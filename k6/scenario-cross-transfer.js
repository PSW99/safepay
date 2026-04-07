/**
 * SafePay 부하 테스트 — 시나리오 B: 교차 송금 (락 경합 + 레이턴시)
 *
 * 목표: A↔B 교차 송금을 동시에 발생시켜
 *       분산 락 경합에 의한 p99 레이턴시 급등을 관찰한다.
 *
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// VU + iteration + timestamp를 UUID v4 형식(8-4-4-4-12)에 매핑
function uniqueIdempotencyKey() {
  const hex = (n, len) => n.toString(16).padStart(len, '0').slice(-len);
  const ts = Date.now();
  const vu = typeof __VU !== 'undefined' ? __VU : 0;
  const iter = typeof __ITER !== 'undefined' ? __ITER : 0;
  const r1 = Math.floor(Math.random() * 0xffff);
  const r2 = Math.floor(Math.random() * 0xffffffffffff);
  // xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx
  return `${hex(ts, 8)}-${hex(vu, 4)}-4${hex(iter, 3)}-8${hex(r1, 3)}-${hex(r2, 12)}`;
}

// 커스텀 메트릭
const transferSuccess = new Counter('transfer_success');
const transferFailed = new Counter('transfer_failed');
const errorRate = new Rate('error_rate');
const transferDuration = new Trend('transfer_duration', true);
const concurrencyConflicts = new Counter('concurrency_conflicts');

// 설정
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    cross_transfer: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 10 },
        { duration: '20s', target: 10 },   // baseline
        { duration: '10s', target: 50 },
        { duration: '20s', target: 50 },   // 중간 부하
        { duration: '10s', target: 100 },
        { duration: '30s', target: 100 },  // 고부하
        { duration: '10s', target: 200 },
        { duration: '30s', target: 200 },  // 극한 — 락 경합 집중
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    transfer_duration: ['p(99)<6000'],
    error_rate: ['rate<0.2'],
  },
};

// Setup: 2명 회원 + 2개 계좌 + 초기 잔액
export function setup() {
  const ts = Date.now();

  // 유저 A
  http.post(`${BASE_URL}/api/v1/auth/signup`, JSON.stringify({
    email: `perf-a-${ts}@safepay.com`, password: 'password123',
    name: '유저A', phone: '010-1111-1111',
  }), { headers: { 'Content-Type': 'application/json' } });

  const loginA = JSON.parse(http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
    email: `perf-a-${ts}@safepay.com`, password: 'password123',
  }), { headers: { 'Content-Type': 'application/json' } }).body);

  const accA = JSON.parse(http.post(`${BASE_URL}/api/v1/accounts`, JSON.stringify({
    accountType: 'CHECKING',
  }), {
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${loginA.accessToken}` },
  }).body);

  // 유저 B
  http.post(`${BASE_URL}/api/v1/auth/signup`, JSON.stringify({
    email: `perf-b-${ts}@safepay.com`, password: 'password123',
    name: '유저B', phone: '010-2222-2222',
  }), { headers: { 'Content-Type': 'application/json' } });

  const loginB = JSON.parse(http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
    email: `perf-b-${ts}@safepay.com`, password: 'password123',
  }), { headers: { 'Content-Type': 'application/json' } }).body);

  const accB = JSON.parse(http.post(`${BASE_URL}/api/v1/accounts`, JSON.stringify({
    accountType: 'CHECKING',
  }), {
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${loginB.accessToken}` },
  }).body);

  // 초기 잔액 100만원씩 (충분히 많이 — 잔액 부족 에러 방지)
  for (let i = 0; i < 10; i++) {
    http.post(`${BASE_URL}/api/v1/accounts/${accA.accountId}/deposit`, JSON.stringify({
      amount: 100000, description: 'initial',
    }), {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${loginA.accessToken}`,
        'Idempotency-Key': uniqueIdempotencyKey(),
      },
    });

    http.post(`${BASE_URL}/api/v1/accounts/${accB.accountId}/deposit`, JSON.stringify({
      amount: 100000, description: 'initial',
    }), {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${loginB.accessToken}`,
        'Idempotency-Key': uniqueIdempotencyKey(),
      },
    });
  }

  console.log(`Setup 완료: A=${accA.accountId}, B=${accB.accountId}`);

  return {
    tokenA: loginA.accessToken,
    tokenB: loginB.accessToken,
    accountA: accA.accountId,
    accountB: accB.accountId,
  };
}

// 메인 VU 로직: 교차 송금
export default function (data) {
  // VU 번호 짝수/홀수로 A→B / B→A 분배
  const isAtoB = __VU % 2 === 0;

  const fromToken = isAtoB ? data.tokenA : data.tokenB;
  const fromAccount = isAtoB ? data.accountA : data.accountB;
  const toAccount = isAtoB ? data.accountB : data.accountA;

  const startTime = Date.now();

  const res = http.post(
    `${BASE_URL}/api/v1/accounts/${fromAccount}/transfer`,
    JSON.stringify({
      toAccountId: toAccount,
      amount: 100,  // 소액 — 잔액 부족 방지
      description: `k6-transfer-vu${__VU}`,
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${fromToken}`,
        'Idempotency-Key': uniqueIdempotencyKey(),
      },
      timeout: '10s',
    }
  );

  const duration = Date.now() - startTime;
  transferDuration.add(duration);

  const success = check(res, {
    'transfer 201': (r) => r.status === 201,
    'response < 5s': (r) => r.timings.duration < 5000,
  });

  if (success) {
    transferSuccess.add(1);
    errorRate.add(0);
  } else {
    transferFailed.add(1);
    errorRate.add(1);

    // CONCURRENCY_CONFLICT 감지
    if (res.body && res.body.includes('TX_003')) {
      concurrencyConflicts.add(1);
    }

    if (res.status !== 201) {
      console.warn(`❌ VU${__VU}: status=${res.status}, body=${res.body?.substring(0, 200)}`);
    }
  }

  sleep(Math.random() * 0.3);
}

export function teardown(data) {
  console.log('====================================');
  console.log('  교차 송금 부하 테스트 완료');
  console.log('  Grafana: http://localhost:3001');
  console.log('====================================');
}

export function handleSummary(data) {
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  const dir = __ENV.K6_RESULTS_DIR || './k6/results';
  return {
    [`${dir}/scenario-cross-transfer-${timestamp}.json`]: JSON.stringify(data, null, 2),
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}

import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';
