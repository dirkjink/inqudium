package eu.inqudium.bulkhead.integration.function;

import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadRollbackTraceEvent;
import eu.inqudium.core.pipeline.Wrapper;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Tiny webshop order service used as the example domain.
 *
 * <p>The class is plain Java in shape — no Inqudium type leaks into the public method
 * signatures, no annotation drives behaviour, no framework hook intercepts calls. Resilience is
 * nevertheless wired in <em>by the service itself</em>: the constructor pulls the named bulkhead
 * from the {@link InqRuntime}, decorates each business method's {@code *Impl} reference, and
 * caches the wrapped functions in private final fields. The public methods are thin delegates
 * that hand their arguments to the cached wrapped functions.
 *
 * <p>This is the function-based pattern's defining shape — there is no AOP container, no
 * interceptor, no DI framework, so the service owns its own resilience wiring. A reader of the
 * class sees the protection topology inline; a maintainer who must reason about which paths are
 * protected and how only needs to read this constructor.
 *
 * <p>The service exposes both call shapes — synchronous methods that return a value directly,
 * and asynchronous methods that return a {@link CompletionStage}. The same bulkhead handle
 * protects both paths: {@link InqBulkhead} implements both the synchronous {@code InqDecorator}
 * contract and the asynchronous {@code InqAsyncDecorator} contract, sharing one strategy and one
 * permit pool across both shapes.
 */
public class OrderService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);

    /**
     * Argument carrier for the {@code placeOrderHolding} wrapped function. The two-latch
     * holding dance does not fit the {@code Function<A, R>} shape directly — a single-field
     * carrier folds the two latches into one argument so {@code decorateFunction} applies
     * uniformly across all four service methods. Public callers never construct one; the
     * public {@code placeOrderHolding(CountDownLatch, CountDownLatch)} method assembles it.
     */
    public record HoldingTicket(CountDownLatch acquired, CountDownLatch release) {
        public HoldingTicket {
            Objects.requireNonNull(acquired, "acquired");
            Objects.requireNonNull(release, "release");
        }
    }

    private final Function<String, String> placeOrder;
    private final Function<HoldingTicket, String> placeOrderHolding;
    private final Function<String, CompletionStage<String>> placeOrderAsync;
    private final Function<CompletableFuture<Void>, CompletionStage<String>> placeOrderHoldingAsync;

    /**
     * Build a self-wrapped order service against the given runtime. The constructor:
     * <ol>
     *   <li>looks up the bulkhead {@link BulkheadConfig#BULKHEAD_NAME} on the runtime,</li>
     *   <li>wraps each {@code *Impl} method via {@link InqBulkhead#decorateFunction
     *       decorateFunction} or {@link InqBulkhead#decorateAsyncFunction decorateAsyncFunction},
     *       caching the wrapped functions as fields,</li>
     *   <li>logs one topology line per wrapped method (chain-id, layer label),</li>
     *   <li>subscribes to the bulkhead's per-component event publisher with handlers that emit
     *       log records at the levels prescribed by sub-step&nbsp;6.C of
     *       {@code REFACTORING_BULKHEAD_LOGGING_AND_RUNTIME_CONFIG.md}: TRACE for
     *       acquire/release, WARN for reject, ERROR for rollback.</li>
     * </ol>
     *
     * <p>Subscriptions are registered on a single bulkhead handle — the runtime stores one
     * component instance under the {@link BulkheadConfig#BULKHEAD_NAME} key, served through every
     * {@code <A, R>} witness, so subscribing once covers all four wrapped methods.
     */
    public OrderService(InqRuntime runtime) {
        InqBulkhead<String, String> syncBulkhead = bulkhead(runtime);
        InqBulkhead<HoldingTicket, String> holdingBulkhead = bulkhead(runtime);
        InqBulkhead<String, String> asyncBulkhead = bulkhead(runtime);
        InqBulkhead<CompletableFuture<Void>, String> asyncHoldingBulkhead = bulkhead(runtime);

        this.placeOrder = syncBulkhead.decorateFunction(this::placeOrderImpl);
        this.placeOrderHolding = holdingBulkhead.decorateFunction(this::placeOrderHoldingImpl);
        this.placeOrderAsync = asyncBulkhead.decorateAsyncFunction(this::placeOrderAsyncImpl);
        this.placeOrderHoldingAsync = asyncHoldingBulkhead.decorateAsyncFunction(
                this::placeOrderHoldingAsyncImpl);

        logTopology("placeOrder", this.placeOrder);
        logTopology("placeOrderHolding", this.placeOrderHolding);
        logTopology("placeOrderAsync", this.placeOrderAsync);
        logTopology("placeOrderHoldingAsync", this.placeOrderHoldingAsync);

        subscribeBulkheadEvents(syncBulkhead);
    }

    /**
     * Places an order for the given item. Synchronous happy-path call — delegates through the
     * cached wrapped function constructed in the constructor.
     */
    public String placeOrder(String item) {
        return placeOrder.apply(item);
    }

    /**
     * Held-permit variant used to demonstrate saturation. The wrapped function counts down the
     * {@code acquired} latch as soon as the body begins (so the caller knows the permit is
     * held) and then waits on {@code release} before returning. Combined with a small bulkhead
     * limit, two concurrent invocations of this method exhaust the available permits and a
     * third call is rejected with
     * {@link eu.inqudium.core.element.bulkhead.InqBulkheadFullException}.
     */
    public String placeOrderHolding(CountDownLatch acquired, CountDownLatch release) {
        return placeOrderHolding.apply(new HoldingTicket(acquired, release));
    }

    /**
     * Places an order asynchronously. Async happy-path call — the returned stage is already
     * complete by the time the caller observes it, modelling a service whose async work has
     * settled by the moment its method returns.
     */
    public CompletionStage<String> placeOrderAsync(String item) {
        return placeOrderAsync.apply(item);
    }

    /**
     * Held-permit async variant used to demonstrate saturation on the async path. The returned
     * stage completes only when {@code release} completes; the bulkhead's permit is held for
     * the entire wait.
     */
    public CompletionStage<String> placeOrderHoldingAsync(CompletableFuture<Void> release) {
        return placeOrderHoldingAsync.apply(release);
    }

    private String placeOrderImpl(String item) {
        return "ordered:" + item;
    }

    private String placeOrderHoldingImpl(HoldingTicket ticket) {
        ticket.acquired().countDown();
        try {
            if (!ticket.release().await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test timeout: holder never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return "released";
    }

    private CompletionStage<String> placeOrderAsyncImpl(String item) {
        return CompletableFuture.completedFuture("async-ordered:" + item);
    }

    private CompletionStage<String> placeOrderHoldingAsyncImpl(CompletableFuture<Void> release) {
        return release.thenApply(ignored -> "async-released");
    }

    /**
     * Log one topology line per wrapped service method. The cast to {@link Wrapper} is safe —
     * both {@link InqBulkhead#decorateFunction} and
     * {@link InqBulkhead#decorateAsyncFunction} return wrapper instances whose runtime types
     * extend {@code AbstractBaseWrapper}, which {@code implements Wrapper<S>}. The log line
     * surfaces the layer label (e.g. {@code "BULKHEAD(orderBh)"}) and the chain-id assigned at
     * the moment the wrapper chain was constructed.
     */
    private static void logTopology(String methodName, Object wrappedFunction) {
        Wrapper<?> w = (Wrapper<?>) wrappedFunction;
        LOG.info("{} protected by {} (chain-id {})",
                methodName, w.layerDescription(), w.chainId());
    }

    /**
     * Subscribe handlers for the four bulkhead event types this example opts into via
     * {@link BulkheadConfig}. Levels follow sub-step&nbsp;6.C decision&nbsp;4: TRACE for the
     * routine acquire/release pair, WARN for rejection (a back-pressure signal callers care
     * about), ERROR for rollback (a librari-internal anomaly that should never occur on a
     * healthy bulkhead).
     */
    private static void subscribeBulkheadEvents(InqBulkhead<?, ?> bulkhead) {
        var publisher = bulkhead.eventPublisher();
        publisher.onEvent(BulkheadOnAcquireEvent.class, e ->
                LOG.trace("Permit acquired on bulkhead '{}' (chain-id {}, concurrent {})",
                        e.getElementName(), e.getChainId(), e.getConcurrentCalls()));
        publisher.onEvent(BulkheadOnReleaseEvent.class, e ->
                LOG.trace("Permit released on bulkhead '{}' (chain-id {}, concurrent {})",
                        e.getElementName(), e.getChainId(), e.getConcurrentCalls()));
        publisher.onEvent(BulkheadOnRejectEvent.class, e ->
                LOG.warn("Permit rejected on bulkhead '{}' (chain-id {}, reason {})",
                        e.getElementName(), e.getChainId(), e.getRejectionReason()));
        publisher.onEvent(BulkheadRollbackTraceEvent.class, e ->
                LOG.error("Permit rolled back on bulkhead '{}' (chain-id {}, cause {})",
                        e.getElementName(), e.getChainId(), e.getErrorType()));
    }

    @SuppressWarnings("unchecked")
    private static <A, R> InqBulkhead<A, R> bulkhead(InqRuntime runtime) {
        return (InqBulkhead<A, R>) runtime.imperative().bulkhead(BulkheadConfig.BULKHEAD_NAME);
    }
}
