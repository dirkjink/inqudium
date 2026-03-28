package eu.inqudium.core.pipeline;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.context.InqContextPropagation;
import eu.inqudium.core.context.InqContextScope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Fluent API for composing multiple resilience elements into a single
 * decoration chain with explicit, validated ordering (ADR-017).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Supplier<Result> resilient = InqPipeline
 *     .of(() -> service.call())
 *     .order(PipelineOrder.INQUDIUM)       // default, can be omitted
 *     .shield(circuitBreakerDecorator)
 *     .shield(retryDecorator)
 *     .decorate();
 *
 * Result result = resilient.get();
 * }</pre>
 *
 * <p>When a {@link PipelineOrder} is set, the {@link #shield} calls can be in any
 * order — the pipeline sorts them. When no order is set, {@link PipelineOrder#INQUDIUM}
 * is the default.
 *
 * <h2>Pipeline responsibilities</h2>
 * <ul>
 *   <li>Sorts elements according to the selected {@link PipelineOrder}.</li>
 *   <li>Generates the {@code callId} (ADR-003) at invocation time.</li>
 *   <li>Orchestrates context propagation (ADR-011) across all elements.</li>
 *   <li>Emits startup warnings for known anti-patterns.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public final class InqPipeline {

    private static final System.Logger LOGGER = System.getLogger(InqPipeline.class.getName());

    private final List<InqDecorator> decorators;
    private final PipelineOrder order;

    private InqPipeline(List<InqDecorator> decorators, PipelineOrder order) {
        this.decorators = List.copyOf(decorators);
        this.order = order;
    }

    /**
     * Starts building a pipeline for the given supplier.
     *
     * @param supplier the operation to protect
     * @param <T>      the result type
     * @return a new pipeline builder
     */
    public static <T> Builder<T> of(Supplier<T> supplier) {
        return new Builder<>(Objects.requireNonNull(supplier, "supplier must not be null"));
    }

    /**
     * Pipeline builder that collects decorators and composes them.
     *
     * @param <T> the result type of the protected operation
     */
    public static final class Builder<T> {

        private final Supplier<T> supplier;
        private final List<InqDecorator> decorators = new ArrayList<>();
        private PipelineOrder order = PipelineOrder.INQUDIUM;

        private Builder(Supplier<T> supplier) {
            this.supplier = supplier;
        }

        /**
         * Adds a resilience element to the pipeline.
         *
         * <p>The actual position in the decoration chain is determined by the
         * {@link PipelineOrder}, not by the order of {@code shield()} calls.
         *
         * @param decorator the element's decorator
         * @return this builder
         */
        public Builder<T> shield(InqDecorator decorator) {
            Objects.requireNonNull(decorator, "decorator must not be null");
            decorators.add(decorator);
            return this;
        }

        /**
         * Sets the pipeline ordering strategy.
         *
         * @param order the ordering to use (default: {@link PipelineOrder#INQUDIUM})
         * @return this builder
         */
        public Builder<T> order(PipelineOrder order) {
            this.order = Objects.requireNonNull(order, "order must not be null");
            return this;
        }

        /**
         * Composes all elements into a single decorated supplier.
         *
         * <p>Elements are sorted according to the selected order. The resulting
         * supplier generates a {@code callId} and activates context propagation
         * on each invocation.
         *
         * @return the decorated supplier
         */
        public Supplier<T> decorate() {
            // Sort decorators according to the pipeline order
            var sorted = new ArrayList<>(decorators);
            sorted.sort(Comparator.comparingInt(d -> order.positionOf(d.getElementType())));

            // Validate for known anti-patterns
            validate(sorted);

            // Build the decoration chain — outermost first (index 0 wraps index 1 wraps ... wraps supplier)
            // We reverse because the first in the sorted list is the outermost,
            // and decoration wraps inside-out.
            Supplier<T> chain = supplier;
            for (int i = sorted.size() - 1; i >= 0; i--) {
                chain = sorted.get(i).decorate(chain);
            }

            // Wrap with callId generation and context propagation
            final Supplier<T> decorated = chain;
            return () -> {
                var callId = UUID.randomUUID().toString();
                try (var scope = InqContextPropagation.activateFor(
                        callId, "pipeline", InqElementType.CACHE /* placeholder — pipeline is not an element */)) {
                    return decorated.get();
                }
            };
        }

        private void validate(List<InqDecorator> sorted) {
            int retryPos = -1;
            int cbPos = -1;
            int tlPos = -1;
            int rlPos = -1;

            for (int i = 0; i < sorted.size(); i++) {
                switch (sorted.get(i).getElementType()) {
                    case RETRY -> retryPos = i;
                    case CIRCUIT_BREAKER -> cbPos = i;
                    case TIME_LIMITER -> tlPos = i;
                    case RATE_LIMITER -> rlPos = i;
                    default -> {} // no validation for other elements
                }
            }

            // Retry outside CircuitBreaker (lower index = outer)
            if (retryPos >= 0 && cbPos >= 0 && retryPos < cbPos) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Pipeline warning: Retry '{0}' is outside CircuitBreaker '{1}'. " +
                        "Retry may attempt to retry against an open circuit breaker. " +
                        "Consider configuring Retry to not retry on InqCallNotPermittedException, " +
                        "or move Retry inside CircuitBreaker.",
                        sorted.get(retryPos).getName(), sorted.get(cbPos).getName());
            }

            // TimeLimiter inside Retry (higher index = inner)
            if (tlPos >= 0 && retryPos >= 0 && tlPos > retryPos) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Pipeline warning: TimeLimiter '{0}' is inside Retry '{1}'. " +
                        "Each retry attempt gets a fresh timeout, but total caller wait time is unbounded.",
                        sorted.get(tlPos).getName(), sorted.get(retryPos).getName());
            }

            // RateLimiter inside Retry
            if (rlPos >= 0 && retryPos >= 0 && rlPos > retryPos) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Pipeline warning: RateLimiter '{0}' is inside Retry '{1}'. " +
                        "Each retry attempt consumes a rate limit permit. " +
                        "Consider moving RateLimiter outside Retry.",
                        sorted.get(rlPos).getName(), sorted.get(retryPos).getName());
            }
        }
    }
}
