package eu.inqudium.aspect.pipeline.integration.perlayer;

import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import eu.inqudium.core.pipeline.LayerAction;

import java.lang.reflect.Method;

/**
 * Timing layer — only active on methods annotated with {@link Timed}.
 * Innermost layer (order 30).
 */
public class TimeLayer implements AspectLayerProvider<Object> {

    @Override
    public String layerName() {
        return "TIME";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public boolean canHandle(Method method) {
        return method.isAnnotationPresent(Timed.class);
    }

    @Override
    public LayerAction<Void, Object> layerAction() {
        return (chainId, callId, arg, next) -> next.execute(chainId, callId, arg);
    }
}
