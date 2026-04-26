/**
 * Built-in {@link eu.inqudium.config.validation.ConsistencyRule ConsistencyRule}s shipped with
 * the configuration framework.
 *
 * <p>Phase&nbsp;1 ships a small set of class-3 rules per ADR-027. Application-specific rules
 * are added via {@code ServiceLoader} discovery once that mechanism comes online in
 * phase&nbsp;2.
 *
 * @see eu.inqudium.config.validation.ConsistencyRule
 * @see eu.inqudium.config.validation.ConsistencyRulePipeline
 */
package eu.inqudium.config.validation.rules;
