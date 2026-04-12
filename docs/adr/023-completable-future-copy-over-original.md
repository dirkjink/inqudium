# ADR-023: Always return the decorated copy, never the original CompletableFuture

**Status:** Accepted  
**Date:** 2026-04-10  
**Deciders:** Core team  
**Related:** ADR-010 (TimeLimiter / CompletionStage semantics), ADR-022 (Call identity propagation)

## Context

Every resilience element in Inqudium that operates on asynchronous code — most notably the Time Limiter (ADR-010), but
also async variants of Circuit Breaker, Retry, and Bulkhead — must decorate a `CompletableFuture` (or
`CompletionStage`). Decoration means registering callbacks such as `thenApply`, `handle`, `whenComplete`, or
`exceptionally` on an existing future to inject resilience behavior (timeout enforcement, fallback values, error
counting, metric emission).

Every one of these registration methods returns a **new** `CompletableFuture` instance — a copy — while leaving the
original instance unchanged. This creates an ambiguity at the API boundary: should the element return the original
future
(on which the callbacks were registered) or the copy (which was produced by the registration)?

This decision affects correctness, composability, and failure isolation across the entire library. Getting it wrong
introduces subtle bugs that surface only under error conditions or in multi-element pipelines — precisely the scenarios
resilience elements are designed to handle.

### The two-object problem

When a callback is registered on a `CompletableFuture`, two distinct objects exist afterward:

```java
CompletableFuture<Integer> original = new CompletableFuture<>();
CompletableFuture<Integer> copy = original.thenApply(value -> value * 2);

// original != copy  (always a different object)
```

These two objects differ in critical ways depending on the registration method and the completion outcome:

**Value-transforming methods (`thenApply`, `handle`):** The original retains its raw value. The copy carries the
transformed value. If the original completes with `5` and the callback doubles it, the original yields `5` and the copy
yields `10`. The transformation exists *only* on the copy.

**Observation methods (`whenComplete`):** Under normal conditions both objects carry the same value — `whenComplete`
does
not transform. However, if the callback itself throws an exception, the copy becomes exceptionally completed while the
original remains successful. The original is immune to bugs in the observation code; the copy is not.

**Error recovery methods (`exceptionally`, `handle`):** When the original completes exceptionally, recovery logic
(fallback values, default responses) can heal the copy, making it complete successfully. The original stays permanently
exceptional. Recovery exists *only* on the copy.

**Terminal methods (`thenAccept`, `thenRun`):** The copy is `CompletableFuture<Void>` and completes with `null`. The
original retains its value. The copy is useful only as a completion signal.

### Branching, not chaining

Multiple callbacks registered on the **same** future create independent branches, not a sequential chain:

```java
CompletableFuture<Integer> original = new CompletableFuture<>();
CompletableFuture<Integer> branchA = original.thenApply(v -> v + 10);  // branch A
CompletableFuture<Integer> branchB = original.thenApply(v -> v * 10);  // branch B

original.complete(5);
// branchA = 15, branchB = 50 — independent, unaware of each other
```

This means that if an element registers an internal callback (e.g., for metrics) and a separate callback (e.g., for
fallback logic) on the same future, these two callbacks produce two independent copies. Which one is returned to the
caller determines what the caller sees.

### The one-way dependency

The dependency between original and copy flows in one direction only:

- Completing the original triggers callbacks on all copies.
- Completing or cancelling a copy has **no effect** on the original.
- Once the original is completed, a second `complete()` call is silently ignored — the one-shot semantics are permanent.

This asymmetry is relevant for timeout enforcement: a Time Limiter that calls `copy.completeExceptionally(new
TimeoutException())` does not interfere with the original, which may still be waiting for the upstream result.

### Timing is irrelevant

Whether a callback is registered before or after the future completes does not matter.
`CompletableFuture` stores its terminal state. A callback registered on an already-completed future fires immediately.
This guarantees that the two-object problem exists regardless of execution timing.

### Empirical verification

The behaviors described above were verified through a comprehensive test suite (`CompletableFutureSideEffectTest`) that
exercises each callback method, each completion mode (success, exception, cancellation), the branching model, the
one-way dependency, the timing invariance, and the object identity guarantee. This test suite serves as the living
specification for the assumptions this ADR relies on. If a future JDK release changes any of these behaviors, the tests
will fail and this decision must be re-evaluated.

## Decision

**Every resilience element that decorates a `CompletableFuture` must return the copy produced by the callback
registration, never the original.**

Concretely, when an element registers its resilience logic:

```java
// Inside TimeLimiter.decorateFuture(...)
CompletableFuture<T> original = futureSupplier.get();

CompletableFuture<T> copy = original.handle((value, error) -> {
    // timeout enforcement, fallback, metric emission
    ...
});

return copy;  // ✅ ALWAYS return the copy
// return original;  // ❌ NEVER return the original
```

When an element needs to register multiple callbacks (e.g., one for observation, one for transformation), it must
**chain** them rather than branch them:

```java
// ✅ Correct: chain produces a single path
CompletableFuture<T> afterMetrics = original.whenComplete((v, e) -> metrics.record(e));
CompletableFuture<T> afterFallback = afterMetrics.handle((v, e) -> e != null ? fallback : v);
return afterFallback;  // caller sees both metrics and fallback

// ❌ Wrong: branching loses the metrics callback
CompletableFuture<T> metricsBranch = original.whenComplete((v, e) -> metrics.record(e));
CompletableFuture<T> fallbackBranch = original.handle((v, e) -> e != null ? fallback : v);
return fallbackBranch;  // caller sees fallback but metrics run on a detached branch
```

### Pipeline composition

When multiple elements are composed in a pipeline, each element receives the copy produced by the previous element and
returns its own copy. The pipeline returns the outermost copy to the caller:

```
supplier.get() → original
  → TimeLimiter.handle(...)      → copy₁  (timeout enforcement)
    → CircuitBreaker.handle(...) → copy₂  (state machine + fallback)
      → Retry.handle(...)        → copy₃  (retry logic)
```

The caller receives `copy₃`, which carries the cumulative resilience decoration of all three elements. The original and
all intermediate copies are internal implementation details.

### Standalone usage

When an element is used outside a pipeline, the same rule applies. The element decorates the future and returns the
copy. The caller never sees the original:

```java
CompletableFuture<String> result = timeLimiter.decorateFuture(() -> asyncService.call());
// result is the copy — timeout-decorated, ready to use
```

## Consequences

**Positive:**

- **Recovery is visible.** Fallback values, timeout exceptions, and error transformations exist only on the copy. By
  returning the copy, the caller actually receives the resilience behavior they configured. Returning the original would
  make all recovery invisible — the element would register logic that no one ever sees.
- **Composition works.** Each element in a pipeline builds on the output of the previous element. Because every element
  returns its copy, transformations stack naturally. If any element returned the original, it would bypass all upstream
  decorations, breaking the pipeline contract.
- **Timeout enforcement is clean.** A Time Limiter can call `copy.completeExceptionally(new TimeoutException())` on its
  copy without affecting the original. The upstream producer continues undisturbed. If the library returned the
  original,
  the Time Limiter would have to force-complete the original, permanently corrupting it and racing with the producer.
- **Caller manipulation is contained.** The one-way dependency means a caller who calls `complete()` or `cancel()` on
  the copy cannot corrupt the original or the library's internal state. The copy acts as a buffer. If the library
  returned the original, a caller's `complete()` would consume the one-shot, preventing the library from ever setting a
  value or exception on it again.
- **Observation failures are explicit.** If a metrics callback throws an exception inside `whenComplete`, the copy
  becomes exceptionally completed — an explicit, debuggable failure. If the library returned the original, the original
  would remain clean, and the broken metrics would silently disappear without any signal to the caller.
- **Consistent with `InqCall` propagation (ADR-022).** The `InqCall` abstraction wraps the supplier; in async variants,
  the copy is the natural carrier of the decorated `InqCall.callId()`. Returning the original would sever the call
  identity chain.

**Negative:**

- Each decoration step allocates one additional `CompletableFuture` object. In a three-element pipeline, three copies
  exist beyond the original. JMH benchmarks confirm this overhead is negligible — these are short-lived objects that the
  JIT's escape analysis handles efficiently — but it is non-zero.
- Developers authoring custom elements must understand the two-object model. Accidentally returning the original instead
  of the copy will silently break resilience behavior without any compile-time or runtime error. Code review and the
  verification test suite are the primary safeguards.

**Neutral:**

- The original future still exists and its callbacks still fire. Internal observation callbacks (metrics, event
  emission)
  can be registered as branches on the original or as chain links — the decision is left to each element's
  implementation, as long as the *returned* future is always the end of the chain.

## Scope: CompletableFuture only

This ADR applies exclusively to `CompletableFuture` / `CompletionStage`. Project Reactor's `Mono` and `Flux` do not
exhibit the two-object problem. A `Mono` is an assembly-time description of a pipeline — operators like `map`,
`onErrorResume`, or `doOnNext` return a new `Mono` that wraps the previous one, but no execution happens until a
subscriber subscribes. There is no mutable shared state, no one-shot completion, and no branching ambiguity. Each
subscription triggers a fresh execution through the entire operator chain. The "original vs. copy" distinction that
drives this ADR does not exist in the reactive model, and the Reactor-based element implementations do not need to
follow
this rule.
