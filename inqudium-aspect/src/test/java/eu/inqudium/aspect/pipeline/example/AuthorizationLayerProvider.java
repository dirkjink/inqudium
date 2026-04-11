package eu.inqudium.aspect.pipeline.example;

import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import eu.inqudium.core.pipeline.LayerAction;

import java.util.List;

/**
 * Authorization layer — checks access rights before allowing the call
 * to proceed down the chain.
 *
 * <p>Outermost layer (order 10): if authorization fails, the chain is
 * short-circuited and no inner layers are invoked.</p>
 */
public class AuthorizationLayerProvider implements AspectLayerProvider<Object> {

    private final List<String> trace;
    private final boolean authorized;

    public AuthorizationLayerProvider() {
        this(null, false);
    }

    /**
     * @param trace      shared trace list for observing execution order
     * @param authorized whether to allow or deny access
     */
    public AuthorizationLayerProvider(List<String> trace, boolean authorized) {
        this.trace = trace;
        this.authorized = authorized;
    }

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
        return (chainId, callId, arg, next) -> {
            trace.add("auth:check");
            if (!authorized) {
                trace.add("auth:denied");
                throw new SecurityException("Access denied");
            }
            trace.add("auth:granted");
            return next.execute(chainId, callId, arg);
        };
    }
}
