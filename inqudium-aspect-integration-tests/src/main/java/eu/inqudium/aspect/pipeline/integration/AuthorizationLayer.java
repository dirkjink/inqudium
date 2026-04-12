package eu.inqudium.aspect.pipeline.integration;

import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import eu.inqudium.core.pipeline.LayerAction;

/**
 * Authorization layer — always grants access in this integration test setup.
 * Outermost layer (order 10).
 */
public class AuthorizationLayer implements AspectLayerProvider<Object> {

    @Override
    public String layerName() {
        return "AUTHORIZATION";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public LayerAction<Void, Object> layerAction() {
        return (chainId, callId, arg, next) -> next.execute(chainId, callId, arg);
    }
}
