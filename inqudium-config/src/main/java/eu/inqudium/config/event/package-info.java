/**
 * Runtime-level lifecycle events.
 *
 * <p>These events travel on the runtime-scoped event publisher established at
 * {@code Inqudium.configure().build()} time, distinct from the per-component publishers in
 * {@code inqudium-core}. Operational tooling subscribes here to observe topology changes
 * (components added, removed, patched) and lifecycle transitions
 * ({@code ComponentBecameHotEvent}, {@code RuntimeComponentVetoedEvent}) without needing to walk
 * every {@code BuildReport}.
 *
 * <p>Per ADR-026 and ADR-028, the runtime emits:
 *
 * <ul>
 *   <li>{@code RuntimeComponentAddedEvent} — on every successful add.</li>
 *   <li>{@code RuntimeComponentRemovedEvent} — on every successful remove.</li>
 *   <li>{@code RuntimeComponentPatchedEvent} — on every successful patch that actually changed a
 *       field.</li>
 *   <li>{@code ComponentBecameHotEvent} — when a component transitions from cold to hot. Fired
 *       exactly once per component lifetime.</li>
 *   <li>{@code RuntimeComponentVetoedEvent} — when a hot patch is rejected by a listener or by the
 *       component-internal mutability check (phase&nbsp;2).</li>
 * </ul>
 */
package eu.inqudium.config.event;
