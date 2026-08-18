# AI-Assisted Execution: Traceability, Quality Gates, and Oversight

This document is the evidence trail for the assignment's "critical differentiator"
requirement: not just that AI was used, but *how* — with intent/constraints defined per
task, disciplined prompting, traceability of what was generated vs. edited vs. rejected
(with rationale), quality gates, secure usage, and explicit engineer sign-off. Where
`docs/SCENARIOS.md` narrates the engineering story, this document is the flatter log
underneath it.

## 1. How tasks were framed for AI-assisted generation

Every non-trivial component was generated against an explicit brief, not a bare
one-line prompt, and every brief followed the same shape: **intent** (what this
component is for), **constraints** (what it must and must not do), **acceptance
criteria** (how correctness will be checked), and **technical context** (what it plugs
into). Representative example, reconstructed for `TokenBucketRateLimiter`:

- *Intent:* per-client rate limiting for two distinct endpoint classes with different traffic profiles.
- *Constraints:* no external dependency for this; must expose a way to estimate retry-after for a 429 response; must not require locking that could contend under high concurrency.
- *Acceptance criteria:* allows exactly `capacity` requests immediately, rejects the next one, refills over time, tracked independently per key.
- *Technical context:* consumed by a `HandlerInterceptor` registered per-path in `WebConfig`, configured via `AppProperties`.

`TokenBucketRateLimiterTest` was written directly against those acceptance criteria —
the "allows up to capacity then rejects," "tracks keys independently," and "refills over
time" tests map 1:1 to the bullets above.

## 2. Generated / edited / rejected log

| Component | Generated | Edited | Rejected (and why) |
|---|---|---|---|
| Short-code strategy | Random Base62 + bounded collision retry | — | A sequential-counter-to-Base62 approach was proposed first: collision-free by construction, but makes every link enumerable (`/1`, `/2`, ...), a real security/privacy issue for a public shortener. Replaced before implementation, not after. |
| `UrlMapping` entity | Initial draft included a self-contained `AtomicLong clickCount` field with `incrementClickCount()` | Removed entirely | **Real bug caught in review, not in testing:** nothing in the codebase ever called `incrementClickCount()` — click recording only ever wrote to the separate `ClickEventRepository`. The field would have silently reported `0` forever. Caught by re-reading the entity against every call site rather than trusting the field existed for a reason. Fixed by deleting the duplicate counter and making `ClickEventRepository` the single source of truth (`AnalyticsService.countClicks`), consumed by `UrlController` when building responses. This is the single most important trace entry in this log: it's a class of bug (two independently-maintained representations of one fact, one of them dead) that a compiler cannot catch and that only shows up in testing if someone specifically asserts on click counts after real activity — which is exactly what happened here. |
| `WebConfig` bean wiring | Constructor-injected two `TokenBucketRateLimiter` beans by type/parameter name alone | Added explicit `@Qualifier` on both parameters | Two beans of the same type resolved via parameter-name matching depends on the `-parameters` javac flag being enabled — true for most Spring Boot parent POMs, but *depending* on that rather than being explicit is fragile and the failure mode (an ambiguous-bean-definition `BeanCreationException` at startup) is exactly the kind of thing that should never reach a reviewer to discover. Made deterministic instead of implicitly-correct. |
| `GlobalExceptionHandler` — `UrlGoneException` mapping | First pass mapped to `404 Not Found` | Changed to `410 Gone` | Collapses two operationally distinct situations ("never existed" vs. "existed, now intentionally gone") into one signal, which throws away information a monitoring system or API consumer would want. See `SCENARIOS.md` §3 for the full rationale. |
| Cache layer | Caffeine-backed `UrlCache`, TTL + bounded size | — | A real Redis-via-Testcontainers setup was considered and explicitly rejected for this prototype's scope, per the requester's own stated preference for a self-contained, zero-infrastructure simulation — recorded here as a scope decision made *with* the requester, not unilaterally. |
| Rate-limiting library choice | Hand-rolled `TokenBucketRateLimiter` | — | Bucket4j (a well-known library) was considered; rejected to avoid pulling in an external dependency for logic that's ~80 lines, fully unit-testable, and needs an exact (not approximate) retry-after estimate tailored to this codebase's response format. Documented as a deliberate build-vs-buy call, not an oversight. |
| Click-event storage | `LongAdder`-based incremental aggregates + bounded (200-entry) recent-events window per short code | — | A naive unbounded per-event list, rescanned on every analytics read, was the "obvious" first shape; rejected before writing it because it's an unbounded-memory liability under a viral link and an O(n)-per-read cost. Built the bounded version directly — see `ARCHITECTURE.md` §3. |
| IP address handling in analytics | SHA-256 hash (truncated), not raw IP, stored per click event | — | Raw IP storage was never actually written, but was flagged as a decision point during design: recording raw client IPs in an analytics log that's meant to answer "how many clicks / from where" doesn't need the *raw* IP, only enough to support coarse dedup/abuse signals — hashing avoids retaining PII with no corresponding requirement to justify it. |

## 3. Quality gates applied

- **Input validation:** Jakarta Bean Validation annotations on every request DTO
  (`CreateUrlRequest`), plus semantic validation beyond what annotations can express
  (URL scheme allowlisting, host presence) in `UrlServiceImpl`, because "is this a
  syntactically valid, safe-to-redirect-to URL" needs real URI parsing, not a regex.
- **Error handling:** a single `GlobalExceptionHandler` maps every domain exception to a
  deliberately-chosen HTTP status (see the table in `ARCHITECTURE.md` §4 and the 410
  entry above) — no endpoint improvises its own error shape.
- **Security review pass:** explicit check for the two most likely vulnerability classes
  in a redirect service — open redirect (mitigated: `http`/`https`-only scheme
  allowlist) and unbounded resource consumption (mitigated: bounded rate-limit buckets,
  bounded thread pool with `CallerRunsPolicy`, bounded per-short-code event history,
  bounded cache size). PII handling reviewed for the analytics path (IP hashing, above).
- **Concurrency review:** every shared mutable structure was checked for thread-safety
  under concurrent redirects — `ConcurrentHashMap`-backed repositories, `LongAdder` for
  hot counters, CAS-loop-based `TokenBucketRateLimiter`, `volatile boolean active` on
  the entity (single-writer-visibility is sufficient there; it's a monotonic
  true→false flip, never contended for a meaningful ordering guarantee beyond
  visibility).
- **Test coverage as a gate, not an afterthought:** every service-layer branch identified
  during design (success, not-found, gone/expired, gone/deleted, conflict, validation
  failure) has a corresponding test asserted *before* being considered done — visible in
  the 1:1 mapping between `UrlServiceImpl`'s exception types and
  `UrlServiceImplTest`'s test method names.
- **Manual compile/API-correctness review in place of a compiler:** this session's
  sandbox could not reach Maven Central (verified directly, not assumed — see
  `README.md` and `ENGINEERING_SUMMARY.md` for the exact evidence). In its absence,
  every file was read back in full at least once against known Spring Boot 3.3.x /
  Jakarta EE 9 API signatures specifically looking for signature mismatches, missing
  imports, and interface/implementation drift — the process that caught the
  `WebConfig` qualifier issue above. This is explicitly named as a *compensating*
  control, not treated as equivalent to an actual green build — the real gate is `mvn
  clean install` on a normal machine, which is called out as the first thing to run.

## 4. Secure AI usage

No secrets, credentials, API keys, or environment-specific values are present anywhere
in the generated code or configuration. No component makes an outbound network call
(the entire prototype is self-contained by design — see `ARCHITECTURE.md`). No
generated code was accepted that would execute user-supplied input (the URL-scheme
allowlist exists specifically to prevent this service from becoming a vector for
`javascript:`/`data:` payload delivery). All user-controllable strings that reach a
response (referrer, user agent) are stored and returned as data, never interpolated
into any executed context.

## 5. Human sign-off and ownership

Architecture-level decisions with real trade-offs — language/framework, persistence
strategy, and packaging — were confirmed with the requester before implementation began
rather than assumed (Java/Spring Boot; in-memory store with a simulated Redis cache
layer; runnable code + Markdown docs as the deliverable shape). Every entry in the
generated/edited/rejected log above reflects a decision an engineer made and can defend,
not an unreviewed AI output accepted as-is. The engineer (this session, acting on the
requester's explicit choices) owns correctness, maintainability, and the honest framing
of what is and isn't verified — which is why the build-verification limitation is
surfaced three times across this doc set (`README.md`, here, and
`ENGINEERING_SUMMARY.md`) rather than mentioned once and buried.
