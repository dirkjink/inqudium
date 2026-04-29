package eu.inqudium.config.validation.rules;

import eu.inqudium.config.validation.ConsistencyRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ServiceLoader-discovery pin for the framework's class-3 rules. Per ADR-027 / REFACTORING.md
 * 2.7 the framework no longer hardcodes the list of {@link ConsistencyRule}s in any source
 * file; rules are picked up exclusively through
 * {@code META-INF/services/eu.inqudium.config.validation.ConsistencyRule}. This test proves the
 * discovery mechanism works in isolation from the build pipeline — a regression that broke the
 * service file (deleted line, wrong fully-qualified name, missing META-INF directory) would
 * show up here even before any runtime is built.
 */
@DisplayName("ConsistencyRule ServiceLoader discovery")
class ConsistencyRuleServiceLoaderTest {

    @Test
    void should_discover_BulkheadProtectiveWithLongWaitRule_via_ServiceLoader() {
        // Given / When
        List<ConsistencyRule<?>> discovered = new ArrayList<>();
        for (ConsistencyRule<?> rule : ServiceLoader.load(ConsistencyRule.class)) {
            discovered.add(rule);
        }

        // Then
        assertThat(discovered)
                .as("the framework's built-in rule must be reachable through ServiceLoader")
                .hasAtLeastOneElementOfType(BulkheadProtectiveWithLongWaitRule.class);
    }

    @Test
    void discovered_rules_should_carry_stable_identifiers_and_typed_appliesTo() {
        // What is to be tested: that every discoverable rule actually responds to its SPI
        // accessors without throwing — a sanity check that ServiceLoader-discovered instances
        // are properly constructed (no-arg constructor, no static-init disasters).

        for (ConsistencyRule<?> rule : ServiceLoader.load(ConsistencyRule.class)) {
            assertThat(rule.ruleId())
                    .as("rule %s must report a non-blank id",
                            rule.getClass().getSimpleName())
                    .isNotBlank();
            assertThat(rule.appliesTo()).isNotNull();
            assertThat(rule.defaultSeverity()).isNotNull();
        }
    }
}
