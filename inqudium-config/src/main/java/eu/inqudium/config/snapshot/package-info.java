/**
 * Immutable snapshot records — the canonical configuration state of every component.
 *
 * <p>A {@code ComponentSnapshot} is a value object. It is thread-safe by virtue of immutability,
 * comparable by {@code equals}, JSON-serializable without further configuration, and validated at
 * construction by its compact constructor (ADR-027 class&nbsp;2). Snapshots are paradigm-agnostic:
 * the {@code ParadigmTag} of a live component is carried by the handle and the {@code LiveContainer}
 * that owns the snapshot, not by the snapshot itself.
 *
 * <p>{@code ComponentSnapshot} is a sealed interface so that pattern matching across all snapshot
 * kinds is exhaustively checked by the compiler.
 *
 * @see eu.inqudium.config.patch
 * @see eu.inqudium.config.live
 */
package eu.inqudium.config.snapshot;
