package eu.inqudium.aspect.pipeline.integration.perlayer;

import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import eu.inqudium.core.pipeline.LayerAction;

import java.lang.reflect.Method;

/**
 * Logging layer — only active on methods annotated with {@link Logged}.
 * Middle layer (order 20).
 */
public class LogLayer implements AspectLayerProvider<Object> {

    @Override
    public String layerName() {
        return "LOG";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean canHandle(Method method) {
        return method.isAnnotationPresent(Logged.class);
    }

    @Override
    public LayerAction<Void, Object> layerAction() {
        return (chainId, callId, arg, next) -> next.execute(chainId, callId, arg);
    }
}
