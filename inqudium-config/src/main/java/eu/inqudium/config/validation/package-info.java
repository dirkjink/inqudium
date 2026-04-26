/**
 * Validation framework — four classes of error, each enforced at its earliest detection point.
 *
 * <p>ADR-027 partitions configuration errors into four classes:
 *
 * <ol>
 *   <li><strong>Argument-range errors</strong> — single-value validity, enforced in DSL setters.</li>
 *   <li><strong>Snapshot invariants</strong> — cross-field invariants on a single snapshot,
 *       enforced in compact constructors.</li>
 *   <li><strong>Semantic conflicts</strong> — internally consistent but contradictory
 *       configurations, caught by {@link eu.inqudium.config.validation.ConsistencyRule consistency
 *       rules} during snapshot build.</li>
 *   <li><strong>Cross-component conflicts</strong> — multi-component pathologies, surfaced on demand
 *       via {@code runtime.diagnose()} and the
 *       {@link eu.inqudium.config.validation.CrossComponentRule CrossComponentRule} SPI.</li>
 * </ol>
 *
 * <p>{@link eu.inqudium.config.validation.BuildReport BuildReport} aggregates the findings from
 * classes 2&nbsp;and&nbsp;3 (and, in phase&nbsp;2, vetoes from ADR-028). {@code DiagnosisReport}
 * carries the class-4 findings.
 */
package eu.inqudium.config.validation;
