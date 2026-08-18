# Architecture Overview

## 1. Components

```
                         ┌──────────────────────────┐
                         │        Client            │
                         └─────────────┬────────────┘
                                        │
                    ┌───────────────────┴───────────────────┐
                    │                                        │
            GET /{shortCode}                       /api/v1/urls/**  (+ /api/v1/cache/**, /actuator/**)
                    │                                        │
        ┌───────────▼────────────┐              ┌────────────▼─────────────┐
        │   RedirectController    │              │       UrlController        │
        │  (hot path, redirect)   │              │  (create/inspect/list/     │
        │                         │              │   delete/analytics)        │
        └───────────┬────────────┘              └────────────┬─────────────┘
                    │                                        │
       RateLimitInterceptor (redirect bucket)     RateLimitInterceptor (create bucket, POST only)
                    │                                        │
                    └───────────────────┬────────────────────┘
                                        │
                              ┌─────────▼──────────┐
                              │     UrlService       │
                              │  (validation,         │
                              │   orchestration)       │
                              └──┬────────────────┬──┘
                                 │                │
                      ┌──────────▼───────┐  ┌─────▼─────────────┐
                      │     UrlCache       │  │  ShortCodeGenerator │
                      │ (Caffeine, TTL +   │  │  (random Base62 +   │
                      │  bounded size —    │  │   collision retry)  │
                      │  simulated Redis)  │  └────────────────────┘
                      └──────────┬────────┘
                                 │ cache-aside fallback
                      ┌──────────▼────────┐
                      │    UrlRepository    │
                      │  (in-memory "DB")   │
                      └────────────────────┘

        ┌──────────────────────────────────────────────────┐
        │  AnalyticsService.recordClickAsync (fire-and-      │
        │  forget, on a dedicated bounded thread pool)        │
        └───────────────────────┬────────────────────────────┘
                                 │
                      ┌──────────▼──────────────┐
                      │  ClickEventRepository      │
                      │  (in-memory, incremental    │
                      │   aggregates via LongAdder) │
                      └─────────────────────────────┘

        ┌──────────────────────────────────────────────────┐
        │  ExpiredLinkSweeper (@Scheduled background job)     │
        │  deactivates expired links + evicts cache            │
        └──────────────────────────────────────────────────┘
```

Package layout mirrors this: `controller` (HTTP boundary), `service` (orchestration and
business rules), `repository` (the "database" abstraction), `cache` (the "Redis"
abstraction), `config` (cross-cutting: rate limiting, async executor, typed properties),
`dto` (request/response shapes, kept separate from domain entities so the API contract
can evolve independently of internal representation), `exception` (domain exceptions +
one central handler), `model` (domain entities), `util` (stateless helpers).

## 2. Control flow

**Create (`POST /api/v1/urls`):** request hits the create-bucket rate limiter →
`UrlController` → `UrlService.createShortUrl`. The long URL is validated (well-formed,
absolute, `http`/`https` only — see §4). If a custom alias was requested, it's inserted
directly via `UrlRepository.saveIfAbsent`, which returns `false` on a naming collision
(mapped to `409 Conflict`). Otherwise `ShortCodeGenerator` produces a random Base62
candidate and the same atomic insert is attempted, retried up to
`app.short-code.max-generation-attempts` times. The cache is **not** written on create —
it's a pure read-through cache, populated lazily by the first redirect. This keeps the
cache as a strict derived view of the repository, which is simpler to reason about than
trying to keep two write paths in sync.

**Redirect (`GET /{shortCode}`):** request hits the (separate, more permissive)
redirect-bucket rate limiter → `RedirectController` → `UrlService.resolve`, which checks
the cache first, falls back to the repository on a miss (populating the cache on the way
out), and throws `UrlNotFoundException` (404) or `UrlGoneException` (410, expired or
deleted) as appropriate. On success, the controller fires `AnalyticsService
.recordClickAsync` (see §3) and returns a `302 Found` immediately — analytics recording
never sits on the response's critical path.

**Analytics (`GET /api/v1/urls/{shortCode}/analytics`):** reads directly from
`ClickEventRepository`'s incrementally-maintained aggregates (total count, referrer
breakdown, daily breakdown) plus a bounded recent-events window — no per-request scan
over raw events (see §4, "Click analytics storage").

**Delete (`DELETE /api/v1/urls/{shortCode}`):** soft delete — flips `active` to `false`
and evicts the cache entry. Soft delete rather than hard delete was chosen so a
still-cached entry elsewhere can't resurrect a "deleted" link's data, and so the
short code itself can never be reissued to a different destination later (an important
property for a shortener: a link that has been shared shouldn't ever silently start
pointing somewhere else).

## 3. Reliability features and why each one exists

**Cache-aside layer (simulated Redis).** `UrlCache` is an interface; `CaffeineUrlCache`
is the only implementation, backed by an in-process Caffeine cache configured with
`expireAfterWrite` (TTL) and `maximumSize` (bounded, approximate-LRU eviction) — the two
properties that matter most about how a real Redis-backed cache would behave in front of
this service. This was a deliberate simulation choice (confirmed with the requester) so
the prototype runs with zero external infrastructure while still demonstrating the
caching *pattern* a production deployment would use. The interface boundary is the point:
swapping in Spring Data Redis / Lettuce later means implementing `UrlCache` again, not
touching `UrlService` or any controller. **Known limitation:** this cache is local to a
single JVM. In a horizontally-scaled deployment, each instance has its own cache, so a
write on instance A doesn't invalidate instance B's cached copy until TTL expiry —
bounded staleness (at most `cache.ttl-minutes`), not unbounded, but real staleness
nonetheless. A shared Redis cluster is the natural fix and requires no service-layer
changes given the `UrlCache` boundary.

**Rate limiting.** Two independent token buckets (`TokenBucketRateLimiter`), keyed by
client IP, one for the create endpoint (stricter — creating links is the more
expensive/abusable operation) and one for redirects (looser — this is the path real
users click through). Token bucket was chosen over a fixed-window counter because it
smooths bursts without a hard reset boundary. **Known limitation:** this limiter is
per-JVM-instance, not shared — in a horizontally-scaled deployment each instance enforces
its own limit, so the *effective* system-wide limit scales with instance count. A shared
implementation (Redis `INCR` + TTL, or a Lua-scripted token bucket) is the production
fix. Also: the interceptor trusts `X-Forwarded-For` when present, which is only safe
behind a proxy that's guaranteed to set/overwrite that header — a public deployment
without such a proxy in front of it should not honor client-supplied
`X-Forwarded-For` as-is (this is a standard footgun with IP-based rate limiting and is
called out explicitly rather than silently shipped).

**Async, fire-and-forget analytics.** `AnalyticsServiceImpl.recordClickAsync` runs on a
dedicated, bounded `ThreadPoolTaskExecutor` (core 4 / max 16 / queue 1000,
`CallerRunsPolicy` on overload) and swallows its own exceptions. The redirect must
succeed even if analytics recording is slow, backed up, or broken — a redirect service
that fails a click because its analytics pipeline hiccuped has its priorities backwards.
The trade-off is explicit: under sustained overload, `CallerRunsPolicy` back-pressures
the calling thread rather than dropping work outright, but a determined enough attacker
could still degrade analytics fidelity before they could break redirects — an acceptable
asymmetry for this system.

**Collision-safe short-code generation.** Random Base62 (not a sequential counter) so
codes aren't enumerable/guessable, with a bounded retry loop against the repository's
atomic `putIfAbsent`-style insert to close the check-then-act race between "is this code
free?" and "reserve it." See `ShortCodeGenerator`'s Javadoc for the full probability
argument (62^7 ≈ 3.5 trillion codes at the default length).

**Expiration handling, two layers.** `UrlService.resolve` lazily deactivates an expired
mapping the instant it's next looked up (so correctness never depends on a background
job's timing), and `ExpiredLinkSweeper` runs on a schedule to proactively tidy up links
that expire without ever being clicked again — otherwise they'd sit "expired but still
flagged active" in the repository indefinitely, which is harmless but makes list views
misleading.

**Bounded click-event storage.** A naive implementation would keep an unbounded list of
every click ever recorded per short code. `InMemoryClickEventRepository` instead
maintains aggregates incrementally with `LongAdder` (O(1) writes, O(1)/O(k) reads) and
retains only the most recent 200 raw events per short code for the "recent activity"
view — memory stays bounded regardless of a link's lifetime traffic.

**Security: scheme allowlisting on submitted URLs.** `UrlServiceImpl` rejects anything
that isn't an absolute `http://` or `https://` URL. This service issues real HTTP
redirects for whatever URL it's given; without this check it would happily accept
`javascript:`, `data:`, or `file:` URIs and become an open redirector / XSS delivery
mechanism. This was treated as a required security control, not an optional nicety.

## 4. Key design decisions and trade-offs (condensed)

| Decision | Chosen approach | Alternative considered | Why |
|---|---|---|---|
| Short-code generation | Random Base62 + collision retry | Monotonic counter → Base62 | Counter is collision-free by construction but makes every link in the system sequentially enumerable — a real privacy/security concern for a public shortener |
| Cache layer | In-process Caffeine, simulating Redis TTL/eviction semantics | Stand up a real Redis container | Zero external infra for a prototype meant to run anywhere; interface boundary (`UrlCache`) makes the swap a contained change later |
| Persistence | In-memory `ConcurrentHashMap`, behind a `UrlRepository` interface | SQLite/Postgres | Fastest to build and run for a 2–3 day prototype scope; not durable across restarts — the single biggest limitation of this build, flagged deliberately rather than glossed over |
| Click count | Single source of truth: `ClickEventRepository` aggregates | A counter field on `UrlMapping`, updated alongside the event log | An earlier draft kept both and the entity counter was never actually wired up — a real bug caught in review (see `AI_TRACEABILITY.md`). Two counters for one fact is a correctness risk with no upside; removed the duplicate |
| Rate limiting algorithm | Token bucket, in-process, per-IP | Fixed window counter; external library (Bucket4j) | Token bucket smooths bursts better than fixed windows; hand-rolled to avoid an external dependency for ~80 lines of well-understood logic and to keep the retry-after estimation exact for this codebase's needs |
| Redirect status code | 302 Found | 301 Moved Permanently | 301 lets browsers cache the redirect target and stop hitting this service after the first visit, which breaks both link expiration/deletion and click analytics |
| Delete semantics | Soft delete (deactivate) | Hard delete (remove from map) | A short code must never be silently reissued to a different destination once it's been shared |

## 5. What a production evolution would add

Not built here, deliberately out of scope for a prototype, listed so the boundary is
explicit rather than implied: a real database (Postgres) behind `UrlRepository`, a real
shared Redis behind `UrlCache`, a shared rate-limit store, authentication/ownership of
links (currently anyone can create/delete/inspect any link — there's no access control
model at all), custom domains, structured click-analytics export to a real
warehouse/stream instead of in-process aggregation, and a CI pipeline running `mvn
verify` plus static analysis on every change (this prototype's "quality gate" was manual
review in a sandbox that couldn't reach a package registry — see
`docs/ENGINEERING_SUMMARY.md` for how that gap was handled here).
