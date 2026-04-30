package eu.inqudium.bulkhead.integration.spring;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.snapshot.CoDelStrategyConfig;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.core.element.InqElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot integration test that exercises a runtime-driven strategy hot-swap on a
 * bulkhead while a Spring-managed service routes through the same bulkhead via
 * {@code @InqBulkhead}.
 *
 * <p>The structural seam under test: the {@code InqElement} bean wired into
 * {@code InqElementRegistry} is the very same handle the runtime mutates, so a runtime
 * patch is observable through the AOP path without rebuilding the aspect.
 */
@SpringBootTest(classes = BulkheadSpringBootHotSwapTest.HotSwapApplication.class)
@DisplayName("Bulkhead Spring Boot — runtime hot-swap")
class BulkheadSpringBootHotSwapTest {

    @SpringBootApplication
    static class HotSwapApplication {

        /**
         * Two independent bulkheads — one drives the strategy-swap test, the other drives
         * the in-place limit-retune test. Sharing a single Spring context means tests
         * inside this class observe each other's runtime mutations; using disjoint names
         * gives each method its own state without {@code @DirtiesContext} overhead.
         */
        @Bean(destroyMethod = "close")
        public InqRuntime inqRuntime() {
            return Inqudium.configure()
                    .imperative(im -> im
                            .bulkhead("aopHotSwap", b -> b.balanced())
                            .bulkhead("aopRetune", b -> b.balanced().maxConcurrentCalls(5)))
                    .build();
        }

        @Bean
        public InqElement aopHotSwap(InqRuntime runtime) {
            return (InqElement) runtime.imperative().bulkhead("aopHotSwap");
        }

        @Bean
        public InqElement aopRetune(InqRuntime runtime) {
            return (InqElement) runtime.imperative().bulkhead("aopRetune");
        }

        @Bean
        public SwapService swapService() {
            return new SwapService();
        }

        @Bean
        public RetuneService retuneService() {
            return new RetuneService();
        }
    }

    @Service
    static class SwapService {
        @InqBulkhead("aopHotSwap")
        public String call(String item) {
            return "swap:" + item;
        }
    }

    @Service
    static class RetuneService {
        @InqBulkhead("aopRetune")
        public String call(String item) {
            return "retune:" + item;
        }
    }

    @Autowired
    SwapService swapService;

    @Autowired
    RetuneService retuneService;

    @Autowired
    InqRuntime runtime;

    @Test
    void runtime_strategy_swap_is_observable_through_the_spring_aop_path() {
        // What is to be tested: the SwapService's @InqBulkhead-annotated method runs through
        // the aspect and the bulkhead while the runtime swaps the bulkhead's strategy.
        // After the swap, calls go through the new strategy without a bean refresh.
        // Why successful: snapshot.strategy() reflects the new type and post-swap calls still
        // return their results.
        // Why important: this is the integration-level pin that REFACTORING.md describes as
        // "Bulkhead-Strategy wird zur Laufzeit ausgetauscht … nachfolgender Aufruf reflektiert
        // die neue Strategy". A regression in the registry → aspect → bulkhead handle chain
        // would silently keep using the old strategy after a runtime patch.

        @SuppressWarnings("unchecked")
        eu.inqudium.imperative.bulkhead.InqBulkhead<Void, Object> bh =
                (eu.inqudium.imperative.bulkhead.InqBulkhead<Void, Object>)
                        runtime.imperative().bulkhead("aopHotSwap");

        assertThat(swapService.call("warm")).isEqualTo("swap:warm");
        assertThat(bh.snapshot().strategy()).isInstanceOf(SemaphoreStrategyConfig.class);

        runtime.update(u -> u.imperative(im -> im.bulkhead("aopHotSwap", b -> b
                .codel(c -> c.targetDelay(Duration.ofMillis(50))
                        .interval(Duration.ofMillis(500))))));

        assertThat(bh.snapshot().strategy()).isInstanceOf(CoDelStrategyConfig.class);
        assertThat(swapService.call("post-swap")).isEqualTo("swap:post-swap");
    }

    @Test
    void runtime_max_concurrent_calls_retune_is_observable_through_the_aspect() {
        // Pinning that an in-place limit re-tune (the most common runtime update) is also
        // honoured by a Spring-managed service holding a permit through the aspect path.
        // Uses a separate bulkhead so the strategy-swap test in this class cannot interfere.

        @SuppressWarnings("unchecked")
        eu.inqudium.imperative.bulkhead.InqBulkhead<Void, Object> bh =
                (eu.inqudium.imperative.bulkhead.InqBulkhead<Void, Object>)
                        runtime.imperative().bulkhead("aopRetune");

        // Force a hot transition with a no-op call so availablePermits reads from the
        // strategy thereafter (cold reads from snapshot, hot reads from strategy — both
        // give the same answer for Semaphore but it makes the test resilient to future
        // accessor changes).
        retuneService.call("warm");
        assertThat(bh.availablePermits()).isEqualTo(5);

        runtime.update(u -> u.imperative(im -> im
                .bulkhead("aopRetune", b -> b.maxConcurrentCalls(13))));

        assertThat(bh.availablePermits()).isEqualTo(13);
    }
}
