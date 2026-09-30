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
| 1 | Catalog + user + gateway, Postgres, Redis cache | 🟡 catalog service with stampede-proof cache done |
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

## Run it

```
docker compose -f infra/docker-compose.yml up -d
mvn -pl catalog-service spring-boot:run          # http://localhost:8181/titles/1
mvn test                                         # needs Docker running
```

## Design

- [HLD](docs/hld.html): services, events, scale, APIs, deep dives, build order
- [Catalog cache LLD](docs/lld-catalog-cache.html): cache-aside, stampede protection

## Layout

```
catalog-service/   titles, Redis cache-aside with stampede protection
infra/             docker-compose for Postgres and Redis
docs/              design pages
```
