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
| 4 | Observability: OpenTelemetry, Prometheus, Grafana, SLOs | ✅ |
| 5 | AI: embeddings, hybrid search, live-feature recommendations | ✅ |
| 6 | Assistant: LLM behind a breaker, semantic cache, guardrails | ✅ |
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

## Observability

Every service exports traces over OTLP to Jaeger and metrics to Prometheus;
Grafana has one dashboard (latency, errors, degraded rows, breakers, cache,
consumer lag, error budget). Trace ids ride in the W3C `traceparent` header
over HTTP and in a Kafka message header, and appear in log lines as
`[traceId-spanId]`.

Two SLOs for the home page, each 99% over 30 days:

| SLO | Good event |
|---|---|
| Fast | `/home` answered 2xx in under 300 ms |
| Complete | a page with no degraded rows |

The second exists because of the resilience work: with history down, pages
still return 200 in ~20 ms, so a latency SLO alone says everything is fine.
Alerts are multi-window burn rates (page at 14.4x over 1 h and 5 min, ticket
at 6x over 6 h and 30 min), written as Prometheus recording and alert rules
in `infra/prometheus/slo-rules.yml`.

Live, with all six services running on the host:

| Check | Result |
|---|---|
| One `/home` request | one trace, 11 spans: gateway, home, history, catalog |
| Playback start | one trace across HTTP and Kafka: gateway → playback (send) → history (receive) |
| Prometheus | all 6 services scraped |
| History killed under ~20 pages/s | fast SLO unaffected; complete SLO burn rate 12x at 10 s, 56x at 80 s; paging alert fired 80 s after the kill (1 min `for`) |
| Log line for a degraded page | `WARN ... [64d2...e951-f315...9dc76] HomeService: home page degraded: [history]` |

A test sends `/home` with a known trace id and checks that all downstream
calls carry it. Two bugs each make it fail: running rows on a thread pool
without copying the trace context (5 different trace ids), and building the
HTTP client with `RestClient.builder()` instead of Spring's instrumented
builder (no `traceparent` sent at all).

Found on the way: the "complete" ratio read "no data" instead of 0 while
all was well, because the `complete="false"` counter didn't exist until the
first bad page. Both counters are now registered at startup.

## AI search and live recommendations

**Search** (`GET /search?q=`, in catalog). Each title has a 384-dimension
embedding of its name, genres and description, from all-MiniLM-L6-v2 run
in-process with ONNX (no API key, no model server), stored in Postgres with
pgvector and an HNSW index. A query runs two searches at once, keyword
(Postgres full-text) and meaning (nearest embeddings), and merges them with
reciprocal rank fusion: each list contributes 1 / (60 + rank), so a title
near the top of both beats one at the top of just one, and ts_rank and
cosine distance never have to be put on one scale.

The model is treated as a dependency: a 150 ms limit and at most 8
embeddings in flight. Past either, search answers with keyword results and
says `"mode": "KEYWORD"`. Titles are embedded off the write path: a new or
edited title is keyword-searchable at once and meaning-searchable within
seconds, and a write never waits for (or fails because of) the model.

**Recommendations** (new `recs-service`, `GET /recs/for-you`). Its own
Kafka consumer group on the playback events keeps a taste vector per user
in Redis: a time-decayed sum of the embeddings of what they watched,
weighted by minutes watched (half-life 3 days). "For you" is the titles
nearest to it, minus what they've seen; a new user gets today's trending
titles instead. An event's changes (taste, seen set, trending, done flag)
are applied in one Lua script, so a redelivered event changes nothing. The
home page has a new "For you" row; if recs is down it's left out.

Live, all seven services running, through the gateway:

| Check | Result |
|---|---|
| "lonely robot searching for its makers" | top 3 are titles about robots, found by both halves; "The Lost Machine" (an android looking for its makers) is found by meaning only and ranks just below them |
| "bank robbery" | three crime titles; "The Broken Code" (hackers draining a crypto exchange) by meaning only |
| Search latency | ~20 ms warm (first query 134 ms) |
| New user | "Trending today" (rebuilt from the topic's history: the new consumer group read it from the start) |
| After 30 min of sci-fi | 4 of 5 recommendations sci-fi |
| After 70 more min of romance | romance and romantic comedy take over |
| Recs killed | `/home` 200 in 57 ms, every row but "For you" |

Tests: search against real pgvector and the real model (7), taste maths
(4), recs against real Kafka and Redis with a fake catalog whose
embeddings are known (6), home with recs slow or down (2), gateway keeping
`/titles/nearest` and `/titles/{id}/embedding` internal (1). Switching off
each safeguard makes a test fail: apply-once check, excluding watched
titles, time decay, embedding time limit, embedding cap, re-embedding on
edit.

Where it's weak, measured rather than hidden: one-word queries ("heist")
embed poorly, so the meaning half adds noise; and keyword matches in a
title's name can outrank better meaning matches ("cosmic voyage" puts a
cruise-ship thriller called *The Quiet Voyage* first). Weighting the two
lists, or a re-ranker, would be the next step.

## Assistant

`POST /assistant/ask {"question": "..."}` (new `assistant-service`): Claude
answers "what should I watch?", grounded in the catalog.

```
guard input → semantic cache → daily budget → retrieve (catalog hybrid search)
            → Claude (bulkhead → breaker → retry → 8 s timeout) → guard output → cache
```

- **Grounded (RAG).** The catalog's hybrid search picks 8 titles; the model
  may recommend only those, by id, through a forced tool call with a JSON
  schema (no free text to parse). Ids it makes up are dropped and counted.
  If the catalog is down the model isn't asked at all: no context, no answer.
- **Guardrails.** Questions are capped at 300 characters and stripped of
  angle brackets, so a question can't close its `<question>` tag and pose as
  instructions; catalog text is treated the same way. Off-topic questions are
  refused (the model sets `on_topic: false`) and not cached. Answers are
  length-capped.
- **Cost.** A per-user (20) and global (500) daily cap on LLM calls, checked
  and counted atomically in Redis; if Redis is down it fails closed. Cache
  hits are free. Token counts are exported as metrics.
- **Always an answer.** Slow, down, rate-limited, over budget, breaker open,
  or no key: the user gets the search results with a plain sentence, and the
  response names the reason.
- **Semantic cache,** tuned from measurements rather than guessed. With this
  embedding model "something funny set in space" vs "something scary set in
  space" scores 0.785, higher than a genuine paraphrase ("a funny space
  movie", 0.731), and questions differing only in a number score above 0.93.
  A threshold low enough for paraphrases would hand the comedy answer to
  someone asking for horror, so it's 0.93 on normalised text (case and
  punctuation removed): it catches rewordings like "A space adventure!",
  never a different request. A better cache key would need a stronger
  embedding model or an extracted intent (genre, mood) rather than raw text.

The key is read only from `STREAM_HUB_ANTHROPIC_KEY`, never from
`ANTHROPIC_API_KEY` (on a developer machine that's often another account's,
such as a work one); a test fails if it's ever picked up. Without a key the
service still runs and answers from search.

Tests: 11, against real Redis and the real embedding model, with a fake
Messages API that records every request. Switching off each guard makes a
test fail: dropping unknown ids, stripping brackets, the cache threshold
(0.7 serves the wrong answer), the budget, not retrying timeouts, not
caching refusals, the breaker, ignoring `ANTHROPIC_API_KEY`, and refusing
to answer without retrieval.

## Run it

```
docker compose -f infra/docker-compose.yml up -d
mvn install -DskipTests
java -jar catalog-service/target/catalog-service-0.1.0-SNAPSHOT.jar   # :8181
java -jar user-service/target/user-service-0.1.0-SNAPSHOT.jar         # :8182
java -jar playback-service/target/playback-service-0.1.0-SNAPSHOT.jar # :8183
java -jar history-service/target/history-service-0.1.0-SNAPSHOT.jar   # :8184
java -jar recs-service/target/recs-service-0.1.0-SNAPSHOT.jar         # :8186
java -jar home-service/target/home-service-0.1.0-SNAPSHOT.jar         # :8185
STREAM_HUB_ANTHROPIC_KEY=sk-ant-... \
  java -jar assistant-service/target/assistant-service-0.1.0-SNAPSHOT.jar  # :8187, key optional
java -jar gateway/target/gateway-0.1.0-SNAPSHOT.jar                   # :8180, the only public one

curl -H 'Content-Type: application/json' \
     -d '{"email":"me@example.com","password":"a-good-password","displayName":"Me"}' \
     localhost:8180/users/register
curl -H 'Content-Type: application/json' \
     -d '{"email":"me@example.com","password":"a-good-password"}' localhost:8180/auth/login
curl -H "Authorization: Bearer <token>" localhost:8180/titles/1
curl -H "Authorization: Bearer <token>" 'localhost:8180/search?q=lost+in+space'
curl -H "Authorization: Bearer <token>" localhost:8180/recs/for-you
curl -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' \
     -d '{"question":"something scary for tonight"}' localhost:8180/assistant/ask

mvn test                                         # needs Docker running

open http://localhost:16686   # Jaeger: traces
open http://localhost:3000    # Grafana: the stream-hub dashboard
open http://localhost:9090/alerts   # Prometheus: SLO alerts
```

## Design

- [HLD](docs/hld.html): services, events, scale, APIs, deep dives, build order
- [Catalog cache LLD](docs/lld-catalog-cache.html): cache-aside, stampede protection
- [Playback events LLD](docs/lld-playback-events.html): Kafka, idempotent consumer, DLT
- [Resilience LLD](docs/lld-resilience.html): deadline, circuit breaker, bulkhead, retry, fallback
- [Observability LLD](docs/lld-observability.html): traces across services and Kafka, metrics, SLOs, burn-rate alerts

## Layout

```
auth-common/       JWT issue and verify, shared
gateway/           entry point: JWT check, rate limits, routing, timeouts
catalog-service/   titles, Redis cache-aside with stampede protection; hybrid search (pgvector + full-text)
user-service/      accounts, BCrypt, login
events-common/     Kafka event types
playback-service/  sessions in Redis, publishes playback events
history-service/   idempotent consumer: continue watching, watch time
recs-service/      live recommendations: taste vectors from playback events, in Redis
assistant-service/ Claude-backed assistant: RAG over catalog search, guardrails, semantic cache, budget
home-service/      home page: parallel rows, deadline, breakers, bulkheads, fallbacks
infra/             docker-compose for Postgres, Redis, Kafka, Jaeger, Prometheus, Grafana;
                   Prometheus SLO rules and alerts, Grafana dashboard
docs/              design pages
```
