package eu.inqudium.config.snapshot;

import eu.inqudium.config.lifecycle.ComponentField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BulkheadField")
class BulkheadFieldTest {

    @Test
    void should_implement_component_field() {
        // What is to be tested: that BulkheadField is a valid ComponentField. Why: the phase-2
        // ChangeRequest type uses Set<? extends ComponentField> as the type of touched fields,
        // so any per-component field enum must satisfy this contract.
        // Why important: a regression here would silently fall back to raw Object discriminators.

        // Given / When / Then
        for (BulkheadField field : BulkheadField.values()) {
            assertThat(field).isInstanceOf(ComponentField.class);
            assertThat(field.name()).isEqualTo(field.toString());
        }
    }

    @Test
    void should_expose_every_patchable_field_of_the_bulkhead_snapshot() {
        // Given / When / Then
        assertThat(BulkheadField.values())
                .extracting(BulkheadField::name)
                .containsExactlyInAnyOrder(
                        "NAME",
                        "MAX_CONCURRENT_CALLS",
                        "MAX_WAIT_DURATION",
                        "TAGS",
                        "DERIVED_FROM_PRESET",
                        "EVENTS");
    }
}
