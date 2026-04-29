package eu.inqudium.config.validation;

import eu.inqudium.config.snapshot.ComponentSnapshot;

import java.util.Optional;

/**
 * Class-3 validation rule: a single-snapshot semantic check.
 *
 * <p>Each rule applies to one snapshot type (e.g. {@code BulkheadSnapshot}) and inspects the fully
 * built snapshot for combinations that pass classes 1 and 2 but contradict the user's apparent
 * intent — for instance, a {@code protective} preset paired with a multi-second
 * {@code maxWaitDuration}. Rules are pure functions of the snapshot; they may not consult
 * external state.
 *
 * <p>Rules are discovered via {@link java.util.ServiceLoader}: the framework's own built-in rules
 * are registered in {@code META-INF/services/eu.inqudium.config.validation.ConsistencyRule} of
 * {@code inqudium-config}; application code adds its own by shipping a service entry on the
 * classpath. Each rule reports a stable {@link #ruleId() ruleId} so per-rule severity overrides
 * and dashboards remain consistent across versions.
 *
 * @param <S> the snapshot type the rule applies to.
 */
public interface ConsistencyRule<S extends ComponentSnapshot> {

    /**
     * @return the rule's stable identifier (e.g. {@code BULKHEAD_PROTECTIVE_WITH_LONG_WAIT}).
     */
    String ruleId();

    /**
     * @return the snapshot type the rule applies to. The framework only invokes the rule on
     *         snapshots assignable to this type.
     */
    Class<S> appliesTo();

    /**
     * @return the rule's default severity. Strict mode and per-rule overrides may alter the
     *         effective severity at runtime.
     */
    Severity defaultSeverity();

    /**
     * @param snapshot the fully built snapshot to inspect.
     * @return a finding if the snapshot violates the rule, or {@code Optional.empty()} otherwise.
     */
    Optional<ValidationFinding> check(S snapshot);
}
