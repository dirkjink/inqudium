package eu.inqudium.spring.benchmark;

import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import eu.inqudium.core.pipeline.LayerAction;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.reflect.Method;

/**
 * Inqudium {@link AspectLayerProvider} that performs the same trivial work
 * as {@link BaselineSpringAspect}: increment a volatile counter, feed its
 * value to a {@link Blackhole}, then delegate to the next layer.
 *
 * <p>Each instance holds its own counter — a three-layer pipeline therefore
 * has three independent volatile counters, one per layer. This matches the
 * realistic picture where each cross-cutting concern (timing, logging,
 * authorization, ...) tracks its own statistic.</p>
 *
 * <p>{@link #canHandle(Method)} always returns {@code true}, so every
 * {@code @Benchmarked} method participates regardless of its signature.</p>
 */
public class CountingLayerProvider implements AspectLayerProvider<Object> {

    private final String name;
    private final int order;
    private final Blackhole blackhole;

    /**
     * Monotonically incremented per {@code layerAction()} invocation.
     */
    @SuppressWarnings("FieldMayBeFinal")
    private volatile long counter = 0L;

    public CountingLayerProvider(String name, int order, Blackhole blackhole) {
        this.name = name;
        this.order = order;
        this.blackhole = blackhole;
    }

    @Override
    public LayerAction<Void, Object> layerAction() {
        // The lambda captures `this`; volatile-counter field access goes
        // through an implicit getfield/putfield on the captured receiver.
        return (chainId, callId, arg, next) -> {
            long c = ++counter;
            blackhole.consume(c);
            return next.execute(chainId, callId, arg);
        };
    }

    @Override
    public String layerName() {
        return name;
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public boolean canHandle(Method method) {
        return true;
    }

    /**
     * Exposed for post-run inspection — not used by the benchmark hot path.
     */
    public long getCounter() {
        return counter;
    }
}
