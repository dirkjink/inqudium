/**
 * Inqudium Config — the paradigm-agnostic configuration framework.
 *
 * <p>This module defines the architecture established by ADR-025 through ADR-029: the DSL produces
 * patches, patches apply to immutable snapshots, snapshots are held by atomic live containers, and a
 * single {@code InqRuntime} aggregates the per-paradigm component containers. Initialization, runtime
 * updates, and format-adapter input (YAML, JSON, ...) all converge on the same patch-and-apply
 * mechanism — there is no second data model.
 *
 * <h2>Sub-packages</h2>
 *
 * <ul>
 *   <li>{@link eu.inqudium.config.snapshot} — sealed {@code ComponentSnapshot} hierarchy and the
 *       per-component {@code Snapshot} records. Records are immutable, validated by their compact
 *       constructors (ADR-027 class&nbsp;2), and paradigm-agnostic.</li>
 *   <li>{@link eu.inqudium.config.patch} — {@code ComponentPatch&lt;S&gt;} interface and per-component
 *       patch classes. Patches carry a BitSet of touched fields and apply a per-field choice
 *       (touched ⇒ new value, untouched ⇒ inherit from base) to produce a new snapshot.</li>
 *   <li>{@link eu.inqudium.config.live} — {@code LiveContainer&lt;S&gt;}, the atomic snapshot holder
 *       that pairs an {@code AtomicReference} with a subscriber list. Lock-free reads, CAS-loop
 *       writes, single notification per successful replacement.</li>
 *   <li>{@link eu.inqudium.config.runtime} — {@code InqRuntime}, {@code ParadigmContainer&lt;P&gt;},
 *       and the cross-paradigm {@code InqConfigView}. The {@code Inqudium#configure()} entry point
 *       lives here.</li>
 *   <li>{@link eu.inqudium.config.lifecycle} — {@code LifecycleState} (cold/hot), the
 *       {@code LifecycleAware} interface, the change-request and decision types, and the
 *       {@code PostCommitInitializable} hook used by the per-paradigm lifecycle base classes
 *       (ADR-028, ADR-029).</li>
 *   <li>{@link eu.inqudium.config.validation} — {@code BuildReport}, {@code ValidationFinding},
 *       {@code ApplyOutcome}, the {@code ConsistencyRule} and {@code CrossComponentRule} SPIs, and
 *       the strict-mode flag (ADR-027).</li>
 *   <li>{@link eu.inqudium.config.spi} — {@code ParadigmProvider}, the SPI through which paradigm
 *       modules announce themselves to the runtime.</li>
 *   <li>{@link eu.inqudium.config.event} — runtime-level lifecycle events
 *       ({@code RuntimeComponentAddedEvent}, {@code ComponentBecameHotEvent}, ...). These travel on
 *       the runtime-scoped publisher, distinct from the per-component event publishers in
 *       {@code inqudium-core}.</li>
 * </ul>
 *
 * <h2>Module boundaries</h2>
 *
 * <p>{@code inqudium-config} depends only on {@code inqudium-core}. Paradigm modules
 * ({@code inqudium-imperative}, {@code inqudium-reactive}, {@code inqudium-rxjava3},
 * {@code inqudium-kotlin}) depend on {@code inqudium-config} but never on each other. Format-adapter
 * modules ({@code inqudium-config-yaml}, ...) translate text into patches and feed them to the
 * runtime — they do not duplicate validation logic.
 *
 * @see <a href="../../../../../docs/adr/025-configuration-architecture.md">ADR-025</a>
 * @see <a href="../../../../../docs/adr/026-runtime-and-component-registry.md">ADR-026</a>
 * @see <a href="../../../../../docs/adr/027-validation-strategy.md">ADR-027</a>
 * @see <a href="../../../../../docs/adr/028-update-propagation-and-component-lifecycle.md">ADR-028</a>
 * @see <a href="../../../../../docs/adr/029-component-lifecycle-implementation-pattern.md">ADR-029</a>
 */
package eu.inqudium.config;
