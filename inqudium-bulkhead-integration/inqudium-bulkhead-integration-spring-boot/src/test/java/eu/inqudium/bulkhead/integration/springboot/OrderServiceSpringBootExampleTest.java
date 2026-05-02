package eu.inqudium.bulkhead.integration.springboot;

import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the Spring Boot bulkhead example.
 *
 * <p>The tests exercise the example application — they autowire the proxied
 * {@link OrderService} from the same {@link OrderServiceApplication} that the demo runs, and
 * call its methods directly. Spring's AOP proxy routes annotated invocations through
 * {@link eu.inqudium.spring.InqShieldAspect}, which resolves the bulkhead by name from the
 * {@link eu.inqudium.core.element.InqElementRegistry InqElementRegistry} populated
 * automatically by {@link eu.inqudium.spring.boot.InqAutoConfiguration}; the test code does
 * not build a runtime, a registry, an aspect, or a proxy by hand — the point of the example,
 * and the point of the test class, is that all of that is Boot's job.
 *
 * <p>The Spring infrastructure is {@link SpringBootTest @SpringBootTest} pointing at
 * {@link OrderServiceApplication} as the source of beans. No inner {@code @Configuration}
 * class is declared on this test, so the {@code TestTypeExcludeFilter} caveat documented in
 * {@code CLAUDE.md} (and in {@code BulkheadSpringBootIntegrationTest} in the library-tests
 * module) does not apply: the {@code @SpringBootApplication} lives in {@code src/main/java}
 * and is referenced explicitly via {@code classes = OrderServiceApplication.class}, so the
 * filter has nothing test-internal to walk through. {@link Nested @Nested} grouping is
 * therefore safe here.
 *
 * <h3>Shared application context across test methods</h3>
 * <p>{@link SpringBootTest @SpringBootTest} caches the {@link
 * org.springframework.context.ApplicationContext ApplicationContext} between test methods in
 * the same class for performance. The {@link InqRuntime} bean — and therefore the bulkhead
 * named {@link BulkheadConfig#BULKHEAD_NAME} — is consequently shared across every test in
 * this class; each test asserts that {@code availablePermits()} returns to the configured
 * limit (two) before it ends, so subsequent tests start from a clean baseline. This mirrors
 * the AspectJ-native and plain-Spring modules' shared-strategy approach: a regression that
 * left a permit dangling would surface as a deterministic failure on a follow-up test.
 *
 * <h3>Lifecycle group: intentionally disabled</h3>
 * <p>The function-based and proxy-based modules pin a "close one runtime, build a fresh one"
 * lifecycle property; that property does not translate idiomatically to the Spring Boot
 * style, because the runtime's lifecycle is owned by the application context — close-and-
 * rebuild inside a single test method would mean tearing down and rebuilding the entire
 * Spring context. The lifecycle group below documents that difference with an explicit
 * {@link Disabled} marker rather than failing tests; the application-context lifecycle
 * property (Spring Boot's context shutdown calls {@code runtime.close()}) is pinned by the
 * library-tests module's {@code BulkheadSpringBootShutdownTest} as a library safety net.
 */
@SpringBootTest(classes = OrderServiceApplication.class)
@DisplayName("Spring Boot bulkhead example")
class OrderServiceSpringBootExampleTest {

    @Autowired
    OrderService service;

    @Autowired
    InqRuntime runtime;

    @SuppressWarnings("unchecked")
    private InqBulkhead<Object, Object> bulkhead() {
        return (InqBulkhead<Object, Object>) runtime.imperative()
                .bulkhead(BulkheadConfig.BULKHEAD_NAME);
    }

    @BeforeEach
    void assert_baseline_permits() {
        // Given: a shared bulkhead the previous test method must have released cleanly.
        // If a regression leaks a permit, this assertion deterministically points at the
        // first follow-up test rather than masking the leak in a later, unrelated assertion.
        assertThat(bulkhead().availablePermits())
                .as("bulkhead must start every test at the configured limit")
                .isEqualTo(2);
    }

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        void place_order_succeeds_through_the_bulkhead() {
            // Given: a Spring Boot-managed OrderService bean whose @InqBulkhead-annotated
            // methods have been wrapped by Spring AOP at instantiation time, with the
            // InqShieldAspect bean and the bulkhead lookup wired by InqAutoConfiguration

            // When: a single order is placed through the proxied bean
            String result = service.placeOrder("Widget");

            // Then: the original method's reply propagates back unchanged through the proxy
            assertThat(result).isEqualTo("ordered:Widget");
        }

        @Test
        void place_order_releases_the_permit_after_each_call() {
            // What is to be tested: the bulkhead releases the acquired permit at the end of
            // every proxied sync call, so sequential calls never deplete the permit pool.
            // How will the test be deemed successful and why: availablePermits() reads two
            // (the configured limit) before and after each call. If the aspect's sync chain
            // failed to release the permit on the synchronous return path, the count would
            // drop monotonically.
            // Why is it important: a leaked permit on the happy path is the most
            // user-impacting class of bulkhead defect — the protection mechanism turns into a
            // cliff for every subsequent caller. Spring Boot's auto-configuration adds the
            // registry-population and aspect-registration steps on top of the plain-Spring
            // setup; this test pins that those auto-config steps do not perturb the
            // bulkhead's release contract.
            InqBulkhead<Object, Object> bh = bulkhead();

            // Given: a fully-released bulkhead at the configured limit (asserted in @BeforeEach)

            // When: the proxied sync method is invoked multiple times sequentially
            for (int i = 0; i < 5; i++) {
                service.placeOrder("item-" + i);

                // Then: the permit count returns to two after every call
                assertThat(bh.availablePermits())
                        .as("after call %d", i)
                        .isEqualTo(2);
            }
        }

        @Test
        void async_place_order_succeeds_through_the_bulkhead() {
            // Given: a Spring Boot-managed OrderService bean whose @InqBulkhead-annotated
            // async methods have been wrapped by Spring AOP. InqShieldAspect reads the
            // CompletionStage return type and dispatches through the async chain.

            // When: a single async order is placed through the proxied bean
            String result = service.placeOrderAsync("Apple")
                    .toCompletableFuture().join();

            // Then: the original method's reply propagates back unchanged and the permit
            // has returned to the pool by the time the stage completes
            assertThat(result).isEqualTo("async-ordered:Apple");
            assertThat(bulkhead().availablePermits()).isEqualTo(2);
        }

        @Test
        void async_place_order_releases_the_permit_after_each_call() {
            // What is to be tested: the aspect's async chain releases the acquired permit on
            // stage completion, so sequential async calls never deplete the permit pool. The
            // async release fires from the bulkhead's whenComplete callback rather than from
            // a finally clause, so it earns its own coverage even though the user-visible
            // property mirrors the sync case.
            // How will the test be deemed successful and why: availablePermits() reads two
            // before and after every joined async call. If the aspect's async dispatch
            // swallowed the whenComplete release callback, the count would drop monotonically.
            // Why is it important: a leaked permit on the async happy path is just as
            // user-impacting as on the sync path; it would silently throttle every caller
            // after the pool drains. ADR-020's release contract requires the callback fires
            // on both success and failure terminations, regardless of the dispatch mechanism.
            InqBulkhead<Object, Object> bh = bulkhead();

            // Given: a fully-released bulkhead at the configured limit (asserted in @BeforeEach)

            // When: the proxied async method is invoked multiple times sequentially, joining
            // each stage before the next call
            for (int i = 0; i < 5; i++) {
                service.placeOrderAsync("item-" + i)
                        .toCompletableFuture().join();

                // Then: the permit count returns to two after every joined stage
                assertThat(bh.availablePermits())
                        .as("after async call %d", i)
                        .isEqualTo(2);
            }
        }
    }

    @Nested
    @DisplayName("Saturation")
    class Saturation {

        @Test
        void concurrent_calls_above_the_limit_are_rejected_with_InqBulkheadFullException() throws InterruptedException {
            // What is to be tested: when both permits are held by concurrent in-flight
            // proxied calls, a third synchronous call cannot acquire a permit and is rejected
            // with InqBulkheadFullException — the same contract the bulkhead enforces under
            // direct decoration also holds when the bulkhead sits behind the Spring AOP proxy
            // assembled by Spring Boot's auto-configuration.
            // How will the test be deemed successful and why: two virtual-thread holders
            // enter placeOrderHolding through the proxied bean and decrement their acquired
            // latches; a third proxied call from the main thread is rejected synchronously;
            // both holders complete cleanly once the release latch fires.
            // Why is it important: saturation rejection is the bulkhead's reason to exist — a
            // regression here means either auto-configuration failed to register the
            // bulkhead in the registry (no rejection at all) or the proxy or aspect re-
            // wrapped the rejection type (Spring AOP itself can wrap exceptions through
            // reflective invocation), breaking the user-facing contract.
            InqBulkhead<Object, Object> bh = bulkhead();

            CountDownLatch holderAcquired1 = new CountDownLatch(1);
            CountDownLatch holderAcquired2 = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<Throwable> holderErrors = new ArrayList<>();

            // Given: two virtual threads each holding a permit through the proxied bean
            Thread holder1 = Thread.startVirtualThread(() -> {
                try {
                    service.placeOrderHolding(holderAcquired1, release);
                } catch (Throwable t) {
                    holderErrors.add(t);
                }
            });
            Thread holder2 = Thread.startVirtualThread(() -> {
                try {
                    service.placeOrderHolding(holderAcquired2, release);
                } catch (Throwable t) {
                    holderErrors.add(t);
                }
            });

            assertThat(holderAcquired1.await(5, TimeUnit.SECONDS))
                    .as("holder 1 must enter the body").isTrue();
            assertThat(holderAcquired2.await(5, TimeUnit.SECONDS))
                    .as("holder 2 must enter the body").isTrue();

            try {
                // When / Then: a third sync call through the proxied bean is rejected
                // synchronously with the bulkhead's own exception, unwrapped through Spring AOP
                assertThatThrownBy(() -> service.placeOrder("Saturated"))
                        .isInstanceOf(InqBulkheadFullException.class);
            } finally {
                release.countDown();
                holder1.join();
                holder2.join();
            }

            assertThat(holderErrors)
                    .as("holders must release without errors").isEmpty();
            assertThat(bh.availablePermits())
                    .as("permits return to the configured limit after holders release")
                    .isEqualTo(2);
        }

        @Test
        void concurrent_async_calls_above_the_limit_are_rejected_through_a_failed_stage() {
            // What is to be tested: when both permits are held by in-flight async calls (the
            // permits were acquired synchronously on the calling thread when the proxied
            // async method returned its still-pending stage), a third async call cannot
            // acquire a permit and is rejected with InqBulkheadFullException. Channel detail:
            // InqShieldAspect's async dispatch wraps any synchronous throw on the async path
            // into a failed CompletionStage via CompletableFuture.failedFuture rather than
            // letting it propagate to the caller. The exception is the same; the surface
            // differs from the function-based decoration path, where the throw propagates
            // synchronously, and matches the proxy-based, AspectJ-native, and plain-Spring
            // examples' behaviour.
            // How will the test be deemed successful and why: two stage holders each consume
            // a permit; the third proxied async call returns a CompletionStage that, when
            // joined, throws CompletionException whose cause is InqBulkheadFullException.
            // After releasing the holders, both permits return to the pool.
            // Why is it important: this test pins both halves of the Spring Boot
            // integration's async-saturation contract — that the bulkhead actually rejects,
            // and that the rejection surfaces through the failed-stage channel. A regression
            // to either half (no rejection, or a synchronous throw escaping the aspect's
            // error normalization) would break the example's documented contract.
            InqBulkhead<Object, Object> bh = bulkhead();

            CompletableFuture<Void> release = new CompletableFuture<>();

            // Given: two in-flight async holders, each holding a permit while their stages
            // remain pending
            CompletionStage<String> holder1 = service.placeOrderHoldingAsync(release);
            CompletionStage<String> holder2 = service.placeOrderHoldingAsync(release);

            assertThat(bh.concurrentCalls())
                    .as("both async holders must hold a permit synchronously")
                    .isEqualTo(2);
            assertThat(bh.availablePermits()).isZero();

            try {
                // When: a third async call attempts to acquire
                CompletionStage<String> rejected = service.placeOrderAsync("Saturated");

                // Then: the rejection arrives through the failed-stage channel
                assertThatThrownBy(() -> rejected.toCompletableFuture().join())
                        .isInstanceOf(CompletionException.class)
                        .hasCauseInstanceOf(InqBulkheadFullException.class);
            } finally {
                release.complete(null);
                holder1.toCompletableFuture().join();
                holder2.toCompletableFuture().join();
            }

            assertThat(bh.availablePermits())
                    .as("permits return to the configured limit after holders release")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Shared strategy")
    class SharedStrategy {

        @Test
        void sync_and_async_calls_share_the_same_bulkhead_strategy_through_spring_boot() {
            // What is to be tested: the proxy's sync and async dispatch paths route through
            // the same bulkhead instance and therefore the same permit pool. A sync hold
            // consumes one permit; a concurrent async call (also routed through the proxy)
            // observes one available permit and acquires successfully (since
            // maxConcurrentCalls is two). Both paths read and update the same
            // concurrentCalls count.
            // How will the test be deemed successful and why: while a sync holder is in
            // flight (concurrentCalls == 1), an async call through the proxied bean is
            // admitted and returns its value; concurrentCalls reads two while both are
            // mid-flight, then drops back to zero after both release. If the integration ever
            // wired sync and async dispatch to separate bulkheads, the async call would
            // observe two free permits regardless of the sync holder, and the count would
            // never read two simultaneously.
            // Why is it important: the function-based, proxy-based, AspectJ-native, and
            // plain-Spring examples' SharedStrategy tests pin the shared-strategy property at
            // their respective surfaces; this test pins the same property at the Spring Boot
            // dispatch surface. The Spring Boot integration is a different surface that could
            // regress independently — for example, if InqAutoConfiguration ever produced two
            // registry entries for the same name (because both a user @Bean InqElement and an
            // auto-discovered handle resolved separately), or if InqShieldAspect's per-Method
            // cache held a stale lookup against a separate bulkhead — even if the underlying
            // decorate APIs continued to share their strategy. ADR-033's
            // one-bulkhead-two-pipeline-shapes property is what InqShieldAspect's hybrid
            // sync/async dispatch depends on; pinning it at the Boot dispatch surface is what
            // guarantees the auto-configured integration honors that property end-to-end.
            InqBulkhead<Object, Object> bh = bulkhead();

            CountDownLatch holderAcquired = new CountDownLatch(1);
            CountDownLatch syncRelease = new CountDownLatch(1);
            List<Throwable> holderErrors = new ArrayList<>();

            // Given: one virtual-thread sync holder occupies one permit through the proxied
            // bean
            Thread holder = Thread.startVirtualThread(() -> {
                try {
                    service.placeOrderHolding(holderAcquired, syncRelease);
                } catch (Throwable t) {
                    holderErrors.add(t);
                }
            });

            try {
                assertThat(holderAcquired.await(5, TimeUnit.SECONDS))
                        .as("sync holder must enter the body").isTrue();
                assertThat(bh.concurrentCalls())
                        .as("sync holder consumed one permit on the shared strategy")
                        .isEqualTo(1);

                // When: an async holding call enters in parallel through the same proxied
                // bean
                CompletableFuture<Void> asyncRelease = new CompletableFuture<>();
                CompletionStage<String> asyncHolder =
                        service.placeOrderHoldingAsync(asyncRelease);

                // Then: the async permit was acquired against the same pool — both paths
                // mid-flight pushes the count to two
                assertThat(bh.concurrentCalls())
                        .as("sync and async holders share one strategy through Spring Boot")
                        .isEqualTo(2);
                assertThat(bh.availablePermits()).isZero();

                // When: both paths release
                asyncRelease.complete(null);
                String asyncResult = asyncHolder.toCompletableFuture().join();
                syncRelease.countDown();
                holder.join();

                // Then: the shared pool drains back to the configured limit
                assertThat(asyncResult).isEqualTo("async-released");
                assertThat(holderErrors)
                        .as("sync holder must release without errors").isEmpty();
                assertThat(bh.concurrentCalls()).isZero();
                assertThat(bh.availablePermits()).isEqualTo(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                syncRelease.countDown();
            }
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @Disabled("""
                The runtime is owned by the Spring Boot application context — its lifecycle is \
                the context's lifecycle. A close-and-rebuild test inside a single test method \
                would mean tearing down and rebuilding the entire ApplicationContext, which \
                is heavy Spring-specific machinery rather than application-level resilience \
                behaviour. The runtime-level lifecycle property is pinned by the function- \
                based and proxy-based example modules; the application-context lifecycle \
                property (Spring Boot's context shutdown calls runtime.close()) is pinned by \
                inqudium-bulkhead-library-tests' BulkheadSpringBootShutdownTest as a library \
                safety net.""")
        void the_runtime_can_be_closed_and_a_fresh_one_built_in_the_same_test_class() {
            // Intentionally empty — see @Disabled reason above. The placeholder method exists
            // so the lifecycle group is documented in the test report instead of silently
            // missing; a reader scanning the suite sees the explicit decision rather than a
            // gap.
        }
    }
}
