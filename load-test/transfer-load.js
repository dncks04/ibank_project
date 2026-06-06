/*
 * k6 부하 테스트 — 동일 계좌 동시 이체 처리량/정합성 측정
 *
 * 다수 VU가 같은 출금계좌 A에서 B로 1원씩 동시에 이체한다.
 * A 한 행에 FOR UPDATE 락이 몰려 직렬화되는 최악 경합을 만들고,
 * 종료 후 (A 잔액 + B 잔액)이 초기 총액과 일치하는지 검증한다.
 * 각 요청은 고유 멱등성 키를 사용한다.
 *
 * 실행:
 *   k6 run load-test/transfer-load.js
 *   k6 run -e VUS=100 -e DURATION=1m -e BASE_URL=http://localhost:8080 load-test/transfer-load.js
 *
 * 측정 (50 VU / 30s, 로컬 PostgreSQL 16):
 *   이체 2,639건, 실패율 0%, p95 799ms
 *   A 99,997,361 + B 2,639 = 100,000,000 (초기 총액 일치)
 */
import http from 'k6/http';
import { check, fail } from 'k6';

// 외부 의존 없는 UUID v4 (멱등성 키용)
function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const INITIAL_BALANCE = 100000000; // A 계좌 초기 잔액 (음수 방지용으로 충분히 크게)
const TRANSFER_AMOUNT = 1;

export const options = {
  scenarios: {
    transfers: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],     // 실패율 1% 미만
    http_req_duration: ['p(95)<800'],   // p95 800ms 미만
  },
};

function jsonHeaders(token) {
  const h = { 'Content-Type': 'application/json' };
  if (token) h['Authorization'] = `Bearer ${token}`;
  return h;
}

// 회원가입 → 로그인 → 계좌 2개 개설. 토큰/계좌번호/초기총액을 VU에 전달.
export function setup() {
  const suffix = `${Date.now()}`.slice(-10);
  const loginId = `load${suffix}`;            // 최대 20자 제약 내
  const password = 'loadtest-pass-123';

  const reg = http.post(`${BASE}/api/auth/register`, JSON.stringify({
    loginId, password, name: '부하테스트', email: `${loginId}@load.test`,
  }), { headers: jsonHeaders() });
  if (reg.status !== 201) fail(`register 실패: ${reg.status} ${reg.body}`);

  const login = http.post(`${BASE}/api/auth/login`, JSON.stringify({ loginId, password }),
    { headers: jsonHeaders() });
  if (login.status !== 200) fail(`login 실패: ${login.status} ${login.body}`);
  const token = login.json('data.accessToken');

  const accA = openAccount(token, INITIAL_BALANCE);
  const accB = openAccount(token, 0);

  console.log(`setup 완료: A=${accA}(${INITIAL_BALANCE}) B=${accB}(0)`);
  return { token, accA, accB, initialTotal: INITIAL_BALANCE };
}

function openAccount(token, initialBalance) {
  const res = http.post(`${BASE}/api/accounts`, JSON.stringify({ initialBalance }),
    { headers: jsonHeaders(token) });
  if (res.status !== 201) fail(`계좌 개설 실패: ${res.status} ${res.body}`);
  return res.json('data.accountNumber');
}

// 본 부하: 동일 A→B 이체를 고유 멱등성 키로 반복
export default function (data) {
  const res = http.post(`${BASE}/api/transactions/transfer`, JSON.stringify({
    idempotencyKey: uuidv4(),
    fromAccountNumber: data.accA,
    toAccountNumber: data.accB,
    amount: TRANSFER_AMOUNT,
    description: 'load',
  }), { headers: jsonHeaders(data.token), tags: { name: 'transfer' } });

  check(res, { 'transfer 200': (r) => r.status === 200 });
}

// 정합성 검증: A + B 잔액이 초기 총액과 정확히 일치해야 한다.
export function teardown(data) {
  const a = Number(http.get(`${BASE}/api/accounts/${data.accA}`,
    { headers: jsonHeaders(data.token) }).json('data.balance'));
  const b = Number(http.get(`${BASE}/api/accounts/${data.accB}`,
    { headers: jsonHeaders(data.token) }).json('data.balance'));

  const total = a + b;
  console.log(`정합성 검증: A=${a} + B=${b} = ${total} (기대=${data.initialTotal})`);
  const ok = check(null, {
    '총 잔액 보존 (정합성 OK)': () => total === data.initialTotal,
    'A 잔액 음수 아님': () => a >= 0,
  });
  if (!ok) fail('정합성 위반: 동시 이체 중 잔액이 깨졌습니다');
}
