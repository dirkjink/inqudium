package eu.inqudium.aspect.pipeline;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-033 Stage-3 closure proof for audit finding 2.17.2.
 *
 * <p>Before Stage 3, {@code InqBulkhead} did not satisfy the {@code <E extends InqElement &
 * InqDecorator<Void, Object>>} intersection bound on the {@link ElementLayerProvider} constructor:
 * the {@code InqElement} contract reached the bulkhead only through the legacy
 * {@code ImperativeBulkhead} marker interface, which did not extend {@code InqElement}, so passing
 * an {@code InqBulkhead} to {@code new ElementLayerProvider(bh, 100)} produced a
 * {@code "type parameter E is not within bound"} compile error.
 *
 * <p>Stage 3 lifted {@code InqElement} into {@code BulkheadHandle} and made {@code InqBulkhead}
 * implement {@code BulkheadHandle<ImperativeTag>} directly. The fact that this test compiles is
 * the closure proof: the constructor accepts a runtime-built {@code InqBulkhead} without any
 * adapter, intersection-type cast, or wrapper. The runtime assertions on the resulting provider
 * pin the wiring down to the layer name and order — both must reflect the bulkhead's identity.
 *
 * <p>The richer end-to-end coverage (cold-to-hot transition under a real wrapper pipeline,
 * strategy-hot-swap during inflight calls, post-removal behaviour) lives in the bulkhead
 * integration test module that 2.20 introduces. This test only fixes the structural contract that
 * 2.20's scenarios depend on.
 */
@DisplayName("ADR-033 Stage 3 — ElementLayerProvider accepts InqBulkhead directly")
class Adr033Stage3ClosureTest {

    @Test
    @DisplayName("should accept an InqBulkhead built via Inqudium.configure() as an ElementLayerProvider input")
    void should_accept_an_InqBulkhead_built_via_Inqudium_configure_as_an_ElementLayerProvider_input() {
        // What is to be tested: that audit finding 2.17.2 is structurally closed —
        // ElementLayerProvider's intersection-typed constructor accepts an InqBulkhead instance
        // without a "type parameter E is not within bound" error. The Stage-3 surface
        // consolidation (BulkheadHandle extends InqElement, InqBulkhead implements
        // BulkheadHandle<ImperativeTag>) is what brings the InqElement contract within reach.
        // Why successful: the test compiles, runs, and the resulting provider exposes the
        // bulkhead's identity in the layer name and the explicit order in the order accessor.
        // Why important: this is the structural precondition for the Phase-2 sub-step 2.18
        // (AspectJ integration) and for the bulkhead integration test module in 2.20. A
        // regression here would block both.

        // Given
        try (InqRuntime runtime = Inqudium.configure()
                .imperative(im -> im.bulkhead("payments", b -> b.balanced()))
                .build()) {
            @SuppressWarnings("unchecked")
            InqBulkhead<Void, Object> bulkhead =
                    (InqBulkhead<Void, Object>) runtime.imperative().bulkhead("payments");

            // When
            ElementLayerProvider provider = new ElementLayerProvider(bulkhead, 100);

            // Then
            assertThat(provider.element()).isSameAs(bulkhead);
            assertThat(provider.layerName()).isEqualTo("BULKHEAD(payments)");
            assertThat(provider.order()).isEqualTo(100);
        }
    }
}
