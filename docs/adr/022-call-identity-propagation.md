# ADR-022: Call identity propagation

**Status:** Accepted  
**Date:** 2026-03-31  
**Last updated:** 2026-04-22  
**Deciders:** Core team  
**Supersedes:**
- Initial ThreadLocal-based `InqCallContext` (removed before first release)
- Initial string-based `InqCall<T>` record design (superseded by the primitive
  `long chainId` + `long callId` model described below)

## Context

Every call through an Inqudium pipeline passes through multiple resilience
elements — a Circuit Breaker, a Retry, a Rate Limiter, a Bulkhead, a Time
Limiter — in sequence. For observability (ADR-003), all events and exceptions
emitted during a single call must share the same identity, so that filtering
by that identity in Kibana, Grafana, or JFR reconstructs the complete lifecycle
of one request across all elements.

### The problem

In the original implementation, each element generated its own correlation ID
independently via `UUID.randomUUID().toString()`. When three elements were
composed in a pipeline, three different IDs appeared on the events, breaking
the correlation promise of ADR-003. There was no mechanism to propagate a
single identity through the decoration chain.

### Requirements

1. **Shared identity in pipelines.** All elements in a pipeline must see the
   same identity for a given invocation.
2. **Independent identity standalone.** When an element is used outside a
   pipeline (e.g. via the one-shot executors), it generates its own identity.
3. **Hot-path allocation-free.** The mechanism must not allocate wrapper
   objects, box primitives, or serialize strings on the per-call path. The
   pipeline is expected to be used under high concurrency, and the identity
   plumbing must not show up in JMH profiles.
4. **No thread affinity.** The mechanism must be compatible with virtual
   threads (ADR-008) and, later, with reactive / coroutine paradigms — no
   `ThreadLocal`, no thread-pinning state.
5. **Testable.** Identity values must be predictable in tests; no random
   UUIDs that make event assertions fragile.

### Rejected solution 1: ThreadLocal context

A `ThreadLocal<String> callIdContext` was rejected early because it fails in
reactive streams and coroutines, breaks across async hand-offs unless
explicitly captured, and introduces hidden coupling outside the method
signature.

### Rejected solution 2: an `InqCall<T>` record carrying a `String callId`

An intermediate design introduced an `InqCall<T>` record that wrapped the
`Supplier<T>` (or `Runnable`, etc.) together with a UUID-based `callId`
string, and changed decorators from `Supplier<T> → Supplier<T>` to
`InqCall<T> → InqCall<T>`. That design is the one historically described by
earlier revisions of this ADR.

It was rejected for two reasons:

1. **Per-layer allocation.** Every decorator layer returned a fresh `InqCall`
   instance carrying a capture-rich `Supplier<T>` lambda. At four or five
   layers per pipeline and tens of millions of calls per second under load,
   the allocation pressure and escape-analysis risk became measurable in
   benchmarks.
2. **Unnecessary indirection for the correlation field itself.** The sole
   purpose of the record was to carry a `String` identifier plus the
   delegate — but a `String` UUID is itself a heap object, and the record
   added a second layer of indirection on top. For the observability fields
   that actually leave the JVM (`InqEvent.callId`, `InqException.callId`),
   the relevant concern is a stable correlation key that downstream tooling
   can filter on. That key can be a `long` just as well as a string — and a
   `long` costs zero allocations to produce, propagate, and format.

## Decision

Identity is carried through the pipeline as **two primitive `long` values**
passed as explicit parameters on every chain step:

- `long chainId` — identifies the wrapper chain or resolved pipeline. All
  layers in the same chain observe the same value. Stable for the lifetime
  of the chain; the chain itself may be invoked many times.
- `long callId` — identifies one specific invocation through that chain.
  Monotonically increasing within its chain, generated once per invocation
  by the outermost layer (or the terminal), and then passed through every
  subsequent layer unchanged.

The authoritative types are:

```java
// eu.inqudium.core.pipeline.InternalExecutor
public interface InternalExecutor<A, R> {
    R execute(long chainId, long callId, A argument);
}

// eu.inqudium.core.pipeline.LayerAction
@FunctionalInterface
public interface LayerAction<A, R> {
    R execute(long chainId, long callId, A argument, InternalExecutor<A, R> next);
}
```

Decorators implement `LayerAction<A, R>` (through `InqDecorator<A, R>`), and
each layer simply forwards the two IDs to the next step:

```java
@Override
public R execute(long chainId, long callId, A argument,
                 InternalExecutor<A, R> next) {
    // pre-advice: use chainId/callId for events, exceptions, logging
    return next.execute(chainId, callId, argument);
    // post-advice
}
```

There is no `InqCall` record, no `InqCallIdGenerator`, and no wrapper object
per layer — only two primitive parameters flowing down the stack.

### Where the IDs come from: `PipelineIds`

All ID generation is centralised in `eu.inqudium.core.pipeline.PipelineIds`,
which exposes three producers with deliberately different contention
profiles:

| Method                             | Scope                  | Used by                                                                    |
|------------------------------------|------------------------|----------------------------------------------------------------------------|
| `nextChainId()`                    | JVM-global             | Every new wrapper chain, every new resolved pipeline, standalone executors |
| `nextStandaloneCallId()`           | JVM-global             | One-shot executions via `InqExecutor` / `InqAsyncExecutor`                 |
| `newInstanceCallIdSource()`        | instance-local factory | Every long-lived wrapper chain and every resolved pipeline                 |

`nextChainId()` and `nextStandaloneCallId()` are both backed by a single
shared `AtomicLong`. That is deliberate: chain IDs are drawn at most once
per chain construction (cold path), and standalone call IDs only appear in
one-shot executors that have no persistent state to attach a counter to —
the aggregate contention across callers is acceptable in exchange for not
having any per-caller state.

`newInstanceCallIdSource()` is the hot-path producer. It returns a fresh
`LongSupplier` backed by its own private `AtomicLong`. Long-lived chains
and resolved pipelines hold exactly one such supplier and invoke it once
per invocation.

### Why instance-local call-ID counters

A single shared `AtomicLong` incremented from many threads generates
cache-line contention: the `LOCK XADD` instruction forces the owning cache
line to migrate between cores, and at high throughput that migration cost
dominates the hot path — the uncontended ~5–10 ns CAS degrades to 100+ ns
per op.

Giving each pipeline (and each wrapper chain) its own private counter
partitions the contention along the same boundary along which the workload
already partitions: threads calling different pipelines never contend;
contention is bounded to the (typically small) set of threads sharing a
single pipeline. This is the same principle `LongAdder` applies internally,
except Inqudium gets it for free by following the pipeline structure
rather than adding a second layer of partitioning on top.

### Chain-ID inheritance: `AbstractBaseWrapper`

Wrapper chains built by composition (e.g. `SupplierWrapper`,
`CallableWrapper`, `FunctionWrapper`) inherit chain identity from their
delegate:

```java
// AbstractBaseWrapper, constructor body (paraphrased)
if (delegate instanceof AbstractBaseWrapper<?, ?> innerWrapper) {
    this.chainId      = innerWrapper.chainId();
    this.callIdSource = innerWrapper.callIdSource;   // same supplier instance
} else {
    this.chainId      = PipelineIds.nextChainId();
    this.callIdSource = PipelineIds.newInstanceCallIdSource();
}
```

Consequences:

- **Outer layers join the existing chain.** Wrapping a `SupplierWrapper`
  with another `SupplierWrapper` preserves the inner chain ID; every layer
  reports the same `chainId()`.
- **New chains start fresh.** Wrapping a plain `Supplier<T>` (not itself a
  wrapper) starts a new chain with a fresh ID and a fresh private counter.
- **Call IDs are drawn exactly once per invocation.** The outermost layer's
  entry point (`run()`, `get()`, `apply()`, `call()`, `proceed()`) calls
  `BaseWrapper.initiateChain(argument)`, which invokes
  `generateCallId()` — a single `getAsLong()` on the shared supplier —
  and then passes the resulting `long` through every inner layer as a
  parameter. Inner layers never draw their own IDs; they only forward
  the one they received.

### Resolved pipelines: `ResolvedPipelineState`

Pre-composed pipelines (`InqPipeline` + `SyncPipelineTerminal`,
`AsyncPipelineTerminal`, `HybridAspectPipelineTerminal`, etc.) fold their
layers once at construction time rather than lazily via the wrapper
hierarchy. They maintain their identity state in an immutable
`ResolvedPipelineState`:

```java
public static ResolvedPipelineState create(List<String> layerNames) {
    return new ResolvedPipelineState(
            PipelineIds.nextChainId(),
            PipelineIds.newInstanceCallIdSource(),
            layerNames);
}
```

On every `execute(...)`, the terminal draws the chain ID from the
pipeline state (a single field load) and the next call ID from the
private supplier (a single CAS), then composes the layer-action chain
and invokes it:

```java
long callId = pipelineState.nextCallId();
long cid    = pipelineState.chainId();
// ...compose actions into an InternalExecutor `current`...
return current.execute(cid, callId, null);
```

Empty pipelines use the shared `EMPTY` sentinel whose `nextCallId()` is
hard-wired to zero, so empty pipelines across the JVM do not share or
mutate a counter.

### Standalone executors

One-shot executors (`InqExecutor`, `InqAsyncExecutor`) have no pipeline or
wrapper chain to attach state to. They draw both IDs directly from the
JVM-global counters at the point of execution:

```java
((LayerAction<Void, T>) this).execute(
        PipelineIds.nextChainId(),
        PipelineIds.nextStandaloneCallId(),
        null,
        (chainId, callId, arg) -> supplier.get());
```

This intentionally uses a separate counter from the per-pipeline suppliers.
The cost is a single shared `AtomicLong` for standalone call IDs; the
benefit is that one-shot callers do not need to construct, retain, or
dispose of any per-caller state.

### How `InqEvent` and `InqException` consume the identity

Both `InqEvent` and `InqException` store both fields as primitive `long`:

```java
// InqEvent
protected InqEvent(long chainId, long callId, String elementName,
                   InqElementType elementType, Instant timestamp) { ... }

// InqException
protected InqException(long chainId, long callId, String code,
                       String elementName, InqElementType elementType,
                       String message, Throwable cause,
                       boolean enableExceptionOptimization) { ... }
```

`InqException` formats its message as `"[" + chainId + "-" + callId + "] "
+ code + ": " + message`, e.g.
`[42-17] INQ-CB-001: circuit 'paymentCb' is OPEN`. Downstream consumers
that expect string-typed correlation keys (MDC, log aggregation, JFR) can
format the `long` on the boundary; the primitive itself stays in the JVM.

### Paradigm scope

This ADR describes the identity model as implemented in `inqudium-core`
(sync) and `inqudium-imperative` (sync + async via `CompletionStage`).
The imperative async path reuses the same `long chainId` / `long callId`
pair through `InternalAsyncExecutor<A, R>`:

```java
public interface InternalAsyncExecutor<A, R> {
    CompletionStage<R> executeAsync(long chainId, long callId, A argument);
}
```

The `inqudium-kotlin`, `inqudium-reactor`, and `inqudium-rxjava3` modules
currently contain no production code. When a paradigm module is
implemented, its decorator contract will carry `chainId` and `callId` as
primitive parameters in the same way — passed explicitly on each step in
Kotlin's `suspend` signatures, carried in Reactor's `ContextView`, and
attached to RxJava subscribers respectively. The shape of those
extensions is not fixed by this ADR; they will be added as the paradigm
modules are built out.

### Why not a `Deadline` or other context field on `InqCall`?

A frequent design pressure is to add `Deadline`, `Priority`, or other
execution-scoped metadata as additional fields on a single context record.
The primitive-parameter model deliberately does not accommodate this by
field addition: adding a third `long` to every `InternalExecutor.execute(...)`
signature is a breaking change to a public contract.

If per-call deadlines or priorities are introduced in a future ADR, they
will be carried on the `argument` channel (which is already typed per
wrapper: `Void`, a domain input, or a context object the caller
constructs) — not by widening the identity pair. This keeps the identity
contract narrow and stable.

## Testability

Deterministic identity is straightforward: tests that need predictable
values can bypass `PipelineIds` entirely and invoke `LayerAction.execute`
or a terminal with fixed `long` values:

```java
var action = myDecorator; // any LayerAction<Void, String>
String result = action.execute(7L, 3L, null,
        (c, ca, a) -> "ok");

assertThat(capturedEvents).allSatisfy(ev -> {
    assertThat(ev.getChainId()).isEqualTo(7L);
    assertThat(ev.getCallId()).isEqualTo(3L);
});
```

No thread-local setup, no mocked generators, no UUID brittleness.

## Consequences

**Positive**

- **Zero per-call allocation for identity.** Two `long` parameters, no
  record instance, no string. The only allocation on the hot path is the
  escape-analysable `InternalExecutor` lambda that binds the terminal
  delegate, which C2 reliably scalar-replaces in practice.
- **Structurally guaranteed correlation.** No code path can cause two
  layers in the same chain to observe different IDs — the values are
  forwarded as method parameters, not read from ambient state.
- **Cache-line-contention-aware.** Per-pipeline `LongSupplier` instances
  prevent the shared-counter collapse that would otherwise appear under
  concurrent load on a single hot pipeline.
- **Virtual-thread compatible.** No `ThreadLocal`, no pinning, no
  `synchronized`. IDs flow through the call stack exactly like any other
  primitive argument.
- **Trivial to test.** `long` literals in, `long` assertions out.

**Negative**

- **Two fields instead of one.** Consumers that only want „one correlation
  key" must now decide between `chainId`, `callId`, or the concatenation
  `chainId-callId`. The message format on `InqException` picks
  concatenation by default.
- **Cross-paradigm story is deferred.** Kotlin coroutines, Reactor, and
  RxJava3 are not yet implemented; their identity-propagation shape is
  described only in principle. A future ADR will pin down each paradigm's
  concrete mechanism once the modules exist.
- **No extensibility via field addition.** Unlike the rejected
  `InqCall`-record design, the identity pair cannot grow new fields
  without changing `InternalExecutor.execute(...)`. New per-call metadata
  must travel on the `argument` channel instead.

**Neutral**

- `long chainId` / `long callId` are core observability contract types.
  Their presence on `InqEvent` and `InqException` is a stable API; the
  underlying generation strategy (global counter, per-instance supplier,
  inheritance rules) is an implementation detail of `PipelineIds`,
  `AbstractBaseWrapper`, and `ResolvedPipelineState` and can evolve
  without breaking consumers.
