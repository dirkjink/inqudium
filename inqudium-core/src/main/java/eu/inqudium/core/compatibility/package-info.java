/**
 * Behavioral change management — compatibility flags for safe library upgrades.
 *
 * <p>When Inqudium makes a behavioral change (e.g. a corrected sliding window boundary,
 * an improved backoff algorithm), the change is gated by a named flag in {@code InqFlag}.
 * The old behavior is preserved by default. The developer opts in to new behavior
 * explicitly — per flag, or all at once via {@code InqCompatibility.adoptAll()} (ADR-013).
 *
 * <h2>Key types</h2>
 * <ul>
 *   <li>{@code InqFlag} — enum of behavioral change flags. Each constant documents
 *       the version it was introduced, the old behavior, and the new behavior.</li>
 *   <li>{@code InqCompatibility} — builder: {@code adoptAll()}, {@code preserveAll()},
 *       per-flag configuration, and ServiceLoader merge with {@code InqCompatibilityOptions}.
 *       Resolution order: defaults → ServiceLoader (Comparable-sorted) → programmatic API.</li>
 *   <li>{@code InqCompatibilityOptions} — SPI for code-free flag configuration via
 *       {@link java.util.ServiceLoader}. Implementations that implement {@code Comparable}
 *       are sorted deterministically; others are appended in discovery order (ADR-014).</li>
 *   <li>{@code InqCompatibilityEvent} — emitted at element creation time as an audit
 *       trail of which flags are active (ADR-003).</li>
 * </ul>
 *
 * <h2>Resolution model</h2>
 * <p>Three layers, lowest to highest priority:
 * <ol>
 *   <li>Built-in defaults (all flags {@code false})</li>
 *   <li>ServiceLoader providers ({@code InqCompatibilityOptions}, Comparable-sorted)</li>
 *   <li>Programmatic API ({@code .compatibility()} on the config builder)</li>
 * </ol>
 * <p>Default merge strategy (Strategy B): programmatic flags override ServiceLoader
 * flags per-flag, not wholesale. {@code ignoreServiceLoader()} opts into Strategy A
 * (full replacement).
 *
 * <h2>Flag lifecycle</h2>
 * <p>Introduced (default=false) → default flipped to true (next minor) → removed (next major).
 * Flags are resolved at configuration time, not on the hot path (ADR-013).
 *
 * @see eu.inqudium.core.event.InqEventPublisher
 */
package eu.inqudium.core.compatibility;
