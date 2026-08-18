# Final Engineering Summary

## Plan and rationale

The assignment asks for a prototype demonstrating an engineering *process*
(requirement understanding, decomposition, AI-assisted execution with traceability,
validation) more than it asks for a feature-complete product. The plan reflected that:
build a genuinely working core (create + redirect) first, prove it with tests, then
layer reliability features on top as an explicit brownfield exercise against that
working core, treating each addition as its own decomposed, validated unit of work
rather than one undifferentiated build. `docs/SCENARIOS.md` is the detailed record of
that plan as executed; this document is the roll-up.

Three scope decisions were made explicitly with the requester before writing code,
rather than assumed, because each has real downstream consequences: **Java/Spring
Boot** as the stack, **in-memory storage with a simulated Redis-style cache layer**
(no external infrastructure required to run this), and **a runnable code repository
plus Markdown documentation** as the deliverable shape. Every architectural decision
downstream of those three is traceable back to them.

## Artifacts produced

- A working Spring Boot service (44 Java files: 26 main, 7 test classes covering
  unit and integration scope) implementing create, redirect, metadata lookup,
  analytics, soft-delete, pagination, cache-stats observability, and a health endpoint.
- `README.md` — setup, configuration reference, full API reference with examples, testing instructions.
- `docs/ARCHITECTURE.md` — components, control flow, and a trade-off table for every non-obvious design decision.
- `docs/SCENARIOS.md` — three worked scenarios (greenfield, brownfield, ambiguous requirement) each showing decomposition, AI-assisted execution with specific accept/reject calls, and validation.
- `docs/AI_TRACEABILITY.md` — the generated/edited/rejected log and quality-gate record this document summarizes.
- This document.

## Risks, trade-offs, and validation performed

The full trade-off table lives in `ARCHITECTURE.md` §4; the headline risks and how each
was validated or mitigated:

**No durable persistence.** The in-memory store loses all data on restart. Mitigated
architecturally (isolated behind `UrlRepository`, so a real database is a contained
swap, not a rewrite) rather than mitigated in this build, because building a real
persistence layer was out of scope for the agreed prototype shape. This is the single
largest gap between this prototype and something deployable as-is, and it's named as
such rather than left implicit.

**Single-instance cache and rate limiter.** Both are correct for a single JVM and
become approximations (bounded-staleness cache, per-instance rate limits) the moment
this runs as more than one instance. Documented explicitly in `ARCHITECTURE.md` with
the specific production fix for each (shared Redis, shared rate-limit store) rather than
presented as if the current implementation already handles horizontal scale.

**No authentication or authorization.** Anyone can create, inspect, list, or delete any
link. There is no user/ownership model at all. This wasn't an oversight to be
discovered later — it's an explicit scope boundary, named here because a reviewer
should not have to infer it.

**Click-count consistency bug, caught in review.** Documented in full in
`AI_TRACEABILITY.md`: an entity-level click counter was generated, never wired up, and
would have silently reported zero forever had it shipped. Caught by manual review before
any test was even written against it, then fixed by consolidating on a single source of
truth. Kept in this summary because it's the clearest evidence in this whole exercise of
the "engineer owns correctness" principle actually operating, not just stated.

**Build verification gap.** The session this prototype was built in could not reach
Maven Central — confirmed directly (`curl` to `repo.maven.apache.org` and two mirror
candidates all returned `403`/connection-refused through the sandbox's network policy),
not inferred from a failed `mvn` run alone. Consequently `mvn compile` / `mvn test` were
never observed to pass by this process. The compensating control was a full manual
line-by-line review of every file against known Spring Boot 3.3.x / Jakarta EE 9 API
shapes, which is how the `WebConfig` bean-qualifier ambiguity (§2 of
`AI_TRACEABILITY.md`) was caught. This compensating control is explicitly **not**
presented as equivalent to a green build. The honest status is: *architecturally sound
and manually verified for API correctness; not yet compiler-verified.* Running `mvn
clean install` on a normal internet-connected machine is the immediate next step, and
should be the first thing done with this repository — not treated as a formality.

**Testing scope.** Unit tests isolate service/utility logic with mocked collaborators;
integration tests drive the real HTTP layer end-to-end including the async analytics
path (via Awaitility) and the rate-limit 429 path (via a property-overridden tiny
bucket). Not covered, and named as a gap rather than silently absent: load/concurrency
testing (no test drives genuinely concurrent requests against the collision-retry or
rate-limiter logic under contention — both were designed for thread-safety and reasoned
about in `AI_TRACEABILITY.md` §3, but reasoning about thread-safety is not the same
evidence as a concurrency test proving it), and multi-instance behavior (impossible to
test meaningfully against a single-instance in-memory design in the first place).

## Assumptions

- A short code, once issued, should never be reassigned to a different destination —
  this drove the soft-delete design and is stated as an assumption because the source
  brief doesn't specify it.
- Analytics consumers can tolerate eventual consistency (a redirect's click may take up
  to the analytics executor's processing delay — typically milliseconds, bounded by the
  thread pool — to appear in a subsequent analytics read); this was accepted in exchange
  for never blocking a redirect on analytics recording.
- "Reliability features" was interpreted as: caching, rate limiting, graceful
  degradation under load, and correct expiration/deletion semantics — not as
  high-availability infrastructure concerns (replication, failover), which are out of
  scope for a single-process prototype by construction.
- The prototype is expected to run as a single instance for demonstration purposes;
  every "known limitation" entry in `ARCHITECTURE.md` follows from this assumption being
  made explicit rather than left to guesswork.

## Limitations (condensed)

No durable storage; single-instance cache and rate limiter; no auth/ownership model; no
load/concurrency test evidence; not compiler-verified inside the authoring sandbox
(compensated by manual review, not equivalent to it). Each is discussed with its
specific rationale and mitigation path above and in `ARCHITECTURE.md` §5 ("What a
production evolution would add"), rather than repeated here without context.
