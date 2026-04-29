package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.LayerAction;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Adapts any {@link InqElement} that implements {@link InqDecorator} to the
 * synchronous aspect pipeline as an {@link AspectLayerProvider}.
 *
 * <p>Works with every imperative resilience element (bulkhead, retry,
 * timeout, circuit-breaker, …) — the only requirement is that the element
 * implements both {@link InqElement} (for name and type) and
 * {@link InqDecorator InqDecorator&lt;Void, Object&gt;} (for the sync
 * around-advice).</p>
 *
 * <p>The constructor uses an intersection type bound to enforce this at
 * compile time. The layer name is derived automatically from the element's
 * {@link InqElement#elementType() type} and
 * {@link InqElement#name() name}, e.g. {@code "BULKHEAD(paymentService)"}
 * or {@code "RETRY(orderApi)"}.</p>
 *
 * <h3>Method filtering</h3>
 * <p>{@link #canHandle(Method)} excludes methods returning
 * {@link CompletionStage}. Synchronous around-advice releases resources
 * (permits, tokens, etc.) when the method <em>returns</em>, not when the
 * async operation <em>completes</em>. For async methods, use
 * {@link AsyncElementLayerProvider} instead.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ImperativeBulkhead<Void, Object> bulkhead = new ImperativeBulkhead<>(cfg, strategy);
 * ImperativeRetry<Void, Object>    retry    = new ImperativeRetry<>(retryCfg);
 * ImperativeTimeout<Void, Object>  timeout  = new ImperativeTimeout<>(toCfg);
 *
 * // Standard ordering — derived from InqElementType.defaultPipelineOrder():
 * // BULKHEAD(100) → RETRY(400) → TIME_LIMITER(500)
 * super(List.of(
 *     new ElementLayerProvider(bulkhead),
 *     new ElementLayerProvider(retry),
 *     new ElementLayerProvider(timeout)
 * ));
 *
 * // Resilience4j ordering — via PipelineOrdering profile:
 * // RETRY(100) → TIME_LIMITER(400) → BULKHEAD(500)
 * PipelineOrdering r4j = PipelineOrdering.resilience4j();
 * super(List.of(
 *     new ElementLayerProvider(bulkhead, r4j),
 *     new ElementLayerProvider(retry, r4j),
 *     new ElementLayerProvider(timeout, r4j)
 * ));
 *
 * // Single element override — timeout outside retry:
 * // BULKHEAD(100) → TIME_LIMITER(350) → RETRY(400)
 * super(List.of(
 *     new ElementLayerProvider(bulkhead),
 *     new ElementLayerProvider(timeout, 350),
 *     new ElementLayerProvider(retry)
 * ));
 * }</pre>
 *
 * @see AsyncElementLayerProvider
 * @since 0.8.0
 */
public final class ElementLayerProvider implements AspectLayerProvider<Object> {

    private final InqElement element;
    private final LayerAction<Void, Object> layerAction;
    private final int order;
    private final String layerName;

    /**
     * Creates a sync layer provider from the given resilience element.
     *
     * <p>The intersection type bound {@code <E extends InqElement &
     * InqDecorator<Void, Object>>} guarantees at compile time that the
     * element provides both identity (name, type) and sync around-advice.</p>
     *
     * @param element the resilience element to adapt
     * @param order   the pipeline priority (lower = outermost wrapper);
     *                overrides the element type's
     *                {@link InqElementType#defaultPipelineOrder() default}
     * @param <E>     intersection of {@link InqElement} and
     *                {@link InqDecorator InqDecorator&lt;Void, Object&gt;}
     * @throws NullPointerException if element is null
     */
    public <E extends InqElement & InqDecorator<Void, Object>> ElementLayerProvider(
            E element, int order) {
        Objects.requireNonNull(element, "Element must not be null");
        this.element = element;
        this.layerAction = element::execute;
        this.order = order;
        this.layerName = element.elementType().name()
                + "(" + element.name() + ")";
    }

    /**
     * Creates a sync layer provider using the element type's
     * {@link InqElementType#defaultPipelineOrder() default order}.
     *
     * <p>This is the preferred constructor for the standard resilience
     * ordering. Use the two-arg constructor to override the position — for
     * example, to place a timeout outside retry.</p>
     *
     * @param element the resilience element to adapt
     * @param <E>     intersection of {@link InqElement} and
     *                {@link InqDecorator InqDecorator&lt;Void, Object&gt;}
     * @throws NullPointerException if element is null
     */
    public <E extends InqElement & InqDecorator<Void, Object>> ElementLayerProvider(E element) {
        this(element, element.elementType().defaultPipelineOrder());
    }

    /**
     * Creates a sync layer provider using the order defined by the given
     * {@link eu.inqudium.core.pipeline.PipelineOrdering} profile.
     *
     * <pre>{@code
     * PipelineOrdering r4j = PipelineOrdering.resilience4j();
     * new ElementLayerProvider(bulkhead, r4j)
     * new ElementLayerProvider(retry, r4j)
     * }</pre>
     *
     * @param element  the resilience element to adapt
     * @param ordering the ordering profile to derive the priority from
     * @param <E>      intersection of {@link InqElement} and
     *                 {@link InqDecorator InqDecorator&lt;Void, Object&gt;}
     * @throws NullPointerException if element or ordering is null
     */
    public <E extends InqElement & InqDecorator<Void, Object>> ElementLayerProvider(
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
     * Returns the element's synchronous around-advice as a {@link LayerAction}.
     *
     * <p>The method reference is captured once at construction time — no
     * per-call allocation.</p>
     */
    @Override
    public LayerAction<Void, Object> layerAction() {
        return layerAction;
    }

    /**
     * Returns {@code true} for synchronous methods only.
     *
     * <p>Methods returning {@link CompletionStage} are excluded — use
     * {@link AsyncElementLayerProvider} for those.</p>
     *
     * @param method the service method being invoked
     * @return {@code true} if the method does <em>not</em> return a
     * {@link CompletionStage}
     */
    @Override
    public boolean canHandle(Method method) {
        return !CompletionStage.class.isAssignableFrom(method.getReturnType());
    }

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
