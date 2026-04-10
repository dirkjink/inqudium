package eu.inqudium.imperative.bulkhead;

import eu.inqudium.core.config.InqConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqExecutor;
import eu.inqudium.imperative.bulkhead.config.InqImperativeBulkheadConfig;
import eu.inqudium.imperative.bulkhead.strategy.SemaphoreBulkheadStrategy;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import eu.inqudium.imperative.core.pipeline.InqAsyncExecutor;

/**
 * Imperative bulkhead — limits concurrent calls via pluggable strategies.
 *
 * <p>Extends {@link InqDecorator} to participate directly in the wrapper pipeline.
 * The bulkhead's around-advice (acquire → execute → release) is defined by the
 * {@link InqDecorator#execute} method, and the factory methods inherited from
 * {@link InqDecorator} allow wrapping any functional interface in one line:</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create a bulkhead
 * Bulkhead<Void, String> bh = Bulkhead.of(BulkheadConfig.builder()
 *     .maxConcurrentCalls(10)
 *     .build());
 *
 * // Decorate via Decorator factory methods — fully type-safe
 * Supplier<String> protected = bh.decorateSupplier(() -> inventoryService.check(sku));
 * Callable<String> protected = bh.decorateCallable(() -> readFile(path));
 *
 * // Compose with other decorators
 * Supplier<String> resilient = retry.decorateSupplier(
 *     bh.decorateSupplier(() -> callApi())
 * );
 * }</pre>
 *
 * @param <A> the argument type flowing through the chain
 * @param <R> the return type flowing back through the chain
 * @since 0.4.0
 */
public interface Bulkhead<A, R>
        extends InqDecorator<A, R>,
        InqExecutor<A, R>,
        InqAsyncExecutor<A, R>,
        InqAsyncDecorator<A, R> {

    /**
     * Creates a bulkhead from a general {@link InqConfig} container.
     *
     * @param config the configuration container holding an {@link InqImperativeBulkheadConfig}
     * @param <A>    the argument type
     * @param <R>    the return type
     * @return a new bulkhead instance
     */
    static <A, R> Bulkhead<A, R> of(InqConfig config) {
        return of(config.of(InqImperativeBulkheadConfig.class).orElseThrow());
    }

    /**
     * Creates a bulkhead with the given configuration.
     *
     * <p>Uses a {@link SemaphoreBulkheadStrategy} with the configured
     * maximum concurrent calls.</p>
     *
     * @param config the bulkhead configuration
     * @param <A>    the argument type
     * @param <R>    the return type
     * @return a new bulkhead instance
     */
    static <A, R> Bulkhead<A, R> of(InqImperativeBulkheadConfig config) {
        return new ImperativeBulkhead<>(config, new SemaphoreBulkheadStrategy(config.maxConcurrentCalls()));
    }

    /**
     * Returns the bulkhead configuration.
     */
    InqImperativeBulkheadConfig getConfig();

    /**
     * Returns the number of currently active concurrent calls.
     */
    int getConcurrentCalls();

    /**
     * Returns the number of permits currently available.
     */
    int getAvailablePermits();

    /**
     * {@inheritDoc}
     *
     * <p>Always returns {@link InqElementType#BULKHEAD}.</p>
     */
    @Override
    default InqElementType getElementType() {
        return InqElementType.BULKHEAD;
    }
}
