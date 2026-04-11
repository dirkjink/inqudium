package eu.inqudium.aspect.pipeline.example;

import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import eu.inqudium.core.pipeline.LayerAction;

import java.util.List;

/**
 * Timing layer — measures the execution duration of the inner chain
 * (including the core method).
 *
 * <p>Innermost layer (order 30): wraps directly around the actual method
 * execution. The measured duration excludes authorization and logging
 * overhead.</p>
 */
public class TimingLayerProvider implements AspectLayerProvider<Object> {

    private final List<String> trace;

    /**
     * @param trace shared trace list for observing execution order
     */
    public TimingLayerProvider(List<String> trace) {
        this.trace = trace;
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
    public LayerAction<Void, Object> layerAction() {
        return (chainId, callId, arg, next) -> {
            trace.add("timer:start");
            long start = System.nanoTime();
            try {
                return next.execute(chainId, callId, arg);
            } finally {
                long elapsed = System.nanoTime() - start;
                trace.add("timer:stop[" + elapsed / 1000 + "µs]");
            }
        };
    }
}
