/**
 * SafePay 부하 테스트 — 시나리오 C: 고부하 스트레스 테스트 (VU 200~500)
 *
 * 목표: 인위적 지연 없이 순수 동시 요청 수만으로
 *       HikariCP 커넥션 풀 한계점을 찾는다.
 *
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter, Rate, Trend } from 'k6/metrics';

// 커스텀 메트릭
const depositSuccess = new Counter('deposit_success');
const depositFailed = new Counter('deposit_failed');
const errorRate = new Rate('error_rate');
const depositDuration = new Trend('deposit_duration', true);

// 설정
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

//  부하 프로필: 200 → 500 VU
export const options = {
    scenarios: {
        stress: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 50 },   // Warm-up
                { duration: '15s', target: 50 },   // baseline 구간
                { duration: '10s', target: 200 },  // 부하 증가
                { duration: '20s', target: 200 },  // 200 VU 유지
                { duration: '10s', target: 350 },  // 고부하
                { duration: '20s', target: 350 },  // 350 VU 유지
                { duration: '10s', target: 500 },  // 극한 부하
                { duration: '30s', target: 500 },  // 500 VU 유지 ← 한계점 관찰
                { duration: '10s', target: 0 },    // Cool-down
            ],
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<5000'],
        deposit_duration: ['p(99)<10000'],
    },
};

// Setup: 회원가입 + 로그인 + 계좌 생성
export function setup() {
    const timestamp = Date.now();

    const signupRes = http.post(`${BASE_URL}/api/v1/auth/signup`, JSON.stringify({
        email: `stress-${timestamp}@safepay.com`,
        password: 'password123',
        name: '스트레스테스트',
        phone: '010-0000-0000',
    }), { headers: { 'Content-Type': 'application/json' } });

    check(signupRes, { 'signup success': (r) => r.status === 201 });

    const loginRes = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
        email: `stress-${timestamp}@safepay.com`,
        password: 'password123',
    }), { headers: { 'Content-Type': 'application/json' } });

    const loginBody = JSON.parse(loginRes.body);

    check(loginRes, { 'login success': (r) => r.status === 200 });

    const accountRes = http.post(`${BASE_URL}/api/v1/accounts`, JSON.stringify({
        accountType: 'CHECKING',
    }), {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${loginBody.accessToken}`,
        },
    });

    const accountBody = JSON.parse(accountRes.body);

    check(accountRes, { 'account created': (r) => r.status === 201 });

    console.log(`Setup 완료: accountId=${accountBody.accountId}`);

    return {
        accessToken: loginBody.accessToken,
        accountId: accountBody.accountId,
    };
}

// 메인 VU 로직
export default function (data) {
    const idempotencyKey = uuidv4();

    const startTime = Date.now();

    const res = http.post(
        `${BASE_URL}/api/v1/accounts/${data.accountId}/deposit`,
        JSON.stringify({
            amount: 100,
            description: `stress-vu${__VU}-iter${__ITER}`,
        }),
        {
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${data.accessToken}`,
                'Idempotency-Key': idempotencyKey,
            },
            timeout: '15s',
        }
    );

    const duration = Date.now() - startTime;
    depositDuration.add(duration);

    const success = check(res, {
        'deposit 201': (r) => r.status === 201,
        'response < 5s': (r) => r.timings.duration < 5000,
    });

    if (success) {
        depositSuccess.add(1);
        errorRate.add(0);
    } else {
        depositFailed.add(1);
        errorRate.add(1);

        if (res.status !== 201) {
            // 에러 로깅을 줄여서 k6 출력이 넘치지 않도록
            if (__ITER % 10 === 0) {
                console.warn(`❌ VU${__VU}: status=${res.status}, body=${res.body?.substring(0, 100)}`);
            }
        }
    }

    // 대기 시간 최소화 — 최대 부하를 위해
    sleep(Math.random() * 0.1);
}

export function teardown(data) {
    console.log('==========================================');
    console.log('  스트레스 테스트 완료 — Grafana 확인:');
    console.log('  http://localhost:3001');
    console.log('==========================================');
}

export function handleSummary(data) {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const dir = __ENV.K6_RESULTS_DIR || './k6/results';
    return {
        [`${dir}/scenario-stress-${timestamp}.json`]: JSON.stringify(data, null, 2),
        stdout: textSummary(data, { indent: ' ', enableColors: true }),
    };
}

import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';