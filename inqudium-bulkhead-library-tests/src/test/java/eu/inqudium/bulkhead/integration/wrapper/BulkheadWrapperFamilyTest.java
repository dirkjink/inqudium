package eu.inqudium.bulkhead.integration.wrapper;

import eu.inqudium.aspect.pipeline.AspectPipelineTerminal;
import eu.inqudium.aspect.pipeline.ElementLayerProvider;
import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.SyncPipelineTerminal;
import eu.inqudium.core.pipeline.proxy.InqProxyFactory;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wrapper- and proxy-family coverage with a real bulkhead (audit finding 2.17.4).
 *
 * <p>Before ADR-033 these tests had to use synthetic {@code LayerAction} lambdas because
 * {@code InqBulkhead} did not implement {@link InqDecorator}; the audit tracked this as a
 * coverage gap. After the decorator-bridge stages, every wrapper family and every proxy
 * construction path can take the bulkhead end-to-end. One test per family is enough — the
 * structural compatibility is the property being pinned, not the per-family permit logic
 * (which lives in the bulkhead's own dedicated tests).
 *
 * <p>Async pendants ({@code AsyncSupplierWrapper} and friends, {@code AsyncPipelineTerminal},
 * the hybrid terminals) are deliberately out of scope: ADR-033 carves async out for a
 * dedicated future ADR. A test that tried to construct an async wrapper around an
 * {@code InqBulkhead} would not compile today, and that is the documented limitation.
 */
@DisplayName("Bulkhead wrapper- and proxy-family coverage")
class BulkheadWrapperFamilyTest {

    /**
     * Convenience: build a runtime with a single balanced bulkhead and yield the handle.
     */
    @SuppressWarnings("unchecked")
    private static <A, R> InqBulkhead<A, R> newBulkhead(InqRuntime runtime, String name) {
        return (InqBulkhead<A, R>) runtime.imperative().bulkhead(name);
    }

    @Nested
    @DisplayName("decorator factories on InqBulkhead")
    class DecoratorFactories {

        @Test
        void supplier_wrapper_runs_the_supplier_through_the_bulkhead() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                InqBulkhead<Void, String> bh = newBulkhead(runtime, "inventory");
                Supplier<String> protected_ = bh.decorateSupplier(() -> "ok");

                assertThat(protected_.get()).isEqualTo("ok");
            }
        }

        @Test
        void function_wrapper_runs_the_function_through_the_bulkhead() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                InqBulkhead<String, Integer> bh = newBulkhead(runtime, "inventory");
                Function<String, Integer> protected_ = bh.decorateFunction(Integer::parseInt);

                assertThat(protected_.apply("42")).isEqualTo(42);
            }
        }

        @Test
        void runnable_wrapper_runs_the_runnable_through_the_bulkhead() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                InqBulkhead<Void, Void> bh = newBulkhead(runtime, "inventory");
                int[] counter = {0};
                Runnable protected_ = bh.decorateRunnable(() -> counter[0]++);

                protected_.run();
                protected_.run();

                assertThat(counter[0]).isEqualTo(2);
            }
        }

        @Test
        void callable_wrapper_runs_the_callable_through_the_bulkhead() throws Exception {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                InqBulkhead<Void, String> bh = newBulkhead(runtime, "inventory");
                Callable<String> protected_ = bh.decorateCallable(() -> "callable-ok");

                assertThat(protected_.call()).isEqualTo("callable-ok");
            }
        }

        @Test
        void joinpoint_wrapper_runs_the_executor_through_the_bulkhead() throws Throwable {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                InqBulkhead<Void, String> bh = newBulkhead(runtime, "inventory");
                JoinPointExecutor<String> protected_ = bh.decorateJoinPoint(() -> "joinpoint-ok");

                assertThat(protected_.proceed()).isEqualTo("joinpoint-ok");
            }
        }
    }

    @Nested
    @DisplayName("InqProxyFactory variants with a real bulkhead")
    class ProxyConstruction {

        public interface InventoryService {
            String checkStock(String sku);
        }

        @Test
        void inq_proxy_factory_routes_method_calls_through_the_bulkhead() {
            // What is to be tested: a JDK dynamic proxy built via InqProxyFactory.of(name,
            // bulkhead) routes interface method calls through the real bulkhead's around-
            // advice. Why important: the proxy path is the supported route for users who
            // want resilience without modifying their service implementation.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                InqBulkhead<Void, Object> bh = newBulkhead(runtime, "inventory");
                InventoryService real = sku -> "stock:" + sku;

                InventoryService proxied = InqProxyFactory.of("bh-proxy", bh)
                        .protect(InventoryService.class, real);

                assertThat(proxied.checkStock("SKU-001")).isEqualTo("stock:SKU-001");
                assertThat(bh.concurrentCalls())
                        .as("the permit was released after the proxied call returned")
                        .isZero();
            }
        }

        @Test
        void inq_proxy_factory_with_pipeline_routes_method_calls_through_the_bulkhead() {
            // What is to be tested: InqProxyFactory.of(pipeline) built from an InqPipeline
            // whose single element is the real bulkhead routes method calls correctly.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                InqBulkhead<Void, Object> bh = newBulkhead(runtime, "inventory");
                InqPipeline pipeline = InqPipeline.builder().shield((InqElement) bh).build();

                InventoryService real = sku -> "stock:" + sku;
                InventoryService proxied = InqProxyFactory.of(pipeline)
                        .protect(InventoryService.class, real);

                assertThat(proxied.checkStock("SKU-002")).isEqualTo("stock:SKU-002");
            }
        }
    }

    @Nested
    @DisplayName("SyncPipelineTerminal with a real bulkhead")
    class SyncTerminal {

        @Test
        void sync_pipeline_terminal_executes_join_point_through_the_bulkhead() throws Throwable {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                InqBulkhead<Void, Object> bh = newBulkhead(runtime, "inventory");
                InqPipeline pipeline = InqPipeline.builder().shield((InqElement) bh).build();
                SyncPipelineTerminal terminal = SyncPipelineTerminal.of(pipeline);

                Object result = terminal.execute(() -> "sync-ok");

                assertThat(result).isEqualTo("sync-ok");
                assertThat(bh.concurrentCalls()).isZero();
            }
        }

        @Test
        void sync_pipeline_terminal_decorate_supplier_reuses_chain() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                InqBulkhead<Void, Object> bh = newBulkhead(runtime, "inventory");
                InqPipeline pipeline = InqPipeline.builder().shield((InqElement) bh).build();
                SyncPipelineTerminal terminal = SyncPipelineTerminal.of(pipeline);

                Supplier<Object> chain = terminal.decorateSupplier(() -> "again");

                assertThat(chain.get()).isEqualTo("again");
                assertThat(chain.get()).isEqualTo("again");
                assertThat(bh.concurrentCalls()).isZero();
            }
        }
    }

    @Nested
    @DisplayName("AspectPipelineTerminal with a real bulkhead")
    class AspectTerminal {

        @Test
        void aspect_pipeline_terminal_executes_through_an_element_layer_provider() throws Throwable {
            // Pinning the AspectJ-side terminal end-to-end: the ElementLayerProvider takes
            // the real bulkhead, the aspect terminal exercises it through a JoinPointExecutor
            // shaped like a ProceedingJoinPoint substitute. No AspectJ weaving needed for
            // the structural verification.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                InqBulkhead<Void, Object> bh = newBulkhead(runtime, "inventory");
                ElementLayerProvider provider = new ElementLayerProvider(bh);

                // Verify the provider exposes the bulkhead's identity correctly — this is
                // the structural seam ElementLayerProvider relies on after ADR-033.
                assertThat(provider.layerName()).isEqualTo("BULKHEAD(inventory)");
                assertThat(provider.element()).isSameAs(bh);

                InqPipeline pipeline = InqPipeline.builder().shield((InqElement) bh).build();
                AspectPipelineTerminal terminal = AspectPipelineTerminal.of(pipeline);

                Object result = terminal.execute(() -> "aspect-ok");

                assertThat(result).isEqualTo("aspect-ok");
                assertThat(bh.concurrentCalls()).isZero();
            }
        }
    }
}
