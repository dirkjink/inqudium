package eu.inqudium.aspect.pipeline.integration;

import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import eu.inqudium.aspect.pipeline.integration.perlayer.Logged;
import eu.inqudium.core.pipeline.LayerAction;

import java.lang.reflect.Method;

/**
 * Logging layer — records chain/call IDs on entry and exit.
 * Middle layer (order 20).
 *
 * <p>Applies to methods annotated with {@link Resilient} (via
 * {@link ResilienceAspect}) or {@link Logged} (via per-layer aspect).</p>
 */
public class LoggingLayer implements AspectLayerProvider<Object> {
    private final boolean enableConsolePrint;

    public LoggingLayer() {
        this(false);
    }

    public LoggingLayer(boolean enableConsolePrint) {
        this.enableConsolePrint = enableConsolePrint;
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
    public boolean canHandle(Method method) {
        return method.isAnnotationPresent(Resilient.class)
                || method.isAnnotationPresent(Logged.class);
    }

    @Override
    public LayerAction<Void, Object> layerAction() {
        return (chainId, callId, arg, next) -> {
            if (enableConsolePrint)
                System.out.printf("[chain=%d, call=%d] entering%n", chainId, callId);
            try {
                Object result = next.execute(chainId, callId, arg);
                if (enableConsolePrint)
                    System.out.printf("[chain=%d, call=%d] result=%s%n", chainId, callId, result);
                return result;
            } catch (Exception e) {
                if (enableConsolePrint)
                    System.out.printf("[chain=%d, call=%d] error=%s%n", chainId, callId, e.getMessage());
                throw e;
            }
        };
    }
}
