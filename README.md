# stream-hub
Streaming-platform backend with Kafka, Redis, circuit breakers and AI recommendations.

The backend of a video streaming service: browse a catalog, search it,
press play, pick up where you left off, and get recommendations that react
to what you just watched. Microservices around a Kafka event stream, with
caching, resilience and observability throughout, plus AI search and an
assistant that keeps working when the AI doesn't.

## Status

| Step | Scope | State |
|---|---|---|
| 1 | Catalog + user + gateway, Postgres, Redis cache | ✅ |
| 2 | Playback + Kafka + idempotent consumers, DLQ | ✅ |
| 3 | Resilience: timeouts, circuit breakers, bulkheads, rate limits | ✅ |
| 4 | Observability: OpenTelemetry, Prometheus, Grafana, SLOs | ⬜ |
| 5 | AI: embeddings, hybrid search, live-feature recommendations | ⬜ |
| 6 | Assistant: LLM behind a breaker, semantic cache, guardrails | ⬜ |
| 7 | Kubernetes, autoscaling, load tests, measured results | ⬜ |

## Catalog cache

Reads go through Redis with **stale-while-revalidate**: an expired entry is
still served while exactly one request (the one that wins a short Redis
lock) reloads it from Postgres. Tested against real Postgres and Redis
(Testcontainers):

| Scenario | Database reads |
|---|---|
| 200 concurrent requests on a stale key | **1** (87 without the lock) |
| 200 concurrent requests on a cold key | **≤ 2** (200 if nobody waits) |
| 1,000 concurrent requests for an id that doesn't exist | **≤ 2** (1,000 without caching "missing") |
| Redis stopped | reads still succeed, straight from Postgres |

Two races the tests found and the fixes: a request could win the lock just
after another one finished refreshing (fixed with a double-check after
taking the lock), and under heavy CPU contention waiters gave up after a
fixed 200 ms and stampeded anyway (fixed: they wait as long as someone
holds the lock).

## Gateway and accounts

Every request goes through the gateway, which checks the JWT and tells the
services who the user is in `X-User-Id`.

- **A client can't impersonate anyone:** any `X-User-Id` a client sends is
  removed before the token is checked, on every route. Tested at the
  backend: with a spoofed header it still sees the token's user.
- **Tokens:** HS256, issuer and expiry checked, `alg: none` and tampered
  payloads refused (7 unit tests).
- **Logins don't reveal which emails exist:** an unknown email still runs a
  BCrypt check against a dummy hash, so it takes as long as a wrong password
  and returns the same 401. Without that it answered in 4 ms vs 131 ms.
- **Rate limits in Redis:** per user on normal routes, much stricter per IP
  on login and register. **Not yet:** a per-account limit on failed logins;
  5 guesses a second per IP is still ~18,000 an hour.
- **Timeouts:** a backend slower than 2 s gets a 504, not a hung client.

A bug the tests caught: with two rate limiters and one marked `@Primary`,
Spring injected the primary one into the parameter named for the other, so
login silently had the lax limit. Fixed with explicit `@Qualifier`s.

## Playback events

Start, heartbeat (every 30 s) and stop go to the playback service, which
keeps the session in Redis and publishes events to Kafka keyed by user id.
The history consumer turns them into "continue watching" and total watch
time. Kafka delivers at least once, so the consumer makes a second delivery
harmless: each event's id is inserted into `processed_events` in the same
transaction as its effects, and offsets are committed only after it.

| Test (real Kafka, Redis, Postgres) | Result |
|---|---|
| Same event delivered twice | counted once |
| Crash after the DB commit, before the offset commit | redelivered, still counted once |
| An older event arriving late | progress doesn't move back (600 s stays 600, not 100) |
| A message that isn't JSON, or an event that keeps failing | parked on `playback-events.DLT` after 3 retries; the next event on the partition is processed |
| 20 copies of one heartbeat at the same instant | the watched seconds counted once (an atomic Redis script) |
| A retried heartbeat, a seek back, a huge jump | adds 0, 0, at most 60 s |

End to end through the gateway, playing two titles with a repeated heartbeat
each: watch time came out at exactly the expected 250 s.

Bugs found on the way: Spring Kafka's dead-letter topic defaults to
`<topic>-dlt`, not `.DLT`, and publishing to a missing topic left the bad
message blocking its whole partition; and dead-letter producers built from
`KafkaProperties` ignored the test container's address. Both fixed (explicit
DLT name, producers built from Spring's own producer factory).

## Resilient home page

`GET /home` is built from history (continue watching) and catalog (three
genre rows), fetched in parallel under a 300 ms page deadline. Every call
goes through a bulkhead, a circuit breaker and one retry, with its own
timeout. A failed or late row falls back (last good copy, or empty) and the
response lists what was degraded. The page itself never fails.

Live, with real processes, killing services with `kill -9` while calling
`/home` through the gateway:

| Situation | Response | Rows |
|---|---|---|
| All up | 200 in 17 ms | all live |
| History killed | 200 in 56 ms | continue watching unavailable, genre rows live |
| History and catalog killed | 200 in 66 ms | genre rows served stale (last good copy) |
| Both restarted, 6 s later | 200 in 78 ms | all live again (breakers closed after trial calls) |

Chaos tests with fake dependencies that can be made slow, broken or flaky
(7 tests). Switching off each tool makes its test fail: no bulkhead → history
gets more than 20 calls at once; no breaker → a broken history keeps being
called; no retry → a single blip degrades a row; no page deadline → a page
takes 862 ms instead of ~200.

Found on the way: the first page-deadline test couldn't tell whether the
deadline existed, because each call's own timeout already bounded it. A new
test with a slow-but-in-timeout dependency does. And a flaky-looking test
failure turned out to be one test class stopping a fake server another
class still used; the fakes are now shared and never stopped.

## Run it

```
docker compose -f infra/docker-compose.yml up -d
mvn install -DskipTests
java -jar catalog-service/target/catalog-service-0.1.0-SNAPSHOT.jar   # :8181
java -jar user-service/target/user-service-0.1.0-SNAPSHOT.jar         # :8182
java -jar playback-service/target/playback-service-0.1.0-SNAPSHOT.jar # :8183
java -jar history-service/target/history-service-0.1.0-SNAPSHOT.jar   # :8184
java -jar home-service/target/home-service-0.1.0-SNAPSHOT.jar         # :8185
java -jar gateway/target/gateway-0.1.0-SNAPSHOT.jar                   # :8180, the only public one

curl -H 'Content-Type: application/json' \
     -d '{"email":"me@example.com","password":"a-good-password","displayName":"Me"}' \
     localhost:8180/users/register
curl -H 'Content-Type: application/json' \
     -d '{"email":"me@example.com","password":"a-good-password"}' localhost:8180/auth/login
curl -H "Authorization: Bearer <token>" localhost:8180/titles/1

mvn test                                         # needs Docker running
```

## Design

- [HLD](docs/hld.html): services, events, scale, APIs, deep dives, build order
- [Catalog cache LLD](docs/lld-catalog-cache.html): cache-aside, stampede protection
- [Playback events LLD](docs/lld-playback-events.html): Kafka, idempotent consumer, DLT
- [Resilience LLD](docs/lld-resilience.html): deadline, circuit breaker, bulkhead, retry, fallback

## Layout

```
auth-common/       JWT issue and verify, shared
gateway/           entry point: JWT check, rate limits, routing, timeouts
catalog-service/   titles, Redis cache-aside with stampede protection
user-service/      accounts, BCrypt, login
events-common/     Kafka event types
playback-service/  sessions in Redis, publishes playback events
history-service/   idempotent consumer: continue watching, watch time
home-service/      home page: parallel rows, deadline, breakers, bulkheads, fallbacks
infra/             docker-compose for Postgres, Redis and Kafka
docs/              design pages
```
