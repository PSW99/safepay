# SafePay

> 간편 송금 시스템 — 비관적 락 동시성 제어 + 멱등성 보장

금융 도메인의 핵심 과제인 **잔액 정합성**, **중복 거래 방지**, **개인정보 암호화**를 직접 설계·구현한 백엔드 프로젝트입니다.

## 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 3.2.3, Spring Security, Spring Data JPA |
| Database | MySQL 8.0 (InnoDB) |
| Auth | JWT (jjwt, BCrypt |
| Encryption | AES-256-GCM |
| Infra | Docker Compose, GitHub Actions CI |
| Test | JUnit 5, Mockito, Testcontainers, AssertJ |
| Docs | SpringDoc OpenAPI (Swagger) |

## 아키텍처

```
Client
  ↓ HTTP (REST API)
┌──────────────────────────────────────────────────────┐
│ Spring Boot 3.2.3                                    │
│                                                      │
│  AuthController ──→ MemberService                    │
│    POST /auth/signup       BCrypt + AES-256 암호화   │
│    POST /auth/login        JWT Access + Refresh 발급 │
│                                                      │
│  AccountController ──→ AccountService                │
│    POST /accounts          Luhn 계좌번호 + AES 암호화│
│    GET  /accounts          복호화 반환               │
│    GET  /accounts/{id}     소유자 검증               │
│                                                      │
│  TransactionController ──→ TransactionService        │
│    POST /accounts/{id}/deposit     비관적 락 + 멱등성│
│    POST /accounts/{id}/withdraw    잔액 검증         │
│    GET  /accounts/{id}/transactions 페이징 조회      │
│                                                      │
│  SecurityConfig ──→ JwtAuthFilter (Stateless)        │
│  GlobalExceptionHandler (도메인 에러 코드)           │
└──────────────────────────────────────────────────────┘
  ↓
MySQL 8.0 — member, account, transaction
```

## 핵심 설계

### 동시성 제어 — 비관적 락

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Account a WHERE a.id = :id")
Optional<Account> findByIdWithLock(@Param("id") Long id);
```

금융 거래는 충돌 시 비용이 크므로 낙관적 락 대신 `SELECT ... FOR UPDATE`를 선택했습니다. 10개 스레드 동시 출금 테스트로 잔액 정합성을 검증합니다.

→ [ADR-001 동시성 제어](https://github.com/PSW99/SafePay/wiki/ADR-001-동시성-제어)

### 멱등성 — Idempotency Key

```
POST /api/v1/accounts/{id}/deposit
Header: Idempotency-Key: {UUID}
```

동일한 키로 2회 요청 시 잔액은 1번만 변경되고, 기존 성공 결과를 반환합니다.

→ [ADR-002 멱등성 설계](https://github.com/PSW99/SafePay/wiki/ADR-002-멱등성-설계)

### PII 암호화

| 데이터 | 방식 | 저장 형태 |
|--------|------|----------|
| 비밀번호 | BCrypt | 해시 |
| 전화번호 | AES-256-GCM | 암호문 |
| 계좌번호 | AES-256-GCM | 암호문 |

→ [ADR-003 암호화 전략](https://github.com/PSW99/SafePay/wiki/ADR-003-암호화-전략)

## API

| API | Method | Path | 인증 | 비고 |
|-----|--------|------|:---:|------|
| 회원가입 | POST | `/api/v1/auth/signup` | - | |
| 로그인 | POST | `/api/v1/auth/login` | - | JWT 발급 |
| 계좌 개설 | POST | `/api/v1/accounts` | ✅ | |
| 내 계좌 목록 | GET | `/api/v1/accounts` | ✅ | |
| 계좌 상세 | GET | `/api/v1/accounts/{id}` | ✅ | 소유자 검증 |
| 입금 | POST | `/api/v1/accounts/{id}/deposit` | ✅ | Idempotency-Key |
| 출금 | POST | `/api/v1/accounts/{id}/withdraw` | ✅ | Idempotency-Key |
| 거래 내역 | GET | `/api/v1/accounts/{id}/transactions` | ✅ | 페이징 |

> Swagger UI: `http://localhost:8080/swagger-ui/index.html`

## 테스트

```
test/
├── unit/           — Mockito (Spring 컨텍스트 없음)
│   ├── member/     MemberServiceTest
│   ├── account/    AccountServiceTest, AccountTest
│   ├── transaction/TransactionServiceTest
│   └── global/     AesEncryptorTest, JwtTokenProviderTest, AccountNumberGeneratorTest
├── integration/    — @SpringBootTest + Testcontainers (MySQL 8.0)
│   ├── AuthControllerTest
│   ├── AccountControllerTest
│   └── TransactionControllerTest
└── concurrency/    — ExecutorService + CountDownLatch
    └── TransactionConcurrencyTest
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
│   │   ├── controller/  AuthController
│   │   ├── service/     MemberService
│   │   ├── repository/  MemberRepository
│   │   ├── entity/      Member
│   │   └── dto/         AuthDto
│   ├── account/
│   │   ├── controller/  AccountController
│   │   ├── service/     AccountService
│   │   ├── repository/  AccountRepository
│   │   ├── entity/      Account (deposit, withdraw, isOwnedBy)
│   │   └── dto/         AccountDto
│   └── transaction/
│       ├── controller/  TransactionController
│       ├── service/     TransactionService (비관적 락 + 멱등성)
│       ├── repository/  TransactionRepository
│       ├── entity/      Transaction (팩토리 메서드)
│       └── dto/         TransactionDto
└── global/
    ├── config/          SecurityConfig, SwaggerConfig, JpaConfig
    ├── exception/       ErrorCode, CustomException, GlobalExceptionHandler
    ├── security/        JwtTokenProvider, JwtAuthFilter, CustomUserPrincipal
    └── util/            AesEncryptor, AccountNumberGenerator
```

## Wiki

| 문서 | 내용 |
|------|------|
| [ADR-001 동시성 제어](https://github.com/PSW99/SafePay/wiki/ADR-001-동시성-제어) | 낙관적 락 vs 비관적 락 vs 분산 락 비교 |
| [ADR-002 멱등성 설계](https://github.com/PSW99/SafePay/wiki/ADR-002-멱등성-설계) | Idempotency Key 구현 방식 선택 |
| [ADR-003 암호화 전략](https://github.com/PSW99/SafePay/wiki/ADR-003-암호화-전략) | AES-256-GCM, BCrypt 선택 근거 |
| [ADR-004 인증 방식](https://github.com/PSW99/SafePay/wiki/ADR-004-인증-방식) | JWT Stateless 인증 설계 |
| [Troubleshooting-001](https://github.com/PSW99/SafePay/wiki/Troubleshooting-001-통합테스트-실패) | Testcontainers 싱글턴 컨테이너 패턴 |
