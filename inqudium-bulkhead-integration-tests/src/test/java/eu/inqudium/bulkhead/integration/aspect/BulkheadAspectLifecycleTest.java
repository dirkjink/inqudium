package eu.inqudium.bulkhead.integration.aspect;

import eu.inqudium.aspect.pipeline.AbstractPipelineAspect;
import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import eu.inqudium.aspect.pipeline.ElementLayerProvider;
import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.ComponentRemovedException;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.snapshot.CoDelStrategyConfig;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AspectJ-pipeline lifecycle compatibility with a real bulkhead (audit finding F-2.18-3 routed
 * into REFACTORING.md sub-step 2.20).
 *
 * <p>The three lifecycle scenarios from {@code BulkheadWrapperLifecycleTest} are re-played here
 * through the aspect-pipeline path. The path under test is
 * {@code AbstractPipelineAspect.execute(JoinPointExecutor)} → {@code ResolvedPipeline} →
 * {@code SyncPipelineTerminal} — the same code that {@code @Around} advice runs after AspectJ
 * weaving. Compile-time weaving is intentionally not used here because the property being
 * verified is the pipeline's interaction with the bulkhead's lifecycle, not the woven
 * advice itself; the structural seam is identical with or without the weaver.
 */
@DisplayName("Bulkhead aspect-pipeline lifecycle compatibility")
class BulkheadAspectLifecycleTest {

    /**
     * Test-only aspect that wraps exactly one element. The constructor takes a single
     * provider so each test can choose whether to wrap a bulkhead, an additional layer, or
     * a different element.
     */
    static final class SingleElementAspect extends AbstractPipelineAspect {
        SingleElementAspect(AspectLayerProvider<Object> provider) {
            super(List.of(provider));
        }
    }

    @Nested
    @DisplayName("cold-to-hot transition")
    class ColdToHot {

        @Test
        void aspect_pipeline_built_cold_transitions_on_first_invocation() throws Throwable {
            // What is to be tested: an aspect-pipeline is constructed against a bulkhead that
            // is still in its cold phase. The first JoinPointExecutor invocation through the
            // aspect must trigger the cold-to-hot transition transparently.
            // Why successful: the aspect returns the executor's value, the bulkhead's
            // concurrentCalls is zero afterwards, and the snapshot stays on the configured
            // strategy.
            // Why important: aspects subscribe to elements at construction time, often well
            // before the runtime has served any traffic; the cold-phase tolerance of the
            // ElementLayerProvider plus InqBulkhead path is the whole point of the lifecycle
            // architecture for AOP users.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("aop-cold", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<Void, Object> bh =
                        (InqBulkhead<Void, Object>) runtime.imperative().bulkhead("aop-cold");
                SingleElementAspect aspect = new SingleElementAspect(new ElementLayerProvider(bh));

                Object result = aspect.execute(() -> "cold-to-hot-ok");

                assertThat(result).isEqualTo("cold-to-hot-ok");
                assertThat(bh.concurrentCalls()).isZero();
                assertThat(bh.snapshot().strategy())
                        .isInstanceOf(SemaphoreStrategyConfig.class);
            }
        }
    }

    @Nested
    @DisplayName("strategy hot-swap")
    class HotSwap {

        @Test
        void aspect_pipeline_held_across_strategy_swap_reflects_the_new_strategy() throws Throwable {
            // What is to be tested: the same aspect-pipeline instance is used before and after
            // a runtime-driven strategy swap. The post-swap call must run on the new strategy.
            // Why successful: snapshot.strategy() reflects the new type and the call still
            // succeeds.
            // Why important: aspects are typically Spring-singletons or AspectJ-singletons —
            // they are constructed once and held for the JVM lifetime. Tolerance to in-flight
            // configuration changes is the only way the architecture can support runtime
            // tuning of resilience parameters without an aspect rebuild.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("aop-swap", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<Void, Object> bh =
                        (InqBulkhead<Void, Object>) runtime.imperative().bulkhead("aop-swap");
                SingleElementAspect aspect = new SingleElementAspect(new ElementLayerProvider(bh));

                assertThat(aspect.execute(() -> "warm")).isEqualTo("warm");
                assertThat(bh.snapshot().strategy())
                        .isInstanceOf(SemaphoreStrategyConfig.class);

                // When — quiescent strategy swap.
                runtime.update(u -> u.imperative(im -> im.bulkhead("aop-swap", b -> b
                        .codel(c -> c.targetDelay(Duration.ofMillis(50))
                                .interval(Duration.ofMillis(500))))));

                // Then — same aspect instance, new strategy on the next call.
                assertThat(bh.snapshot().strategy())
                        .isInstanceOf(CoDelStrategyConfig.class);
                assertThat(aspect.execute(() -> "post-swap")).isEqualTo("post-swap");
            }
        }
    }

    @Nested
    @DisplayName("structural removal")
    class StructuralRemoval {

        @Test
        void aspect_pipeline_after_markRemoved_raises_ComponentRemovedException() throws Throwable {
            // What is to be tested: the aspect-pipeline is built and used while the bulkhead
            // is hot, then the bulkhead is structurally removed. The next call through the
            // aspect must fail with ComponentRemovedException.
            // Why successful: the exception is thrown by the layer-action (which delegates to
            // bulkhead.execute(...)), then propagates through the pipeline to the aspect's
            // caller without being swallowed by an intermediate layer.
            // Why important: aspects must respect the same removal contract as direct calls;
            // otherwise removed components could continue to "phantom-execute" through the
            // AOP entry point.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("aop-remove", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<Void, Object> bh =
                        (InqBulkhead<Void, Object>) runtime.imperative().bulkhead("aop-remove");
                SingleElementAspect aspect = new SingleElementAspect(new ElementLayerProvider(bh));

                assertThat(aspect.execute(() -> "warm")).isEqualTo("warm");

                runtime.update(u -> u.imperative(im -> im.removeBulkhead("aop-remove")));

                assertThatThrownBy(() -> aspect.execute(() -> "after-remove"))
                        .isInstanceOf(ComponentRemovedException.class)
                        .hasMessageContaining("aop-remove");
            }
        }
    }
}
