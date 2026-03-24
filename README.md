# SafePay

> 간편 송금 시스템 — 분산 락 + 비관적 락 이중 방어 · 멱등성 보장 · PII 암호화

금융 도메인의 핵심 과제인 **잔액 정합성**, **중복 거래 방지**, **개인정보 암호화**를 직접 설계·구현한 백엔드 프로젝트입니다.

## 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 3.2.3, Spring Security, Spring Data JPA |
| Database | MySQL 8.0 (InnoDB) |
| Cache / Lock / Session | Redis 7 (Redisson, Refresh Token 저장) |
| Auth | JWT (jjwt, BCrypt), Refresh Token Rotation |
| Encryption | AES-256-GCM, HMAC-SHA-256 |
| Infra | Docker Compose, GitHub Actions CI |
| Test | JUnit 5, Mockito, Testcontainers (MySQL + Redis), AssertJ |
| Docs | SpringDoc OpenAPI (Swagger) |

## 아키텍처

```
Client
  ↓ HTTP (REST API)
┌──────────────────────────────────────────────────────────────────┐
│ Spring Boot 3.2.3                                                │
│                                                                  │
│  AuthController ──→ MemberService                                │
│    POST /auth/signup        BCrypt + AES-256 암호화              │
│    POST /auth/login         JWT 발급 + Redis에 Refresh Token 저장│
│    POST /auth/refresh       Rotation + Reuse Detection           │
│    POST /auth/logout        Redis에서 Refresh Token 즉시 삭제    │
│                                                                  │
│  AccountController ──→ AccountService                            │
│    POST /accounts           Luhn 계좌번호 + AES 암호화           │
│    GET  /accounts           복호화 반환                          │
│    GET  /accounts/{id}      소유자 검증                          │
│                                                                  │
│  TransactionController ──→ TransactionService                    │
│    POST /accounts/{id}/deposit    분산 락 + 비관적 락 + 멱등성   │
│    POST /accounts/{id}/withdraw   잔액 검증                      │
│    POST /accounts/{id}/transfer   데드락 방지 (오름차순 락)      │
│    GET  /accounts/{id}/transactions  페이징 조회                 │
│                                                                  │
│  SecurityConfig ──→ JwtAuthFilter (Stateless)                    │
│  GlobalExceptionHandler (도메인 에러 코드)                       │
└──────────────────────────────────────────────────────────────────┘
  ↓                 ↓
Redis 7            MySQL 8.0
(분산 락,          (비관적 락, 데이터 저장)
 Refresh Token)
```

## 핵심 설계

### 동시성 제어 — 분산 락 + 비관적 락 이중 방어

```
요청 → Redis 분산 락 (1차 필터링) → DB 비관적 락 (2차 안전망) → 비즈니스 로직
         Redisson tryLock               SELECT ... FOR UPDATE
         waitTime: 3s                   InnoDB Row Lock
         leaseTime: 5s
```

- **멀티 인스턴스 환경**: Redis 분산 락이 DB 커넥션 풀 고갈을 방지
- **Graceful Degradation**: Redis 장애 시 비관적 락만으로 자동 폴백
- **단일 인스턴스**: 비관적 락만으로도 정합성 보장

→ [ADR-001 동시성 제어](https://github.com/PSW99/SafePay/wiki/ADR-001-동시성-제어)
→ [ADR-006 분산 락 도입](https://github.com/PSW99/safepay/wiki/ADR-006-Redis-%EB%B6%84%EC%82%B0-%EB%9D%BD-%EB%8F%84%EC%9E%85)

### 송금 — 데드락 방지

```java
// 계좌 ID 오름차순으로 락 획득 → 순환 대기 불가능
Long firstLockId = Math.min(fromId, toId);
Long secondLockId = Math.max(fromId, toId);
```

A→B와 B→A가 동시에 발생해도 항상 같은 순서로 락을 획득하므로 데드락이 원천 차단됩니다.

→ [ADR-007 송금 동시성 제어](https://github.com/PSW99/safepay/wiki/ADR-007-%EC%86%A1%EA%B8%88-%EB%8F%99%EC%8B%9C%EC%84%B1-%EC%A0%9C%EC%96%B4-%E2%80%94-%EB%8D%B0%EB%93%9C%EB%9D%BD-%EB%B0%A9%EC%A7%80-%EC%A0%84%EB%9E%B5)

### 멱등성 — 계좌 스코프 Idempotency Key

```
POST /api/v1/accounts/{id}/deposit
Header: Idempotency-Key: {UUID}
```

- 동일한 키로 2회 요청 시 잔액은 1번만 변경, 기존 성공 결과를 반환
- `(account_id, idempotency_key)` 복합 유니크 — 계좌 간 정보 유출 방지

→ [ADR-002 멱등성 설계](https://github.com/PSW99/SafePay/wiki/ADR-002-멱등성-설계)

### 인증 — Refresh Token Rotation

```text
로그인 → Access Token(30m) + Refresh Token(7d) 발급, JTI를 Redis에 저장
갱신  → 기존 Refresh Token 폐기 + 새 토큰 쌍 발급 (Rotation)
탈취  → 폐기된 토큰 재사용 감지 → 해당 회원의 전체 세션 무효화 (Reuse Detection)
```

- **1회용 Refresh Token**: 갱신할 때마다 새 토큰 발급, 이전 토큰 즉시 폐기
- **Reuse Detection**: 폐기된 토큰이 재사용되면 탈취로 판단하여 전체 세션 무효화
- **즉시 로그아웃**: Redis에서 Refresh Token 삭제 → 갱신 즉시 차단

→ [ADR-009 Refresh Token Rotation](https://github.com/PSW99/safepay/wiki/ADR-009-Refresh-Token-Rotation-%E2%80%94-%ED%86%A0%ED%81%B0-%ED%83%88%EC%B7%A8-%EB%8C%80%EC%9D%91)

### PII 암호화 + 블라인드 인덱스

| 데이터 | 방식 | 저장 형태 | 용도 |
|--------|------|----------|------|
| 비밀번호 | BCrypt | 해시 | 검증 |
| 전화번호 | AES-256-GCM | 암호문 | 복호화 |
| 계좌번호 | AES-256-GCM | 암호문 | 복호화 |
| 계좌번호 | HMAC-SHA-256 | 블라인드 인덱스 | 유일성 검증 |

AES-GCM은 랜덤 IV로 같은 평문도 매번 다른 암호문을 생성하므로, UNIQUE 제약으로 중복을 잡을 수 없습니다.
HMAC-SHA-256 블라인드 인덱스를 별도 컬럼으로 저장하여 평문 유일성을 보장합니다.

→ [ADR-003 암호화 전략](https://github.com/PSW99/SafePay/wiki/ADR-003-암호화-전략)
→ [ADR-008 블라인드 인덱스](https://github.com/PSW99/safepay/wiki/ADR-008-%EB%B8%94%EB%9D%BC%EC%9D%B8%EB%93%9C-%EC%9D%B8%EB%8D%B1%EC%8A%A4-%E2%80%94-%EC%95%94%ED%98%B8%ED%99%94%EB%90%9C-%EC%BB%AC%EB%9F%BC%EC%9D%98-%EC%9C%A0%EC%9D%BC%EC%84%B1-%EB%B3%B4%EC%9E%A5)

## API

| API | Method | Path | 인증 | 비고 |
|-----|--------|------|:---:|------|
| 회원가입 | POST | `/api/v1/auth/signup` | - | |
| 로그인 | POST | `/api/v1/auth/login` | - | JWT 발급 + Redis 저장 |
| 토큰 갱신 | POST | `/api/v1/auth/refresh` | - | Rotation + Reuse Detection |
| 로그아웃 | POST | `/api/v1/auth/logout` | ✅ | Redis 즉시 삭제 |
| 계좌 개설 | POST | `/api/v1/accounts` | ✅ | HMAC 블라인드 인덱스 |
| 내 계좌 목록 | GET | `/api/v1/accounts` | ✅ | |
| 계좌 상세 | GET | `/api/v1/accounts/{id}` | ✅ | 소유자 검증 |
| 입금 | POST | `/api/v1/accounts/{id}/deposit` | ✅ | 분산 락 + Idempotency-Key |
| 출금 | POST | `/api/v1/accounts/{id}/withdraw` | ✅ | 분산 락 + Idempotency-Key |
| 송금 | POST | `/api/v1/accounts/{id}/transfer` | ✅ | 오름차순 락 + Idempotency-Key |
| 거래 내역 | GET | `/api/v1/accounts/{id}/transactions` | ✅ | 페이징 |

> Swagger UI: `http://localhost:8080/swagger-ui/index.html`

## 테스트

```
test/
├── unit/           — Mockito (Spring 컨텍스트 없음)
│   ├── member/     MemberServiceTest (로그인, 갱신, Reuse Detection, 로그아웃)
│   ├── account/    AccountServiceTest, AccountTest
│   ├── transaction/TransactionServiceTest, TransactionExecutorTest
│   └── global/     AesEncryptorTest, JwtTokenProviderTest,
│                   AccountNumberGeneratorTest, HmacUtilTest,
│                   DistributedLockManagerTest, RefreshTokenStoreTest
├── integration/    — @SpringBootTest + Testcontainers (MySQL 8.0 + Redis 7)
│   ├── AuthControllerTest (회원가입, 로그인, 갱신, Rotation, Reuse Detection, 로그아웃)
│   ├── AccountControllerTest
│   └── TransactionControllerTest
└── concurrency/    — ExecutorService + CountDownLatch
    └── TransactionConcurrencyTest
        ├── 잔액 정합성 (이중 방어)
        ├── 분산 락 동작 검증
        └── 송금 동시성 (데드락 방지)
```

CI 파이프라인 (GitHub Actions):

```
Build → Unit Test → Integration Test (Testcontainers) → Concurrency Test
                         ↓                                    ↓
                    (병렬 실행)                          (병렬 실행)
```

## 실행 방법

### 1. 인프라 실행

```bash
docker compose up -d
```

MySQL 8.0 (3306) + Redis 7 (6379)이 실행됩니다.

### 2. 환경변수 설정

```bash
export JWT_SECRET=your-256-bit-secret-key-for-jwt-signing
export AES_SECRET_KEY=your-32-byte-aes-key-here!!
export HMAC_SECRET_KEY=your-32-byte-hmac-key-here!!
```

### 3. 애플리케이션 실행

```bash
cd backend
./gradlew bootRun
```

### 4. 테스트 실행

```bash
# 전체 테스트
./gradlew test
 
# 단위 테스트만
./gradlew test --tests "com.safepay.unit.*"
 
# 통합 테스트만 (Docker 필요 — Testcontainers)
./gradlew test --tests "com.safepay.integration.*"
 
# 동시성 테스트만
./gradlew test --tests "com.safepay.concurrency.*"
```

## 프로젝트 구조

```
backend/src/main/java/com/safepay/
├── domain/
│   ├── member/
│   │   ├── controller/  AuthController (signup, login, refresh, logout)
│   │   ├── service/     MemberService (Refresh Token Rotation, Reuse Detection)
│   │   ├── repository/  MemberRepository
│   │   ├── entity/      Member
│   │   └── dto/         AuthDto, RefreshDto
│   ├── account/
│   │   ├── controller/  AccountController
│   │   ├── service/     AccountService (블라인드 인덱스 기반 중복 검사)
│   │   ├── repository/  AccountRepository
│   │   ├── entity/      Account (deposit, withdraw, isOwnedBy)
│   │   └── dto/         AccountDto
│   └── transaction/
│       ├── controller/  TransactionController
│       ├── service/     TransactionService (분산 락 오케스트레이션)
│       │                TransactionExecutor (비관적 락 + 비즈니스 로직)
│       ├── repository/  TransactionRepository
│       ├── entity/      Transaction (팩토리 메서드)
│       └── dto/         TransactionDto, TransferDto
└── global/
    ├── config/          SecurityConfig, SwaggerConfig, JpaConfig, RedissonConfig
    ├── exception/       ErrorCode, CustomException, GlobalExceptionHandler
    ├── security/        JwtTokenProvider (JTI 지원), JwtAuthFilter,
    │                    CustomUserPrincipal, RefreshTokenStore (Redis)
    └── util/            AesEncryptor, HmacUtil, AccountNumberGenerator,
                         DistributedLockManager
```

## Wiki

| 문서 | 내용 |
|------|------|
| [ADR-001 동시성 제어](https://github.com/PSW99/SafePay/wiki/ADR-001-동시성-제어) | 분산 락 + 비관적 락 이중 방어 |
| [ADR-002 멱등성 설계](https://github.com/PSW99/SafePay/wiki/ADR-002-멱등성-설계) | 계좌 스코프 Idempotency Key |
| [ADR-003 암호화 전략](https://github.com/PSW99/SafePay/wiki/ADR-003-암호화-전략) | AES-256-GCM, BCrypt, HMAC-SHA-256 |
| [ADR-004 인증 방식](https://github.com/PSW99/SafePay/wiki/ADR-004-인증-방식) | JWT Stateless 인증 설계 |
| [ADR-005 에러 처리 전략](https://github.com/PSW99/safepay/wiki/ADR-005-%EC%97%90%EB%9F%AC-%EC%B2%98%EB%A6%AC-%EB%B0%8F-%EC%97%90%EB%9F%AC-%EC%BD%94%EB%93%9C-%EC%B2%B4%EA%B3%84) | 도메인 에러 코드 체계 (AUTH_001~006, TX_001~007) |
| [ADR-006 분산 락 도입](https://github.com/PSW99/safepay/wiki/ADR-006-Redis-%EB%B6%84%EC%82%B0-%EB%9D%BD-%EB%8F%84%EC%9E%85) | Redisson 분산 락 + Graceful Degradation |
| [ADR-007 송금 동시성 제어](https://github.com/PSW99/safepay/wiki/ADR-007-%EC%86%A1%EA%B8%88-%EB%8F%99%EC%8B%9C%EC%84%B1-%EC%A0%9C%EC%96%B4-%E2%80%94-%EB%8D%B0%EB%93%9C%EB%9D%BD-%EB%B0%A9%EC%A7%80-%EC%A0%84%EB%9E%B5) | 오름차순 락 데드락 방지 전략 |
| [ADR-008 블라인드 인덱스](https://github.com/PSW99/safepay/wiki/ADR-008-%EB%B8%94%EB%9D%BC%EC%9D%B8%EB%93%9C-%EC%9D%B8%EB%8D%B1%EC%8A%A4-%E2%80%94-%EC%95%94%ED%98%B8%ED%99%94%EB%90%9C-%EC%BB%AC%EB%9F%BC%EC%9D%98-%EC%9C%A0%EC%9D%BC%EC%84%B1-%EB%B3%B4%EC%9E%A5) | HMAC-SHA-256 평문 유일성 보장 |
| [ADR-009 Refresh Token Rotation](https://github.com/PSW99/safepay/wiki/ADR-009-Refresh-Token-Rotation-%E2%80%94-%ED%86%A0%ED%81%B0-%ED%83%88%EC%B7%A8-%EB%8C%80%EC%9D%91) | 토큰 탈취 대응 + Reuse Detection |
| [Troubleshooting-001](https://github.com/PSW99/safepay/wiki/Troubleshooting%E2%80%90001%E2%80%90%ED%86%B5%ED%95%A9%ED%85%8C%EC%8A%A4%ED%8A%B8%E2%80%90%EC%8B%A4%ED%8C%A8) | Testcontainers 싱글턴 컨테이너 패턴 |
| [Troubleshooting-002](https://github.com/PSW99/safepay/wiki/Troubleshooting%E2%80%90002:-%EB%A9%B1%EB%93%B1%EC%84%B1-%ED%82%A4-%EB%8F%99%EC%8B%9C-%EC%9A%94%EC%B2%AD-%EC%8B%9C-Hibernate-%EC%84%B8%EC%85%98-%EA%B9%A8%EC%A7%90) | 멱등성 키 동시 요청 시 Hibernate 세션 깨짐 |
| [Troubleshooting-003](https://github.com/PSW99/safepay/wiki/Troubleshooting%E2%80%90003:-@Transactional-Self%E2%80%90Invocation%EC%9C%BC%EB%A1%9C-%ED%8A%B8%EB%9E%9C%EC%9E%AD%EC%85%98-%EB%AF%B8%EC%A0%81%EC%9A%A9) | @Transactional Self‑Invocation으로 트랜잭션 미적용 |
 