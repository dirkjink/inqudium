package eu.inqudium.config.validation.rules;

import eu.inqudium.config.validation.ConsistencyRule;

import java.util.List;

/**
 * Hardcoded registry of the rules the framework ships with in phase&nbsp;1.
 *
 * <p>{@code ServiceLoader}-based registration is added in phase&nbsp;2 (REFACTORING.md
 * step&nbsp;2.5); for now, the build pipeline reads the list from this class. Tests can pass
 * their own list to {@link eu.inqudium.config.validation.ConsistencyRulePipeline#apply
 * ConsistencyRulePipeline.apply} directly to test rules in isolation from the registry.
 */
public final class BuiltInConsistencyRules {

    private static final List<ConsistencyRule<?>> RULES =
            List.of(new BulkheadProtectiveWithLongWaitRule());

    private BuiltInConsistencyRules() {
        // utility class
    }

    /**
     * @return the currently-registered phase-1 rules. The list is unmodifiable.
     */
    public static List<ConsistencyRule<?>> all() {
        return RULES;
    }
}
