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
| 2 | Playback + Kafka + idempotent consumers, DLQ | ⬜ |
| 3 | Resilience: timeouts, circuit breakers, bulkheads, rate limits | ⬜ |
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

## Run it

```
docker compose -f infra/docker-compose.yml up -d
mvn install -DskipTests
java -jar catalog-service/target/catalog-service-0.1.0-SNAPSHOT.jar   # :8181
java -jar user-service/target/user-service-0.1.0-SNAPSHOT.jar         # :8182
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

## Layout

```
auth-common/       JWT issue and verify, shared
gateway/           entry point: JWT check, rate limits, routing, timeouts
catalog-service/   titles, Redis cache-aside with stampede protection
user-service/      accounts, BCrypt, login
infra/             docker-compose for Postgres and Redis
docs/              design pages
```
