# ADR-023: Always return the decorated copy, never the original CompletionStage

**Status:** Accepted  
**Date:** 2026-04-10  
**Last updated:** 2026-04-22  
**Deciders:** Core team  
**Related:** ADR-010 (TimeLimiter / CompletionStage semantics), ADR-022 (Call identity propagation)

## Context

Every resilience element in Inqudium that operates on asynchronous code — most notably the Time Limiter (ADR-010), but
also async variants of Circuit Breaker, Retry, and Bulkhead — must decorate a `CompletionStage` (concretely a
`CompletableFuture`, which is the implementation type the JDK hands back from `supplyAsync`, `toCompletableFuture()`,
etc.). Decoration means registering callbacks such as `thenApply`, `handle`, `whenComplete`, or `exceptionally` on an
existing stage to inject resilience behavior (timeout enforcement, fallback values, error counting, metric emission).

Every one of these registration methods returns a **new** `CompletionStage` instance — a copy — while leaving the
original instance unchanged. This creates an ambiguity at the API boundary: should the element return the original
stage (on which the callbacks were registered) or the copy (which was produced by the registration)?

This decision affects correctness, composability, and failure isolation across the entire library. Getting it wrong
introduces subtle bugs that surface only under error conditions or in multi-element pipelines — precisely the scenarios
resilience elements are designed to handle.

### The two-object problem

When a callback is registered on a `CompletionStage`, two distinct objects exist afterward:

```java
CompletableFuture<Integer> original = new CompletableFuture<>();
CompletionStage<Integer>   copy     = original.thenApply(value -> value * 2);

// original != copy  (always a different object)
```

These two objects differ in critical ways depending on the registration method and the completion outcome:

**Value-transforming methods (`thenApply`, `handle`):** The original retains its raw value. The copy carries the
transformed value. If the original completes with `5` and the callback doubles it, the original yields `5` and the copy
yields `10`. The transformation exists *only* on the copy.

**Observation methods (`whenComplete`):** Under normal conditions both objects carry the same value — `whenComplete`
does not transform. However, if the callback itself throws an exception, the copy becomes exceptionally completed while
the original remains successful. The original is immune to bugs in the observation code; the copy is not.

**Error recovery methods (`exceptionally`, `handle`):** When the original completes exceptionally, recovery logic
(fallback values, default responses) can heal the copy, making it complete successfully. The original stays permanently
exceptional. Recovery exists *only* on the copy.

**Terminal methods (`thenAccept`, `thenRun`):** The copy is `CompletionStage<Void>` and completes with `null`. The
original retains its value. The copy is useful only as a completion signal.

### Branching, not chaining

Multiple callbacks registered on the **same** stage create independent branches, not a sequential chain:

```java
CompletableFuture<Integer> original = new CompletableFuture<>();
CompletionStage<Integer>   branchA  = original.thenApply(v -> v + 10);  // branch A
CompletionStage<Integer>   branchB  = original.thenApply(v -> v * 10);  // branch B

original.complete(5);
// branchA = 15, branchB = 50 — independent, unaware of each other
```

This means that if an element registers an internal callback (e.g., for metrics) and a separate callback (e.g., for
fallback logic) on the same stage, these two callbacks produce two independent copies. Which one is returned to the
caller determines what the caller sees.

### The one-way dependency

The dependency between original and copy flows in one direction only:

- Completing the original triggers callbacks on all copies.
- Completing or cancelling a copy has **no effect** on the original.
- Once the original is completed, a second `complete()` call is silently ignored — the one-shot semantics are permanent.

This asymmetry is relevant for timeout enforcement: a Time Limiter that interferes only with its own copy cannot
corrupt the original, which may still be waiting for the upstream result.

### Timing is irrelevant (for the slow path)

Whether a callback is registered before or after the stage completes does not matter for the two-object split.
`CompletableFuture` stores its terminal state; a callback registered on an already-completed stage fires immediately
and still produces a separate copy. The two-object problem exists regardless of execution timing.

Timing does matter for one optimisation: *if* the caller knows the stage is already done *before* deciding whether to
register a callback at all, it can skip the registration entirely and return the original without violating the rule.
See the fast-path exception below.

## Decision

**Every resilience element that decorates a `CompletionStage` must return the copy produced by the callback
registration, never the original.**

Concretely, when an async layer registers its resilience logic:

```java
// Inside an InqAsyncDecorator.executeAsync(...)
CompletionStage<R> original = next.executeAsync(chainId, callId, argument);

CompletionStage<R> copy = original.whenComplete((result, error) -> {
    // metric emission, permit release, outcome recording
    ...
});

return copy;      // ✅ ALWAYS return the copy
// return original;  // ❌ NEVER return the original
```

When an element needs to register multiple callbacks (e.g., one for observation, one for transformation), it must
**chain** them rather than branch them:

```java
// ✅ Correct: chain produces a single path
CompletionStage<R> afterMetrics  = original.whenComplete((v, e) -> metrics.record(e));
CompletionStage<R> afterFallback = afterMetrics.handle((v, e) -> e != null ? fallback : v);
return afterFallback;  // caller sees both metrics and fallback

// ❌ Wrong: branching loses the metrics callback
CompletionStage<R> metricsBranch  = original.whenComplete((v, e) -> metrics.record(e));
CompletionStage<R> fallbackBranch = original.handle((v, e) -> e != null ? fallback : v);
return fallbackBranch;  // caller sees fallback but metrics run on a detached branch
```

### Fast-path exception: already-completed stages

When the downstream stage is already completed at the moment the layer decides how to handle it, the layer may
skip the callback registration entirely and return the original. Because no callback is registered, no copy is
produced, and the two-object split does not occur — the rule is preserved, not violated.

This pattern is used by `ImperativeBulkhead.executeAsync(...)`:

```java
if (stage instanceof CompletableFuture<?> cf && cf.isDone()) {
    long rttNanos = nanoTimeSource.now() - startNanos;
    releaseAndReport(chainId, callId, rttNanos, completionError(cf));
    return stage;                                               // original, no callback
} else {
    return stage.whenComplete((result, error) -> {
        long rttNanos = nanoTimeSource.now() - startNanos;
        releaseAndReport(chainId, callId, rttNanos, error);
    });                                                         // copy, per the main rule
}
```

The fast path is an optimisation for sync-wrapped-as-async cases (validation failures, cached results, pre-completed
stubs): the release/observation work runs inline on the calling thread, which is cheaper than scheduling a callback
that will fire immediately anyway. The slow path — any stage that is still pending when the layer inspects it — must
follow the main rule and return the copy.

### Pipeline composition

When multiple async layers are composed in a pipeline, each layer receives the stage produced by the layer below it
(`next.executeAsync(chainId, callId, argument)`) and returns its own copy. The pipeline returns the outermost copy to
the caller:

```
terminal.executeAsync(...)               → original
  Retry.executeAsync(...)                → copy₁  (retry outcome recording)
    CircuitBreaker.executeAsync(...)     → copy₂  (state machine + success/failure recording)
      Bulkhead.executeAsync(...)         → copy₃  (permit release, RTT measurement)
```

The caller receives `copy₃`, which carries the cumulative resilience decoration of all three layers. The original and
all intermediate copies are internal implementation details.

### Standalone usage

When an element is used outside a pipeline, the same rule applies. The element decorates the stage and returns the
copy. The caller never sees the original:

```java
CompletableFuture<String> result =
        timeLimiter.executeCompletionStageAsync(() -> asyncService.call(), timeout);
// result is the copy — timeout-decorated, ready to use
```

The concrete decoration inside `CompletableFutureAsyncExecutor.attachTimeoutAndEvents(...)` composes two steps:
`orTimeout(...)` (which mutates the CF in place to schedule a deadline) followed by `handle((result, ex) -> ...)`,
which produces the copy that transforms `TimeoutException` into `TimeLimiterException` and emits the completion
event. The returned future is the `handle` copy.

### Call identity: orthogonal, not carried on the copy

ADR-022 propagates call identity as two primitive `long` parameters
(`chainId`, `callId`) on every layer invocation:

```java
CompletionStage<R> executeAsync(long chainId, long callId, A argument,
                                InternalAsyncExecutor<A, R> next);
```

The IDs flow as method arguments through the async stack, independent of which `CompletionStage` instance is
returned. The copy does not „carry" the call identity — the identity lives in the parameter positions, not in the
stage object. Returning the original vs. the copy is orthogonal to correlation: either one would observe the same
`chainId` / `callId` for a given invocation, because those values are already bound in the lambda closures that
register the callbacks.

The interaction point is therefore weaker than it might appear: ADR-022 is unaffected by this ADR's decision, and
this ADR is unaffected by ADR-022's decision. The two are independently motivated.

## Consequences

**Positive:**

- **Recovery is visible.** Fallback values, timeout exceptions, and error transformations exist only on the copy. By
  returning the copy, the caller actually receives the resilience behavior they configured. Returning the original
  would make all recovery invisible — the element would register logic that no one ever sees.
- **Composition works.** Each layer in a pipeline builds on the output of the layer below it. Because every layer
  returns its copy, transformations stack naturally. If any layer returned the original, it would bypass all upstream
  decorations, breaking the pipeline contract.
- **Timeout enforcement is clean.** A Time Limiter decorates with `orTimeout(...)` (deadline) followed by
  `handle(...)` (exception translation) and returns the `handle` copy. The upstream producer continues undisturbed on
  the original; the deadline and the translation live on the copy. No force-completion of the original, no race with
  the producer.
- **Caller manipulation is contained.** The one-way dependency means a caller who calls `complete()` or `cancel()` on
  the copy cannot corrupt the original or the library's internal state. The copy acts as a buffer. If the library
  returned the original, a caller's `complete()` would consume the one-shot, preventing the library from ever setting
  a value or exception on it again.
- **Observation failures are explicit.** If a metrics or permit-release callback throws inside `whenComplete`, the
  copy becomes exceptionally completed — an explicit, debuggable failure surfaced on the caller's future. If the
  library returned the original, the original would remain clean and the broken callback would silently disappear on
  a detached branch.

**Negative:**

- Each decoration step allocates one additional `CompletionStage` object. In a three-layer pipeline, three copies
  exist beyond the original. JMH benchmarks confirm this overhead is negligible — these are short-lived objects that
  the JIT's escape analysis handles efficiently — but it is non-zero. The fast-path exception above avoids the
  allocation for already-completed stages.
- Developers authoring custom layers must understand the two-object model. Accidentally returning the original instead
  of the copy will silently break resilience behavior without any compile-time or runtime error. Code review and
  targeted unit tests (which assert that observation-callback failures surface on the returned future) are the
  primary safeguards.

**Neutral:**

- The original stage still exists and its callbacks still fire. Internal observation callbacks (metrics, event
  emission) can be registered as branches on the original or as chain links — the decision is left to each layer's
  implementation, as long as the *returned* stage is always the end of the chain (or, under the fast-path exception,
  is the original because no callback was registered at all).

## Scope: CompletionStage only

This ADR applies exclusively to `CompletionStage` / `CompletableFuture`. Project Reactor's `Mono` and `Flux` do not
exhibit the two-object problem. A `Mono` is an assembly-time description of a pipeline — operators like `map`,
`onErrorResume`, or `doOnNext` return a new `Mono` that wraps the previous one, but no execution happens until a
subscriber subscribes. There is no mutable shared state, no one-shot completion, and no branching ambiguity. Each
subscription triggers a fresh execution through the entire operator chain. The „original vs. copy" distinction that
drives this ADR does not exist in the reactive model, and any future Reactor-based layer implementations do not need
to follow this rule.
