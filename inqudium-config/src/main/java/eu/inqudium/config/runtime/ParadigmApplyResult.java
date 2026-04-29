package eu.inqudium.config.runtime;

import eu.inqudium.config.validation.ApplyOutcome;
import eu.inqudium.config.validation.VetoFinding;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result of applying one paradigm-section's worth of patches to a {@link ParadigmContainer}.
 *
 * <p>Aggregates the per-component {@link ApplyOutcome}s with the {@link VetoFinding}s collected
 * along the way. The runtime collects one of these per paradigm and merges them into the
 * {@code BuildReport}.
 *
 * @param outcomes      per-component outcomes keyed by {@link ComponentKey}, in registration
 *                      order; never null. Defensively copied to an immutable view.
 * @param vetoFindings  veto findings produced by the dispatcher's hot-path listener iteration,
 *                      in encounter order; never null. Defensively copied to an immutable view.
 */
public record ParadigmApplyResult(
        Map<ComponentKey, ApplyOutcome> outcomes,
        List<VetoFinding> vetoFindings) {

    public ParadigmApplyResult {
        Objects.requireNonNull(outcomes, "outcomes");
        Objects.requireNonNull(vetoFindings, "vetoFindings");
        outcomes = Map.copyOf(outcomes);
        vetoFindings = List.copyOf(vetoFindings);
    }

    /**
     * @return a result with no outcomes and no findings.
     */
    public static ParadigmApplyResult empty() {
        return new ParadigmApplyResult(Map.of(), List.of());
    }
}
