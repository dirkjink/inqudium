/**
 * Built-in {@link eu.inqudium.config.validation.ConsistencyRule ConsistencyRule}s and
 * {@link eu.inqudium.config.validation.CrossComponentRule CrossComponentRule}s shipped with the
 * configuration framework.
 *
 * <p>Both rule families are discovered at build time via {@link java.util.ServiceLoader}. The
 * framework's own rules are listed in
 * {@code META-INF/services/eu.inqudium.config.validation.ConsistencyRule} and
 * {@code .../CrossComponentRule}; application code contributes additional rules by adding its
 * own service entries on the classpath, no source changes required.
 *
 * @see eu.inqudium.config.validation.ConsistencyRule
 * @see eu.inqudium.config.validation.ConsistencyRulePipeline
 * @see eu.inqudium.config.validation.CrossComponentRule
 */
package eu.inqudium.config.validation.rules;
