// Load test for the home page through the gateway.
//
//   k6 run -e BASE=http://localhost:8280 -e RATE=150 load/home.js
//
// Open model: requests arrive at a fixed rate whether or not earlier ones
// have finished (constant/ramping-arrival-rate). A closed loop of N users
// that each wait for their last response slows down with the system and
// under-reports latency exactly when it matters (coordinated omission).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8280';
const RATE = Number(__ENV.RATE || 150);          // pages per second at the plateau
const USERS = Number(__ENV.USERS || 40);          // spread over users: the gateway limits each to 50/s
const degraded = new Counter('degraded_pages');

export const options = {
  scenarios: {
    home: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 600,
      stages: [
        { target: RATE, duration: __ENV.RAMP || '1m' },
        { target: RATE, duration: __ENV.HOLD || '3m' },
        { target: 0, duration: '10s' },
      ],
    },
  },
  thresholds: {
    'http_req_failed{name:home}': ['rate<0.01'],
    'http_req_duration{name:home}': ['p(95)<300'],   // the "fast" SLO's threshold
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// Accounts made once. Register and login are limited to 5/s per IP, so slowly.
export function setup() {
  const run = Date.now();
  const tokens = [];
  for (let i = 0; i < USERS; i++) {
    const body = { email: `load${run}-${i}@example.com`, password: 'a-good-password', displayName: `L${i}` };
    const headers = { 'Content-Type': 'application/json' };
    http.post(`${BASE}/users/register`, JSON.stringify(body), { headers });
    sleep(0.25);
    const r = http.post(`${BASE}/auth/login`, JSON.stringify(body), { headers });
    sleep(0.25);
    if (r.status === 200) tokens.push(r.json('token'));
  }
  if (tokens.length === 0) throw new Error('no logins succeeded');
  return { tokens };
}

export default function (data) {
  const token = data.tokens[Math.floor(Math.random() * data.tokens.length)];
  const r = http.get(`${BASE}/home`, { headers: { Authorization: `Bearer ${token}` }, tags: { name: 'home' } });
  const ok = check(r, { '200': (x) => x.status === 200 });
  if (ok && r.json('degraded').length > 0) degraded.add(1);
}
