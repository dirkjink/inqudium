package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;

import java.util.Objects;

/**
 * Adapts any {@link InqElement} that implements {@link InqAsyncDecorator} to
 * the asynchronous aspect pipeline as an {@link AsyncAspectLayerProvider}.
 *
 * <p>Works with every imperative resilience element (bulkhead, retry,
 * timeout, circuit-breaker, …) — the only requirement is that the element
 * implements both {@link InqElement} (for name and type) and
 * {@link InqAsyncDecorator InqAsyncDecorator&lt;Void, Object&gt;} (for
 * the two-phase async around-advice).</p>
 *
 * <p>The constructor uses an intersection type bound to enforce this at
 * compile time. The layer name is derived automatically from the element's
 * {@link InqElement#elementType() type} and
 * {@link InqElement#name() name}, e.g. {@code "BULKHEAD(paymentService)"}
 * or {@code "RETRY(orderApi)"}.</p>
 *
 * <h3>Method filtering</h3>
 * <p>{@link #canHandle} uses the {@link AsyncAspectLayerProvider} default,
 * which accepts only methods returning
 * {@link java.util.concurrent.CompletionStage}. For synchronous methods,
 * use {@link ElementLayerProvider} instead.</p>
 *
 * <h3>Usage</h3>
 * <p>The async aspect counterpart accepts any element implementing {@link InqAsyncDecorator}.
 * Note that the imperative bulkhead does <em>not</em> currently implement
 * {@code InqAsyncDecorator} — ADR-033 carves the asynchronous bulkhead out for a dedicated
 * future ADR. Until that ADR lands, async pipelines compose the components that already
 * speak the async contract (retry, time-limiter, etc.) and leave the bulkhead to the
 * synchronous side via {@link ElementLayerProvider}:</p>
 * <pre>{@code
 * InqRuntime runtime = Inqudium.configure()
 *         .imperative(im -> im
 *                 .retry("paymentRetry", r -> r.attempts(3)))
 *         .build();
 *
 * InqElement paymentRetry = (InqElement) runtime.imperative().retry("paymentRetry");
 *
 * // Standard ordering — derived from InqElementType.defaultPipelineOrder()
 * @Aspect
 * public class AsyncResilienceAspect extends AbstractAsyncPipelineAspect {
 *     public AsyncResilienceAspect() {
 *         super(List.of(
 *             new AsyncElementLayerProvider(paymentRetry)
 *         ));
 *     }
 * }
 *
 * // Explicit-order override
 * super(List.of(
 *     new AsyncElementLayerProvider(paymentRetry, 150)
 * ));
 * }</pre>
 *
 * @see ElementLayerProvider
 * @since 0.8.0
 */
public final class AsyncElementLayerProvider implements AsyncAspectLayerProvider<Object> {

    private final InqElement element;
    private final AsyncLayerAction<Void, Object> asyncLayerAction;
    private final int order;
    private final String layerName;

    /**
     * Creates an async layer provider from the given resilience element.
     *
     * <p>The intersection type bound {@code <E extends InqElement &
     * InqAsyncDecorator<Void, Object>>} guarantees at compile time that the
     * element provides both identity (name, type) and async around-advice.</p>
     *
     * @param element the resilience element to adapt
     * @param order   the pipeline priority (lower = outermost wrapper);
     *                overrides the element type's
     *                {@link InqElementType#defaultPipelineOrder() default}
     * @param <E>     intersection of {@link InqElement} and
     *                {@link InqAsyncDecorator InqAsyncDecorator&lt;Void, Object&gt;}
     * @throws NullPointerException if element is null
     */
    public <E extends InqElement & InqAsyncDecorator<Void, Object>> AsyncElementLayerProvider(
            E element, int order) {
        Objects.requireNonNull(element, "Element must not be null");
        this.element = element;
        this.asyncLayerAction = element::executeAsync;
        this.order = order;
        this.layerName = element.elementType().name()
                + "(" + element.name() + ")";
    }

    /**
     * Creates an async layer provider using the element type's
     * {@link InqElementType#defaultPipelineOrder() default order}.
     *
     * <p>This is the preferred constructor for the standard resilience
     * ordering. Use the two-arg constructor to override the position.</p>
     *
     * @param element the resilience element to adapt
     * @param <E>     intersection of {@link InqElement} and
     *                {@link InqAsyncDecorator InqAsyncDecorator&lt;Void, Object&gt;}
     * @throws NullPointerException if element is null
     */
    public <E extends InqElement & InqAsyncDecorator<Void, Object>> AsyncElementLayerProvider(
            E element) {
        this(element, element.elementType().defaultPipelineOrder());
    }

    /**
     * Creates an async layer provider using the order defined by the given
     * {@link eu.inqudium.core.pipeline.PipelineOrdering} profile.
     *
     * <pre>{@code
     * PipelineOrdering r4j = PipelineOrdering.resilience4j();
     * new AsyncElementLayerProvider(bulkhead, r4j)
     * new AsyncElementLayerProvider(retry, r4j)
     * }</pre>
     *
     * @param element  the resilience element to adapt
     * @param ordering the ordering profile to derive the priority from
     * @param <E>      intersection of {@link InqElement} and
     *                 {@link InqAsyncDecorator InqAsyncDecorator&lt;Void, Object&gt;}
     * @throws NullPointerException if element or ordering is null
     */
    public <E extends InqElement & InqAsyncDecorator<Void, Object>> AsyncElementLayerProvider(
            E element, eu.inqudium.core.pipeline.PipelineOrdering ordering) {
        this(element, Objects.requireNonNull(ordering, "Ordering must not be null")
                .orderFor(element.elementType()));
    }

    @Override
    public String layerName() {
        return layerName;
    }

    @Override
    public int order() {
        return order;
    }

    /**
     * Returns the element's two-phase async around-advice as an
     * {@link AsyncLayerAction}.
     *
     * <p>The method reference is captured once at construction time — no
     * per-call allocation.</p>
     */
    @Override
    public AsyncLayerAction<Void, Object> asyncLayerAction() {
        return asyncLayerAction;
    }

    // canHandle(Method) — inherited from AsyncAspectLayerProvider default:
    // returns true only for methods returning CompletionStage.

    /**
     * Returns the underlying resilience element.
     *
     * <p>Useful for diagnostics, metrics polling, or runtime reconfiguration.</p>
     *
     * @return the wrapped element, never {@code null}
     */
    public InqElement element() {
        return element;
    }
}
