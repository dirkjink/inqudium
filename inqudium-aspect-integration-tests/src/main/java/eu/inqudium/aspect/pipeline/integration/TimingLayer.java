package eu.inqudium.aspect.pipeline.integration;

import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import eu.inqudium.aspect.pipeline.integration.perlayer.Timed;
import eu.inqudium.core.pipeline.LayerAction;

import java.lang.reflect.Method;

/**
 * Timing layer — measures execution duration of the core method.
 * Innermost layer (order 30).
 *
 * <p>Applies to methods annotated with {@link Resilient} (via
 * {@link ResilienceAspect}) or {@link Timed} (via per-layer aspect).
 * Demonstrates {@code canHandle}-based method filtering.</p>
 */
public class TimingLayer implements AspectLayerProvider<Object> {

    private final boolean enableConsolePrint;

    public TimingLayer() {
        this(false);
    }

    public TimingLayer(boolean enableConsolePrint) {
        this.enableConsolePrint = enableConsolePrint;
    }

    @Override
    public String layerName() {
        return "TIMING";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public boolean canHandle(Method method) {
        return method.isAnnotationPresent(Resilient.class)
                || method.isAnnotationPresent(Timed.class);
    }

    @Override
    public LayerAction<Void, Object> layerAction() {
        return (chainId, callId, arg, next) -> {
            long start = System.nanoTime();
            try {
                return next.execute(chainId, callId, arg);
            } finally {
                long elapsed = System.nanoTime() - start;
                if (enableConsolePrint)
                    System.out.printf("[chain=%d, call=%d] took %d µs%n",
                            chainId, callId, elapsed / 1000);
            }
        };
    }
}
