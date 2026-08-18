# Three Scenarios: Greenfield, Brownfield, Ambiguous

Per the assignment's requirement to demonstrate requirement understanding, task
decomposition, AI-assisted execution with traceability, and validation across different
kinds of work, this document walks through three representative slices of the actual
build — not hypothetical examples, but how this specific codebase actually grew, in the
order it actually grew in.

---

## Scenario 1 — Greenfield: the core shortener

### Requirement understanding

The source ask ("build a working prototype... with core APIs, analytics, and
reliability features... over 2-3 days") is a program, not a single feature. The first
engineering decision was to scope a v0: the smallest slice that is a *working URL
shortener* at all — create a short link, redirect through it — before adding anything
that makes it *reliable* or *observable*. Ambiguities identified and resolved before
writing code:

- **"Short URL" format** — not specified whether codes should be predictable/sequential
  or opaque. Resolved: opaque/random, for the security reason detailed in
  `ARCHITECTURE.md` (enumerability).
- **Redirect semantics** — 301 vs 302 was not specified but has real behavioral
  consequences (browser caching bypasses the service entirely on 301). Resolved: 302,
  documented as a deliberate choice, not a default.
- **What "long URL" is valid input** — not specified. Resolved: must be an absolute
  `http`/`https` URL; this is both a correctness and a security decision (see
  `ARCHITECTURE.md`, open-redirect concern).

### Task decomposition

1. Domain model (`UrlMapping`) — no dependencies.
2. Repository abstraction + in-memory implementation — depends on (1).
3. Short-code generation with collision handling — depends on (2), needs the repository to check existence against.
4. Long-URL validation logic — no dependencies, but consumed by (5).
5. `UrlService.createShortUrl` / `resolve` — depends on (2), (3), (4).
6. `RedirectController` + `UrlController` (create/get) — depends on (5).
7. Global exception handling, mapping domain exceptions to HTTP status — depends on (6) existing to have something to wrap.

This ordering matters: (3) was deliberately built and unit-testable *before* the
controller existed, because collision handling is the one piece of this slice with
actual algorithmic risk (a bad implementation either collides silently — a correctness
bug — or never terminates — an availability bug). Validating it in isolation first meant
the controller layer could be built with confidence that the thing underneath it was
sound.

### AI-assisted execution

Working task-by-task rather than "write me a URL shortener" in one shot — each task
above was specified with intent + constraints before generation, e.g. for (3): *"Generate
short codes that are not sequentially guessable, detect collisions against the
repository, retry a bounded number of times, and fail loudly (not silently overwrite) if
retries are exhausted."* That constraint (loud failure over silent overwrite) is what
produced `ShortCodeGenerationException` and the explicit `maxAttempts` in
`AppProperties` rather than an unbounded `while(true)` loop, which is what a
less-constrained prompt tends to produce.

One rejected AI suggestion at this stage: a first draft proposed encoding an
auto-incrementing database ID directly to Base62 for short codes. Engineer rejected it
immediately — it's collision-free by construction, which is *attractive*, but it makes
every link in the system sequentially enumerable (`GET /1`, `GET /2`, ... walks every
link ever created). Replaced with the random-generation-plus-retry approach actually in
the codebase. This is logged in more detail in `AI_TRACEABILITY.md`.

### Validation

- `ShortCodeGeneratorTest`: no-collision path, retry-then-succeed path, exhausted-retries
  path (asserts the loud-failure behavior specifically, not just "doesn't crash").
- `UrlServiceImplTest`: URL validation matrix (blank, malformed, disallowed schemes,
  allowed schemes) — this is where the scheme-allowlist decision gets its regression
  protection.
- `UrlShortenerIntegrationTest#fullLifecycle...`: create → redirect → verify `Location`
  header equals the original long URL exactly (byte-for-byte, not just "some redirect
  happened").

---

## Scenario 2 — Brownfield: retrofitting reliability onto the v0

### Requirement understanding / codebase reasoning

Once the v0 above existed and worked, the remaining requirement ("reliability features")
had to be interpreted against *that specific, already-running codebase* rather than
designed in the abstract. That's the brownfield move: before writing anything, the
question was "what in the existing system breaks or degrades under real traffic, and
which module owns the fix?"

Concrete impact analysis performed against the v0 codebase:

- **Every redirect hits the repository directly.** `UrlServiceImpl.resolve` called
  `urlRepository.findByShortCode` unconditionally. Under load, that's every single click
  doing a full lookup with no fast path. *Impacted module:* a new `cache` package sits
  between `UrlService` and `UrlRepository`; `UrlServiceImpl.resolve` is the only method
  that changes.
- **Nothing bounds request rate.** Both `POST /api/v1/urls` and `GET /{shortCode}` were
  wide open. *Impacted modules:* new `config.RateLimitInterceptor` +
  `config.WebConfig`, registered against the existing controllers without modifying
  either controller's method bodies at all — the existing request-handling code didn't
  need to know rate limiting exists.
- **`resolve` was synchronous only** — no analytics existed yet, so adding click
  tracking as a *blocking* call inside the hot redirect path would have been a latency
  regression the moment it landed. *Impacted modules:* new `AnalyticsService` running on
  its own executor, invoked from `RedirectController` but explicitly not awaited.

### Task decomposition (dependency-ordered)

1. `UrlCache` interface + `CaffeineUrlCache` — standalone, no dependency on existing code.
2. Wire `UrlCache` into `UrlServiceImpl.resolve` (cache-aside) and `deleteUrl`/expiry paths (eviction) — depends on (1); this is the only touch point in existing service code.
3. `TokenBucketRateLimiter` (standalone data structure) — no dependency.
4. `RateLimitInterceptor` + `WebConfig` wiring — depends on (3); zero changes to `UrlController`/`RedirectController` bodies.
5. `ClickEventRepository` + `AnalyticsService` (async) — standalone.
6. Wire `AnalyticsService.recordClickAsync` into `RedirectController` (one line, fire-and-forget) — depends on (5).
7. Expose click counts through the existing `UrlResponse`/analytics endpoints — depends on (5), and this is where the click-count bug described in `AI_TRACEABILITY.md` was introduced and then caught.

### AI-assisted execution

Because this was retrofit work, every generation prompt for steps 2, 4, and 6 explicitly
included the constraint *"do not change the existing method signature / do not change
what callers of this class see"* — the goal was additive change with a small, reviewable
diff against working code, which is the brownfield discipline: minimize blast radius.
Step 2 is a good example of that constraint doing its job: the AI-generated first pass of
`resolve()` initially *also* proposed changing the method's return type to
`Optional<UrlMapping>` "for consistency." Rejected — that would have forced every
existing caller (at the time, just the not-yet-written controllers, but the principle
holds) to change, for a refactor that had nothing to do with the caching requirement
actually being implemented. Kept the existing exception-throwing contract from Scenario
1 and layered caching underneath it without touching its shape.

### Validation

- Cache: exercised indirectly through `UrlServiceImplTest#resolve_cacheHit...` and
  `resolve_cacheMiss...`, which assert the repository is *not* touched on a hit and *is*
  touched (and the cache subsequently populated) on a miss — validating the cache-aside
  contract, not just "a cache exists."
- Rate limiting: `RateLimitIntegrationTest` deliberately overrides the bucket to
  capacity 2 via `@TestPropertySource` so the 429 path is deterministic and fast rather
  than trying to exhaust the real 20/min production limit in a test.
- Async analytics: `UrlShortenerIntegrationTest` uses Awaitility to poll-assert the
  click landed, proving both that recording works *and* that the redirect response
  itself didn't wait for it (the test asserts the 302 first, independently, before
  polling for the analytics side effect).

---

## Scenario 3 — Ambiguous requirement: "handle link expiration and deletion"

### Requirement understanding

The source brief mentions "reliability features" but nowhere specifies what should
happen to a link that's no longer wanted. This is a genuinely underspecified
requirement with several defensible interpretations, each with different consequences.
Rather than pick one silently, the ambiguity was made explicit and resolved with stated
rationale:

- **Hard delete vs. soft delete?** Interpreted as: soft delete. Rationale made explicit
  in `ARCHITECTURE.md` — a short code must never be silently reissued to point somewhere
  else after it's been shared; hard-deleting and later allowing the same code to be
  regenerated would violate that.
- **What HTTP status for "used to exist, now doesn't"?** Interpreted as: `410 Gone`,
  distinct from `404 Not Found` for "never existed." This is not what a minimal
  implementation would produce by default (returning 404 for both is the path of least
  resistance) — it was a deliberate choice because the two cases carry different
  operational meaning (410 is cacheable/monitorable as "expected, permanent," 404 as
  "possible bug or bad link").
- **Does expiration need a background job, or is a check-on-read enough?** Interpreted
  as: both, for different reasons. Check-on-read (`UrlService.resolve`) is what makes
  expiration *correct* — it can never be stale, because it's evaluated at the moment of
  use. The background sweep (`ExpiredLinkSweeper`) exists purely for the secondary
  concern that an expired-but-never-clicked-again link would otherwise sit
  "active" in list views forever. Building only the sweep and skipping the read-time
  check would have been the more obviously "reliability feature"-shaped answer, but it
  would be *wrong* — correctness can't depend on a scheduler's timing.

### Task decomposition

1. Add `expiresAt` (nullable) to `UrlMapping`, and `ttlSeconds` (optional) to the create
   request — no dependencies.
2. `isExpired` / `isActive` / `isResolvable` predicates on the entity — depends on (1).
3. `UrlGoneException`, distinct from `UrlNotFoundException` — no dependencies, but must
   exist before (4).
4. `UrlService.resolve` lazy-expiry check (deactivate + evict + throw) — depends on (2), (3), and on the cache work from Scenario 2 (needs `UrlCache.evict`).
5. `UrlService.deleteUrl` (soft delete) — depends on (2), and on cache eviction.
6. `GlobalExceptionHandler` mapping for `UrlGoneException` → 410 — depends on (3).
7. `ExpiredLinkSweeper` background job — depends on (2); explicitly the *last* piece built, because it's additive hygiene on top of an already-correct system, not a correctness dependency.

### AI-assisted execution

This is the scenario where disciplined prompting mattered most, precisely because the
requirement was underspecified — a vague prompt here would have gotten a vague (and
probably wrong-by-default) answer. The generation prompt for step 4 stated the
constraint explicitly: *"expiration must be correct at read time regardless of whether
any background job has run; a background sweep, if added, is an optimization, not the
mechanism."* That constraint is *why* `resolve()` does its own deactivate-and-evict
inline instead of just checking a boolean flag that some other job maintains.

An AI-generated first draft of step 6 mapped `UrlGoneException` to `404`, on the (not
unreasonable) theory that "the client experience is the same either way — the link
doesn't work." Rejected: collapsing 404 and 410 throws away information a real client or
monitoring system would want (a spike in 410s means "links are expiring as designed"; a
spike in 404s means "someone's generating or sharing bad links, worth investigating").
Kept them distinct.

### Validation

- `UrlServiceImplTest#resolve_expiredMapping_throwsUrlGoneException_andDeactivatesAndEvicts`
  and `#resolve_deletedMapping_throwsUrlGoneException` assert the distinct-exception
  behavior directly, plus the side effects (deactivation, cache eviction) that make
  correctness not depend on the sweep.
- `UrlShortenerIntegrationTest#createWithShortLivedTtl_expiresAndGoesAway` is an
  end-to-end proof that a 1-second-TTL link redirects successfully immediately after
  creation and returns 410 once expired — through the real HTTP layer, not a mock —
  specifically so the "correct at read time" claim is validated the way a client would
  actually experience it, not just at the unit level.
- The 410-vs-404 distinction itself is asserted in both the unit test message
  assertions and the integration test's status-code assertions
  (`redirect_unknownShortCode_returns404` alongside the TTL test's `isGone()`), so the
  two codes can't silently collapse back into each other in a future change without a
  test failing.
