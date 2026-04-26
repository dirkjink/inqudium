package eu.inqudium.config.spi;

import eu.inqudium.config.patch.BulkheadPatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ParadigmSectionPatches")
class ParadigmSectionPatchesTest {

    @Test
    void should_carry_supplied_bulkhead_patches_in_registration_order() {
        // Given
        BulkheadPatch p1 = new BulkheadPatch();
        BulkheadPatch p2 = new BulkheadPatch();
        Map<String, BulkheadPatch> source = new LinkedHashMap<>();
        source.put("first", p1);
        source.put("second", p2);

        // When
        ParadigmSectionPatches patches = new ParadigmSectionPatches(source);

        // Then
        assertThat(patches.bulkheadPatches()).containsExactly(
                Map.entry("first", p1),
                Map.entry("second", p2));
    }

    @Test
    void should_defensively_copy_the_supplied_map() {
        // What is to be tested: that the section's stored map is independent of the caller's.
        // Why successful: post-construction mutations to the caller map do not alter the
        // section's view.
        // Why important: providers receive this section and may iterate concurrently with
        // ongoing DSL traversal in pathological cases — the immutable view guarantees
        // iteration safety.

        // Given
        BulkheadPatch p = new BulkheadPatch();
        Map<String, BulkheadPatch> mutable = new LinkedHashMap<>();
        mutable.put("first", p);
        ParadigmSectionPatches patches = new ParadigmSectionPatches(mutable);

        // When
        mutable.put("later", new BulkheadPatch());
        mutable.clear();

        // Then
        assertThat(patches.bulkheadPatches()).containsExactly(Map.entry("first", p));
    }

    @Test
    void should_expose_an_unmodifiable_view() {
        // Given
        ParadigmSectionPatches patches = new ParadigmSectionPatches(Map.of("a", new BulkheadPatch()));

        // When / Then
        assertThatThrownBy(() -> patches.bulkheadPatches().put("x", new BulkheadPatch()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_report_empty_when_no_patches_were_supplied() {
        // Given / When
        ParadigmSectionPatches patches = new ParadigmSectionPatches(Map.of());

        // Then
        assertThat(patches.isEmpty()).isTrue();
        assertThat(patches.bulkheadPatches()).isEmpty();
    }

    @Test
    void should_reject_a_null_bulkhead_map() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new ParadigmSectionPatches(null))
                .withMessageContaining("bulkheadPatches");
    }
}
