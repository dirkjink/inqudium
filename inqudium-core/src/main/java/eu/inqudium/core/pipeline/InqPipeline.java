package eu.inqudium.core.pipeline;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqCallIdGenerator;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.context.InqContextPropagation;
import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.exception.InqRuntimeException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Fluent API for composing multiple resilience elements into a single
 * decoration chain with explicit, validated ordering (ADR-017).
 *
 * <p>The pipeline generates a callId and wraps the operation in an {@link InqCall}.
 * This call flows through all decorators — each element reads
 * {@code call.callId()} for event correlation. No thread-local, no hidden state.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Supplier<Result> resilient = InqPipeline
 *     .of(() -> service.call())
 *     .order(PipelineOrder.INQUDIUM)
 *     .shield(circuitBreakerDecorator)
 *     .shield(retryDecorator)
 *     .decorate();
 *
 * Result result = resilient.get();
 * }</pre>
 *
 * @since 0.1.0
 */
public final class InqPipeline {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(InqPipeline.class);

    private InqPipeline() {}

    /**
     * Starts building a pipeline for the given supplier.
     *
     * @param supplier the operation to protect
     * @param <T>      the result type
     * @return a new pipeline builder
     */
    public static <T> Builder<T> of(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier must not be null");
        return new Builder<>(supplier::get);
    }

    /**
     * Starts building a pipeline for the given callable.
     *
     * <p>Checked exceptions from the callable flow naturally through the
     * pipeline's decoration chain and are wrapped in {@link InqRuntimeException}
     * at the {@code Supplier} boundary when {@link Builder#decorate()} is invoked.
     *
     * @param callable the operation to protect
     * @param <T>      the result type
     * @return a new pipeline builder
     */
    public static <T> Builder<T> of(Callable<T> callable) {
        return new Builder<>(Objects.requireNonNull(callable, "callable must not be null"));
    }

    /**
     * Pipeline builder that collects decorators and composes them.
     *
     * @param <T> the result type of the protected operation
     */
    public static final class Builder<T> {

        private final Callable<T> callable;
        private final List<InqDecorator> decorators = new ArrayList<>();
        private PipelineOrder order = PipelineOrder.INQUDIUM;
        private InqCallIdGenerator callIdGenerator = InqCallIdGenerator.uuid();

        private Builder(Callable<T> callable) {
            this.callable = callable;
        }

        /**
         * Adds a resilience element to the pipeline.
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
         * Sets the call ID generator for this pipeline.
         *
         * @param callIdGenerator the generator to use (default: UUID)
         * @return this builder
         */
        public Builder<T> callIdGenerator(InqCallIdGenerator callIdGenerator) {
            this.callIdGenerator = Objects.requireNonNull(callIdGenerator, "callIdGenerator must not be null");
            return this;
        }

        /**
         * Composes all elements into a single decorated supplier.
         *
         * <p>Elements are sorted according to the selected order. On each invocation,
         * the pipeline generates a callId, wraps the supplier in an {@link InqCall},
         * and passes it through the decoration chain. Context propagation is activated
         * around the outermost call.
         *
         * @return the decorated supplier
         */
        public Supplier<T> decorate() {
            var sorted = new ArrayList<>(decorators);
            sorted.sort(Comparator.comparingInt(d -> order.positionOf(d.getElementType())));

            validate(sorted);

            var chain = List.copyOf(sorted);
            final InqCallIdGenerator gen = callIdGenerator;
            final Callable<T> originalCallable = callable;

            return () -> {
                var callId = gen.generate();

                // Build the InqCall chain: Callable flows through all decorators
                InqCall<T> call = InqCall.of(callId, originalCallable);
                for (int i = chain.size() - 1; i >= 0; i--) {
                    call = chain.get(i).decorate(call);
                }

                // Execute with context propagation — Supplier boundary wraps checked exceptions
                final InqCall<T> outermost = call;
                try (var ctxScope = InqContextPropagation.activateFor(
                        callId, "pipeline", InqElementType.CACHE)) {
                    return outermost.execute();
                } catch (InqException ie) {
                    throw ie;
                } catch (RuntimeException re) {
                    LOGGER.error("[{}] pipeline: {}", callId, re.toString());
                    throw re;
                } catch (Exception e) {
                    LOGGER.error("[{}] pipeline: {}", callId, e.toString());
                    throw new InqRuntimeException(callId, "pipeline", null, e);
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
                    default -> {}
                }
            }

            if (retryPos >= 0 && cbPos >= 0 && retryPos < cbPos) {
                LOGGER.warn(
                        "Pipeline warning: Retry '{}' is outside CircuitBreaker '{}'. " +
                        "Retry may attempt to retry against an open circuit breaker. " +
                        "Consider configuring Retry to not retry on InqCallNotPermittedException, " +
                        "or move Retry inside CircuitBreaker.",
                        sorted.get(retryPos).getName(), sorted.get(cbPos).getName());
            }

            if (tlPos >= 0 && retryPos >= 0 && tlPos > retryPos) {
                LOGGER.warn(
                        "Pipeline warning: TimeLimiter '{}' is inside Retry '{}'. " +
                        "Each retry attempt gets a fresh timeout, but total caller wait time is unbounded.",
                        sorted.get(tlPos).getName(), sorted.get(retryPos).getName());
            }

            if (rlPos >= 0 && retryPos >= 0 && rlPos > retryPos) {
                LOGGER.warn(
                        "Pipeline warning: RateLimiter '{}' is inside Retry '{}'. " +
                        "Each retry attempt consumes a rate limit permit. " +
                        "Consider moving RateLimiter outside Retry.",
                        sorted.get(rlPos).getName(), sorted.get(retryPos).getName());
            }
        }
    }
}
