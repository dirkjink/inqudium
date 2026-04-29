package eu.inqudium.config.validation;

import eu.inqudium.config.snapshot.ComponentSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs class-3 {@link ConsistencyRule}s against fully built {@link ComponentSnapshot}s and
 * returns the collected {@link ValidationFinding}s.
 *
 * <p>The pipeline is a pure function — no global state, no side effects beyond the returned
 * list. Strict-mode behaviour is layered on top via {@link #elevateWarningsToErrors}: callers
 * decide when (or whether) to elevate. The pipeline is reusable across the build and update
 * paths.
 *
 * <p>For each snapshot the pipeline iterates every registered rule whose
 * {@link ConsistencyRule#appliesTo() appliesTo} type matches the snapshot's runtime class and
 * collects whatever the rule's {@link ConsistencyRule#check check} returns. Iteration order is
 * stable (snapshot order × rule order), so the resulting findings list reflects a deterministic
 * traversal that downstream tooling can rely on.
 */
public final class ConsistencyRulePipeline {

    private ConsistencyRulePipeline() {
        // utility class
    }

    /**
     * Apply every rule that fits each snapshot.
     *
     * @param snapshots the snapshots to inspect; never null. The stream is consumed by this
     *                  call.
     * @param rules     the registered rules; never null.
     * @return the collected findings, in iteration order. Never null.
     */
    public static List<ValidationFinding> apply(
            Stream<? extends ComponentSnapshot> snapshots, List<ConsistencyRule<?>> rules) {
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(rules, "rules");
        List<ValidationFinding> findings = new ArrayList<>();
        snapshots.forEach(snapshot -> applyAllRulesTo(snapshot, rules, findings));
        return findings;
    }

    @SuppressWarnings("unchecked")
    private static void applyAllRulesTo(
            ComponentSnapshot snapshot,
            List<ConsistencyRule<?>> rules,
            List<ValidationFinding> sink) {
        for (ConsistencyRule<?> rule : rules) {
            if (rule.appliesTo().isInstance(snapshot)) {
                // The instanceof guard above makes this cast safe: we have just confirmed the
                // snapshot's runtime class is assignable to the rule's appliesTo type.
                ConsistencyRule<ComponentSnapshot> typed =
                        (ConsistencyRule<ComponentSnapshot>) rule;
                typed.check(snapshot).ifPresent(sink::add);
            }
        }
    }

    /**
     * Strict-mode transform: every {@link Severity#WARNING WARNING}-level finding is rewritten
     * with {@link Severity#ERROR ERROR}. Other severities pass through unchanged.
     *
     * @param findings the findings to transform; never null. The list itself is not modified —
     *                 a new list is returned.
     * @return a new list with warnings elevated to errors.
     */
    public static List<ValidationFinding> elevateWarningsToErrors(
            List<ValidationFinding> findings) {
        Objects.requireNonNull(findings, "findings");
        return findings.stream()
                .map(ConsistencyRulePipeline::elevate)
                .collect(Collectors.toUnmodifiableList());
    }

    private static ValidationFinding elevate(ValidationFinding f) {
        if (f.severity() != Severity.WARNING) {
            return f;
        }
        return new ValidationFinding(f.ruleId(), Severity.ERROR, f.componentName(), f.message());
    }
}
