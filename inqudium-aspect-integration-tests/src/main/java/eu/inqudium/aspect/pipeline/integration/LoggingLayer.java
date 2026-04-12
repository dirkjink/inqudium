package eu.inqudium.aspect.pipeline.integration;

import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import eu.inqudium.core.pipeline.LayerAction;

/**
 * Logging layer — records chain/call IDs on entry and exit.
 * Middle layer (order 20).
 */
public class LoggingLayer implements AspectLayerProvider<Object> {

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
            System.out.printf("[chain=%d, call=%d] entering%n", chainId, callId);
            try {
                Object result = next.execute(chainId, callId, arg);
                System.out.printf("[chain=%d, call=%d] result=%s%n", chainId, callId, result);
                return result;
            } catch (Exception e) {
                System.out.printf("[chain=%d, call=%d] error=%s%n", chainId, callId, e.getMessage());
                throw e;
            }
        };
    }
}
