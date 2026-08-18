# URL Shortener — AI-Assisted Engineering Prototype

A working URL shortener service built in Java / Spring Boot: create short links, redirect
through them, inspect their metadata, and pull click analytics — with an in-memory data
tier, a simulated Redis-style cache layer, rate limiting, async analytics recording, and
expiration handling layered on top for reliability.


## Contents

- `docs/ARCHITECTURE.md` — components, control flow, and the reasoning behind each design decision.
- `docs/SCENARIOS.md` — three worked engineering scenarios (greenfield, brownfield, ambiguous requirement), each showing decomposition, AI-assisted execution, and validation.
- `docs/AI_TRACEABILITY.md` — a log of what AI generated, what the engineer edited or rejected and why, and the quality gates applied.
- `docs/ENGINEERING_SUMMARY.md` — the final summary: plan, artifacts, risks/trade-offs, assumptions, and limitations.

## Tech stack

- Java 21, Spring Boot 3.3 (Web, Validation, Actuator)
- Caffeine (in-process cache, configured to simulate Redis-style TTL + bounded-size eviction — see `docs/ARCHITECTURE.md` for why an in-process simulation was chosen over standing up real Redis for this prototype)
- JUnit 5, Mockito, AssertJ, MockMvc, Awaitility for tests
- Maven

No external services (database, Redis, message broker) are required to run this
prototype — everything runs in a single JVM process. That's a deliberate scope decision
for a 2–3 day prototype, not an accident; see the "Persistence" and "Cache layer"
sections of `docs/ARCHITECTURE.md` for the trade-off and what swapping in real
infrastructure would look like.

## Prerequisites

- JDK 21+
- Maven 3.9+ (or use the included `./mvnw` if present in your environment)

## Setup and run

```bash
# from the project root
mvn clean install        # compiles, runs tests, packages the jar
mvn spring-boot:run       # runs the app on http://localhost:8080

# or, after packaging:
java -jar target/url-shortener-1.0.0.jar
```

The app starts on port 8080 by default (`server.port` in `src/main/resources/application.yml`).

**A note on build verification in this repository's authoring environment:** this
prototype was built inside a network-sandboxed session that could not reach Maven
Central (all outbound requests to `repo.maven.apache.org` and equivalent mirrors were
blocked at the network layer — confirmed via direct `curl` tests, not assumed). That
means `mvn compile` / `mvn test` could not be executed and observed to pass *in that
session*. In lieu of a compiler, every file was manually reviewed line-by-line against
Spring Boot 3.3.x / Jakarta EE 9 API signatures, and one real bug was caught this way
(see `docs/AI_TRACEABILITY.md`, "Click-count double bookkeeping"). On a normal
internet-connected machine or CI runner, `mvn clean install` will resolve dependencies
from Maven Central and run the full test suite normally — there is nothing
environment-specific in the build itself. Treat this as the first thing to confirm
after cloning.

## Configuration

All tunables live under `app.*` in `src/main/resources/application.yml`:

| Property | Default | Meaning |
|---|---|---|
| `app.base-url` | `http://localhost:8080` | Used to build the `shortUrl` field in API responses |
| `app.short-code.length` | `7` | Length of generated (non-custom) short codes |
| `app.short-code.max-generation-attempts` | `5` | Collision retry budget before failing generation |
| `app.cache.max-size` | `10000` | Max entries in the simulated Redis cache |
| `app.cache.ttl-minutes` | `10` | Cache entry TTL |
| `app.rate-limit.enabled` | `true` | Master switch for rate limiting |
| `app.rate-limit.create.*` | 20 req / 60s | Token bucket for `POST /api/v1/urls` |
| `app.rate-limit.redirect.*` | 120 req / 60s | Token bucket for `GET /{shortCode}` |
| `app.cleanup.fixed-delay-ms` | `60000` | Background sweep interval for expiring links |

## API reference

### Create a short URL

```bash
curl -X POST http://localhost:8080/api/v1/urls \
  -H "Content-Type: application/json" \
  -d '{"longUrl": "https://example.com/some/very/long/path"}'
```

Optional fields: `customAlias` (3–20 chars, letters/digits/`-`/`_`), `ttlSeconds`
(positive integer; omit for a link that never expires).

Response (`201 Created`):

```json
{
  "shortCode": "aZ3kQ9b",
  "shortUrl": "http://localhost:8080/aZ3kQ9b",
  "longUrl": "https://example.com/some/very/long/path",
  "customAlias": false,
  "createdAt": "2026-08-17T12:00:00Z",
  "expiresAt": null,
  "active": true,
  "clickCount": 0
}
```

### Redirect (the link end users actually click)

```bash
curl -i http://localhost:8080/aZ3kQ9b
# HTTP/1.1 302 Found
# Location: https://example.com/some/very/long/path
```

### Inspect metadata

```bash
curl http://localhost:8080/api/v1/urls/aZ3kQ9b
```

### Analytics

```bash
curl http://localhost:8080/api/v1/urls/aZ3kQ9b/analytics
```

```json
{
  "shortCode": "aZ3kQ9b",
  "totalClicks": 3,
  "clicksByReferrer": {"direct": 1, "https://twitter.com": 2},
  "clicksByDay": {"2026-08-17": 3},
  "recentClicks": [{"timestamp": "...", "referrer": "...", "userAgent": "..."}]
}
```

### Delete (soft delete)

```bash
curl -X DELETE http://localhost:8080/api/v1/urls/aZ3kQ9b
# 204 No Content; subsequent redirects return 410 Gone
```

### List (paginated)

```bash
curl "http://localhost:8080/api/v1/urls?page=0&size=20"
```

### Cache stats (observability)

```bash
curl http://localhost:8080/api/v1/cache/stats
```

### Health

```bash
curl http://localhost:8080/actuator/health
```

### Error format

Every error response uses the same envelope:

```json
{
  "timestamp": "2026-08-17T12:00:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "No URL found for short code 'xyz'",
  "path": "/api/v1/urls/xyz"
}
```

| Status | When |
|---|---|
| 400 | Invalid/blank `longUrl`, malformed request body |
| 404 | Short code doesn't exist |
| 409 | Custom alias already taken |
| 410 | Short code existed but expired or was deleted |
| 429 | Rate limit exceeded (`Retry-After` header included) |
| 503 | Short-code generation exhausted its collision-retry budget |

## Testing approach

```bash
mvn test
```

- **Unit tests** (`UrlServiceImplTest`, `ShortCodeGeneratorTest`, `TokenBucketRateLimiterTest`,
  `InMemoryClickEventRepositoryTest`, `Base62Test`) isolate one component at a time with
  mocked collaborators — covering validation edge cases, collision handling, expiration/
  deletion state transitions, and rate-limiter refill behavior.
- **Integration tests** (`UrlShortenerIntegrationTest`, `RateLimitIntegrationTest`) boot
  the full Spring context and drive the real HTTP layer with MockMvc, covering the
  end-to-end create → redirect → analytics → delete lifecycle, custom-alias conflicts,
  TTL expiration, pagination, and the 429 rate-limit path.
- Async analytics recording is asserted with `Awaitility` poll-until-true rather than a
  fixed `Thread.sleep`, so the tests aren't flaky under load and don't waste wall-clock
  time waiting longer than necessary.

See `docs/ENGINEERING_SUMMARY.md` for what testing does *not* cover (load/concurrency
testing, multi-instance behavior) and why.
