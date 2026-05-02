# REFACTORING_PROXY_CONSOLIDATION.md

Plan for consolidating the three parallel proxy-creation mechanisms in
the codebase into a single unified architecture, fixing an architectural
divergence that surfaced during sub-step 6.D of the
bulkhead-logging-and-runtime-config refactor.

## The architectural finding

Three independent JDK-proxy creation mechanisms exist today, each with its
own invocation handler, object-method semantics, equals/hashCode
behaviour, caching strategy, and `Wrapper` interface support:

1. **`ProxyWrapper.createProxy(...)`** in `inqudium-core` — the "complete"
   architecture. Implements `Wrapper<S>` via the `interfaceType +
   Wrapper.class` proxy interface array. Has a dedicated `ProxyWrapper
   extends AbstractProxyWrapper extends AbstractBaseWrapper` invocation
   handler. Stable per-instance `chainId`. Real-target-comparing equals
   semantics. Per-extension `MethodHandleCache` shared up the stack via
   linked-extension constructors. Used by `InqProxyFactory` and
   `InqAsyncProxyFactory`.

2. **`ProxyPipelineTerminal.protect(...)`** in `inqudium-core` — a
   reduced parallel mechanism for `InqPipeline` input. Inline lambda
   invocation handler. No `Wrapper.class` interface — proxies cannot be
   cast to `Wrapper`. No `chainId` concept. Identity-based equals/
   hashCode (proxy reference comparison). Terminal-internal
   `MethodHandleCache`.

3. **`HybridProxyPipelineTerminal.protect(...)`** in `inqudium-imperative`
   — a third parallel mechanism for sync+async hybrid dispatch over an
   `InqPipeline`. Inline lambda invocation handler. No `Wrapper.class`
   interface. No `chainId` concept. Identity-based equals/hashCode.
   Terminal-internal `MethodHandleCache`. Per-`Method` `CachedChain`
   structure that bundles the sync/async decision, the chain factory,
   and the method invoker.

The three mechanisms emerged historically: `ProxyWrapper` is the
"complete" architecture, while `ProxyPipelineTerminal` and
`HybridProxyPipelineTerminal` were added as convenience wrappers to lift
`InqPipeline` directly into a proxy. Their "convenience" was achieved by
duplicating the proxy-creation machinery rather than reusing
`ProxyWrapper.createProxy(...)`. This produced three separate
implementations of object-method handling, three different `equals()`
contracts, two separate `Wrapper` worlds, and a fundamental
inconsistency that 6.D exposed: a topology log line of the form
"placeOrder protected by BULKHEAD(orderBh) (chain-id N)" cannot be
written for proxies created via `HybridProxyPipelineTerminal`, because
neither `Wrapper`-introspection nor a stable `chainId` is available.

## What this refactor does

Consolidates the three mechanisms into one. `ProxyWrapper.createProxy(...)`
becomes the single proxy-creation point. The two `Terminal` classes are
**deleted entirely** — they are not behind a delete-and-replace migration,
they are dropped without preserving their public API. Their public role
is taken over by new overloaded factory methods on the existing factory
interfaces:

- `InqProxyFactory.of(InqPipeline)` — new sync-only factory variant for
  pipeline input (today: `ProxyPipelineTerminal.of(pipeline).protect(...)`).
- `InqAsyncProxyFactory.of(InqPipeline)` — new hybrid-dispatch factory
  variant for pipeline input (today:
  `HybridProxyPipelineTerminal.of(pipeline).protect(...)`).

Both new factory variants delegate to `ProxyWrapper.createProxy(...)`
with appropriate dispatch extensions. The dispatch extensions for the
pipeline-input case are new:

- **`PipelineDispatchExtension`** — sync pipeline dispatch. Catch-all
  (`canHandle` always returns `true`, `isCatchAll` returns `true`).
  Builds the chain factory via `pipeline.chain(...)` once at construction;
  each invocation runs the cached factory with a method-specific terminal.
  Linked-constructor pattern with `MethodHandleCache` sharing across
  stacked layers, identical to `SyncDispatchExtension`'s pattern.
- **`AsyncPipelineDispatchExtension`** — async pipeline dispatch.
  `canHandle` returns `true` only for methods returning `CompletionStage`.
  Linked-constructor pattern with `MethodHandleCache` sharing, identical
  to `AsyncDispatchExtension`'s pattern.

For hybrid use (a service interface with mixed sync and async methods),
`InqAsyncProxyFactory.of(InqPipeline)` registers both extensions
internally — `AsyncPipelineDispatchExtension` first (specific:
`CompletionStage` returns), `PipelineDispatchExtension` second
(catch-all). This matches what `HybridProxyPipelineTerminal` does today
via its per-method `CachedChain.async` flag, but rendered as the standard
extension-priority mechanism.

This is feasible because the codebase has no external consumers yet —
the maintainer confirmed that no users depend on the current
`HybridProxyPipelineTerminal` or `ProxyPipelineTerminal` public API.
The aggressive shape (delete, don't preserve) is the right one in this
window.

## Behavioral changes (intentional)

The consolidated architecture produces three observable behavior
changes for proxies that were previously created through the deleted
terminals:

1. **`equals()`** — was identity-based (`proxy == args[0]`), becomes
   `realTarget`-comparing (per `AbstractProxyWrapper.handleEquals`).
   Two proxies wrapping the same underlying real target now report
   `equals` as `true` even if they are distinct proxy instances.
2. **`hashCode()`** — was `System.identityHashCode(proxy)`, becomes
   `realTarget.hashCode()`. Consistent with the new equals contract.
3. **`toString()`** — was a `ProxyInvocationSupport.buildSummary(...)`
   string, becomes the `AbstractProxyWrapper.handleObjectMethod`
   format `"layerDescription -> realTarget.toString()"`.

These changes are intentional improvements — they bring the previously
divergent terminal-based proxies into line with `ProxyWrapper`-based
proxies. Tests that pinned the old identity-equals or summary-toString
behavior must be updated (see P.G).

## What this refactor does NOT do

- Does NOT rename `InqProxyFactory` or `InqAsyncProxyFactory`. Naming
  cleanup (the maintainer's "Option 4" from the design discussion) is
  a separate, optional follow-up.
- Does NOT change `InqPipeline` itself. The pipeline-as-self-aware-
  factory pattern (`pipeline.toProxy(...)`) is a future possibility,
  not part of this work.
- Does NOT touch the `inqudium-spring` `InqShieldAspect` or its
  introspection API from refactor 6.B. Spring AOP proxies are a
  separate concern; this refactor only addresses JDK dynamic proxies
  in the `inqudium-core` and `inqudium-imperative` proxy chain.
- Does NOT remove the `execute(JoinPointExecutor)` /
  `executeAsync(JoinPointExecutor)` "uncached, useful for unit tests"
  methods on `HybridProxyPipelineTerminal` separately from deleting
  the class — they go away when the class goes away. Tests that used
  them must be rewritten against the new API.

## Why now

This refactor is required because sub-step 6.D of the
bulkhead-logging-and-runtime-config refactor cannot complete without it.
The 6.D `OrderService` topology log demands a `Wrapper`-cast and a
stable `chainId` that today's `HybridProxyPipelineTerminal` does not
provide. Patching only the `chainId` lookup (the minimal-fix option)
would leave the wider divergence intact and complicate any future
unified-introspection-API work the maintainer has planned. Doing this
work now closes the architectural gap once.

## Sub-steps

### P.A: Plan document at the repository root

This document. Includes its own completion-log entry update from
`[ ]` to `[x]` once the file is committed and the PR opened.

Also updates `REFACTORING_BULKHEAD_LOGGING_AND_RUNTIME_CONFIG.md`'s
completion log to mark 6.D as paused with an `Awaiting:` note pointing
at this plan.

### P.B: PipelineDispatchExtension (sync)

New class:
`inqudium-core/src/main/java/eu/inqudium/core/pipeline/proxy/PipelineDispatchExtension.java`

A `DispatchExtension` analogous to `SyncDispatchExtension` but driven by
an `InqPipeline` instead of a single `LayerAction`. Implements:

- Public constructor `PipelineDispatchExtension(InqPipeline pipeline)`
  — fresh `MethodHandleCache`, no inner-extension link, identity
  next-step factory.
- Private linked constructor
  `PipelineDispatchExtension(InqPipeline pipeline,
  PipelineDispatchExtension inner, Object realTarget,
  MethodHandleCache handleCache)` — chains into the inner extension's
  `executeChain(...)` and inherits the outer extension's
  `MethodHandleCache`. Identical sharing pattern to
  `SyncDispatchExtension`.
- `canHandle(Method) → true` (catch-all).
- `isCatchAll() → true`.
- `linkInner(DispatchExtension[], Object)` — `findInner` searches for
  a `PipelineDispatchExtension` (exact-type match, not
  `SyncDispatchExtension` — gemischte Stacks gehen durch normale
  Proxy-Re-Entry).
- `dispatch(...)` and `executeChain(...)` — pre-composes the chain
  factory once via `pipeline.chain(...)` (mirroring
  `HybridProxyPipelineTerminal.buildSyncChainFactory()`), reuses it
  for every invocation. Terminal builds via the cached `MethodInvoker`.
- `buildTerminal(...)` — same shape as `SyncDispatchExtension`'s, with
  `handleException(method, e)` for reflection-error unwrapping.

Tests in
`inqudium-core/src/test/java/eu/inqudium/core/pipeline/proxy/PipelineDispatchExtensionTest.java`,
parallel in shape to existing `SyncDispatchExtension` tests:

- Cache-sharing test: stacked layers share their `MethodHandleCache`
  via the linked-extension constructor.
- Chain-walk-optimization test: when the outer
  `PipelineDispatchExtension` is linked to an inner one, the chain
  walk bypasses proxy re-entry and the terminal invokes the deep
  `realTarget` directly.
- Standalone-fallback test: no compatible inner extension produces a
  fresh standalone instance with the existing handle cache preserved.
- Idempotence test: pipeline folding happens once; repeated invocations
  reuse the cached factory.

### P.C: AsyncPipelineDispatchExtension (async)

New class:
`inqudium-imperative/src/main/java/eu/inqudium/imperative/core/pipeline/AsyncPipelineDispatchExtension.java`

A `DispatchExtension` analogous to `AsyncDispatchExtension` but driven
by an `InqPipeline` instead of a single `AsyncLayerAction`. Implements:

- Public constructor `AsyncPipelineDispatchExtension(InqPipeline pipeline)`.
- Private linked constructor with `AsyncPipelineDispatchExtension inner`
  and inherited `MethodHandleCache`.
- `canHandle(Method) → CompletionStage.class.isAssignableFrom(method.getReturnType())`.
- `isCatchAll() → false` (must NOT be catch-all — sync-only methods
  must fall through to the catch-all extension behind it).
- `linkInner(DispatchExtension[], Object)` — `findInner` searches
  `AsyncPipelineDispatchExtension` exact-type match.
- `dispatch(...)` and `executeChain(...)` — pre-composes the async
  chain factory once via `pipeline.chain(...)` (mirroring
  `HybridProxyPipelineTerminal.buildAsyncChainFactory()`).
- `buildTerminal(...)` — same shape as `AsyncDispatchExtension`'s,
  including the runtime check that the method's return value is a
  non-null `CompletionStage`. The check stays defensive — it should
  never trigger if `canHandle` worked correctly, but a misconfigured
  pipeline could surface here.

Tests in
`inqudium-imperative/src/test/java/eu/inqudium/imperative/core/pipeline/AsyncPipelineDispatchExtensionTest.java`,
parallel in shape to existing `AsyncDispatchExtension` tests, with the
same four properties pinned (cache sharing, chain-walk optimization,
standalone fallback, idempotence).

### P.D: New factory methods

In existing classes:

- `InqProxyFactory.of(InqPipeline pipeline)` — static factory that
  returns an `InqProxyFactory` whose `protect(Class<T>, T)` calls
  `ProxyWrapper.createProxy(serviceInterface, target, name, pipelineExtension)`
  with a single `PipelineDispatchExtension`. The layer name should
  default to a sensible value (e.g. "InqPipelineProxy" or similar —
  use whatever reads cleanest).
- `InqAsyncProxyFactory.of(InqPipeline pipeline)` — static factory
  that returns an `InqAsyncProxyFactory` whose `protect(Class<T>, T)`
  calls `ProxyWrapper.createProxy(...)` with TWO extensions:
  `AsyncPipelineDispatchExtension` first (specific, returns
  `CompletionStage`), `PipelineDispatchExtension` second (catch-all).
  This matches what `HybridProxyPipelineTerminal` does today —
  per-method dispatch is decided at invocation time by the standard
  extension-priority mechanism (`canHandle` order), no separate
  `CachedChain.async` flag needed.

The new factories must NOT preserve the deleted terminals' identity
behavior. They produce `Wrapper`-conforming, `chainId`-stable proxies.

Tests in the existing factory test classes:

- `InqProxyFactoryTest` (or equivalent) — pin that
  `InqProxyFactory.of(pipeline)` produces a proxy that is
  `instanceof Wrapper`, has a positive `chainId()`, and routes through
  the pipeline.
- `InqAsyncProxyFactoryTest` (or equivalent) — analogous, plus pin the
  hybrid behavior: a service interface with both sync and async methods
  routes each to the correct extension based on return type.

### P.E: Delete ProxyPipelineTerminal

Delete:
`inqudium-core/src/main/java/eu/inqudium/core/pipeline/proxy/ProxyPipelineTerminal.java`

Find all consumers via repo-wide grep on `ProxyPipelineTerminal`. Each
consumer (test, example, library code) is migrated to
`InqProxyFactory.of(pipeline).protect(serviceInterface, target)`.

Tests that pinned the old identity-`equals` or summary-`toString`
behavior of `ProxyPipelineTerminal`-created proxies must be updated to
the new behavior (real-target equals, layerDescription→target toString).
Each such test gets a one-line comment noting the migration:

```java
// Migrated from ProxyPipelineTerminal: equals semantics changed from
// identity to real-target comparison after the proxy consolidation
// (see REFACTORING_PROXY_CONSOLIDATION.md, P.G).
```

### P.F: Delete HybridProxyPipelineTerminal

Delete:
`inqudium-imperative/src/main/java/eu/inqudium/imperative/core/pipeline/HybridProxyPipelineTerminal.java`

Find all consumers via repo-wide grep on `HybridProxyPipelineTerminal`.
Known consumers include:

- Internal tests in `inqudium-imperative` (e.g.
  `HybridProxyPipelineTerminalTest`).
- The bulkhead integration example module
  `inqudium-bulkhead-integration-proxy` (5.C delivered this; the example
  uses `HybridProxyPipelineTerminal.of(...).protect(...)` directly).
- Any library tests in `inqudium-bulkhead-library-tests` that test
  through the hybrid terminal.

Each consumer migrates to
`InqAsyncProxyFactory.of(pipeline).protect(serviceInterface, target)`.

The deleted `execute(JoinPointExecutor)` and `executeAsync(JoinPointExecutor)`
methods (the "uncached, useful for unit tests" ones) have no replacement —
tests that used them must be rewritten against the standard `protect(...)`
form. Pause and report if the rewrite is non-trivial; the maintainer has
indicated these methods were a flawed convenience and should disappear.

### P.G: Behavioral verification

After P.E and P.F are merged, walk the test suites of
`inqudium-core`, `inqudium-imperative`, and the bulkhead integration
modules and verify:

- All existing tests pass with the new behavior.
- Tests that explicitly pinned identity-`equals` or summary-`toString`
  on terminal-created proxies have been updated and have the migration
  comment from P.E / P.F.
- The bulkhead integration example modules (especially
  `inqudium-bulkhead-integration-proxy`) still produce the expected
  three-phase Main demo output described in 5.C — same observable
  behavior, only the underlying proxy-creation mechanism changed.
- `((Wrapper<?>) proxy).chainId()` now works on proxies produced by
  the consolidated `InqAsyncProxyFactory.of(pipeline).protect(...)`.
  This is the property that 6.D will rely on.
- Sanity check: a service with both sync and async methods, proxied via
  `InqAsyncProxyFactory.of(pipeline).protect(...)`, dispatches each
  method correctly. Add a smoke test if none exists.

If the verification surfaces tests that pinned the old behavior in a
non-obvious way (e.g. via deep object-equality assertions that
incidentally relied on identity semantics), pause and report.

### P.H: Documentation closure

Tasks:

- Repo-wide grep for stale references to `ProxyPipelineTerminal` and
  `HybridProxyPipelineTerminal`. Confirm all references are gone from
  live code (Git history is fine).
- Add an entry to `ADR_DRIFT_NOTES.md` recording two follow-ups:
  - **The naming-cleanup option** (Option 4 in the design discussion):
    `InqProxyFactory` and `InqAsyncProxyFactory` could be renamed (e.g.
    to make explicit that the latter is hybrid-dispatch, not async-only).
    Note this as a small follow-up — it requires changing user-facing
    names but no behavior.
  - **The unified introspection API** (the maintainer's stated future
    intent): with the proxy consolidation done, the proxy side of the
    unified-introspection question is settled (every proxy implements
    `Wrapper`). The remaining work is harmonizing AspectJ
    (`AbstractPipelineAspect`'s API) with Spring-AOP
    (`InqShieldAspect`'s API from 6.B), which produces the
    technology-spanning thin wrapper API the maintainer has flagged.
- Confirm `REFACTORING_BULKHEAD_LOGGING_AND_RUNTIME_CONFIG.md`'s 6.D
  entry can have its `Awaiting:` note removed once this refactor is
  done — but DO NOT remove it as part of P.H. The 6.D resumption is
  the next sub-step in the OTHER refactor's chain; this refactor's
  closure ends at P.H.
- Delete `REFACTORING_PROXY_CONSOLIDATION.md` per its own
  document-lifecycle section.

## Sequencing rationale

P.A → P.B → P.C → P.D → P.E → P.F → P.G → P.H.

P.B and P.C (the new extensions) come before P.D (the new factory
methods that use them). P.D comes before P.E and P.F (the deletions
that depend on the new factories existing). P.E (sync terminal) comes
before P.F (hybrid terminal) because the sync side has fewer consumers
and is a smaller migration — establishing the migration pattern on the
simpler case first. P.G consolidates verification across both
deletions. P.H is the documentation closure.

## Completion-log discipline

Same as REFACTORING_BULKHEAD_LOGGING_AND_RUNTIME_CONFIG.md. Every
sub-step's prompt includes an explicit instruction to update its log
entry as part of the sub-step's wrap-up. Format:

- `- [ ] P.X — Title` — sub-step not yet started or in progress without
  a defined pause-state.
- `- [x] P.X — Title (YYYY-MM-DD, PR #N)` — sub-step complete.
- `- [ ] P.X — Title`
   `      Awaiting: <one-line description>` — sub-step paused.

The discipline applies retroactively to P.A: the prompt that creates
this document also updates P.A's entry to `[x]`.

## Risk register

- **Cache-sharing in linked extensions** (P.B, P.C): the
  `MethodHandleCache` must be inherited via the linked-constructor
  path, exactly as `SyncDispatchExtension` and `AsyncDispatchExtension`
  do today. A linked extension that allocates a fresh cache would
  silently double the per-method resolution work in stacked-proxy
  scenarios. The plan's parallel-to-existing-extensions framing makes
  this explicit, but the implementation is subtle enough that tests
  must pin it.

- **Hybrid-dispatch ordering** (P.D): in
  `InqAsyncProxyFactory.of(InqPipeline)`, the
  `AsyncPipelineDispatchExtension` MUST be registered before the
  `PipelineDispatchExtension` (catch-all). Reverse ordering would route
  every method through the sync chain — including async ones, which
  would then return `CompletionStage` results from the sync chain's
  layer-actions, breaking semantics silently.

- **Behavioral changes** (P.E, P.F, P.G): the equals/hashCode/toString
  changes are intentional improvements, but tests written against the
  old terminal behavior must be updated. The risk is that some tests
  pin the old behavior implicitly (e.g. via object-equality assertions
  that incidentally relied on identity). P.G's verification step is
  the safety net.

- **Bulkhead integration example migration** (P.F): the
  `inqudium-bulkhead-integration-proxy` module from 5.C uses
  `HybridProxyPipelineTerminal` directly. Its Main, OrderService, and
  tests must migrate to the new factory call. The migration should be
  one-line per call site, but verify the manual run produces the same
  three-phase demo output.

- **Hidden consumers** (P.E, P.F): the repo-wide grep must be the
  authoritative source for finding consumers, not a list compiled from
  memory. A missed consumer would cause a compile failure at the
  delete commit, which is the right way for it to surface — but the
  plan should make the grep step unambiguous.

## Document lifecycle

This document lives at the repository root for the duration of the
proxy-consolidation refactor. When all eight sub-steps are complete and
signed off, this document is deleted in P.H. The audit trail at that
point lives in Git history, the closed PRs, the consolidated proxy
source code, and the new entry in `ADR_DRIFT_NOTES.md` recording the
naming-cleanup and unified-introspection follow-ups.

## Completion log

- [x] P.A — Plan document (2026-05-02, PR #41)
- [x] P.B — PipelineDispatchExtension (sync) (2026-05-02, PR #42)
- [ ] P.C — AsyncPipelineDispatchExtension (async)
- [ ] P.D — New factory methods on InqProxyFactory and InqAsyncProxyFactory
- [ ] P.E — Delete ProxyPipelineTerminal
- [ ] P.F — Delete HybridProxyPipelineTerminal
- [ ] P.G — Behavioral verification
- [ ] P.H — Documentation closure

The discipline for keeping this log current is described in the
"Completion-log discipline" section above. Every sub-step's wrap-up
includes an instruction to update its own entry.
