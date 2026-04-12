package eu.inqudium.aspect.pipeline.integration.perlayer;

import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import eu.inqudium.core.pipeline.LayerAction;

import java.lang.reflect.Method;

/**
 * Authorization layer — only active on methods annotated with {@link Authorized}.
 * Outermost layer (order 10).
 */
public class AuthLayer implements AspectLayerProvider<Object> {

    @Override
    public String layerName() {
        return "AUTH";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean canHandle(Method method) {
        return method.isAnnotationPresent(Authorized.class);
    }

    @Override
    public LayerAction<Void, Object> layerAction() {
        return (chainId, callId, arg, next) -> next.execute(chainId, callId, arg);
    }
}
