package eu.inqudium.aspect.pipeline.example;

import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import eu.inqudium.core.pipeline.LayerAction;

import java.util.List;

/**
 * Logging layer — records entry, exit, and exception events for each
 * method invocation passing through the chain.
 *
 * <p>Middle layer (order 20): sits between authorization (outer) and
 * timing (inner). Only reached if authorization passes.</p>
 */
public class LoggingLayerProvider implements AspectLayerProvider<Object> {

    private final List<String> trace;

    public LoggingLayerProvider() {
        this(null);
    }

    /**
     * @param trace shared trace list for observing execution order
     */
    public LoggingLayerProvider(List<String> trace) {
        this.trace = trace;
    }

    @Override
    public String layerName() {
        return "LOGGING";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public LayerAction<Void, Object> layerAction() {
        return (chainId, callId, arg, next) -> {
            trace.add("log:enter[chain=" + chainId + ",call=" + callId + "]");
            try {
                Object result = next.execute(chainId, callId, arg);
                trace.add("log:exit[result=" + result + "]");
                return result;
            } catch (Exception e) {
                trace.add("log:error[" + e.getMessage() + "]");
                throw e;
            }
        };
    }
}
