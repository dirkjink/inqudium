package eu.inqudium.aspect.pipeline.integration;

import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import eu.inqudium.aspect.pipeline.integration.perlayer.Authorized;
import eu.inqudium.core.pipeline.LayerAction;

import java.lang.reflect.Method;

/**
 * Authorization layer — always grants access in this integration test setup.
 * Outermost layer (order 10).
 *
 * <p>Applies to methods annotated with {@link Resilient} (via
 * {@link ResilienceAspect}) or {@link Authorized} (via per-layer aspect).</p>
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
    public boolean canHandle(Method method) {
        return method.isAnnotationPresent(Resilient.class)
                || method.isAnnotationPresent(Authorized.class);
    }

    @Override
    public LayerAction<Void, Object> layerAction() {
        return (chainId, callId, arg, next) -> next.execute(chainId, callId, arg);
    }
}
