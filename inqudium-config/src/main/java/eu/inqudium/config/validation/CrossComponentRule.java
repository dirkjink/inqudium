package eu.inqudium.config.validation;

import eu.inqudium.config.runtime.InqConfigView;

import java.util.List;

/**
 * Class-4 validation rule: a multi-component diagnostic check.
 *
 * <p>Cross-component rules are not run automatically. They are invoked only by
 * {@code runtime.diagnose()}, which gives the operator (or the CI/CD pipeline) explicit control
 * over when potentially expensive multi-component checks run. A single rule inspects an
 * {@link InqConfigView} of the entire runtime topology and may return zero or more
 * {@link DiagnosticFinding}s referencing one or several components.
 *
 * <p>Rules are discovered via {@link java.util.ServiceLoader}; built-in rules ship with
 * {@code inqudium-config} and component-specific paradigm modules contribute their own.
 */
public interface CrossComponentRule {

    /**
     * @return the rule's stable identifier (e.g. {@code RETRY_BURST_CAN_FILL_BULKHEAD}).
     */
    String ruleId();

    /**
     * @return the rule's default severity. Per-rule overrides may alter the effective severity.
     */
    Severity defaultSeverity();

    /**
     * @param view the cross-paradigm read view onto the configured components.
     * @return zero or more findings produced by inspecting the topology. Never null.
     */
    List<DiagnosticFinding> check(InqConfigView view);
}
