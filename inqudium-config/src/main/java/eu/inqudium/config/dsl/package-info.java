/**
 * The fluent DSL surface — paradigm-agnostic builder interfaces and the abstract base
 * implementation reused by every paradigm module.
 *
 * <p>{@link eu.inqudium.config.dsl.BulkheadBuilder BulkheadBuilder&lt;P&gt;} declares the methods
 * that work uniformly across paradigms (limits, wait duration, tags, presets). Per-paradigm
 * sub-interfaces ({@link eu.inqudium.config.dsl.ImperativeBulkheadBuilder
 * ImperativeBulkheadBuilder} and — in later phases — {@code ReactiveBulkheadBuilder} and
 * friends) extend it and add paradigm-specific methods.
 * {@link eu.inqudium.config.dsl.BulkheadBuilderBase BulkheadBuilderBase} owns the shared state
 * (the underlying {@code BulkheadPatch}, the preset-then-customize guard) so each concrete
 * builder in a paradigm module is a thin shell.
 *
 * <p>The DSL produces {@code ComponentPatch} instances; nothing in this package validates the
 * resulting snapshots — that is the job of the snapshot's compact constructor (ADR-027 class
 * 2). DSL setters validate their own arguments (class 1), and presets enforce the
 * preset-then-customize discipline (ADR-027 strategy A).
 */
package eu.inqudium.config.dsl;
