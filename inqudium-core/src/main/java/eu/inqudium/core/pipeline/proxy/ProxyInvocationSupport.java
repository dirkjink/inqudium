package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.pipeline.InqPipeline;

/**
 * Shared utilities for proxy invocation handlers.
 *
 * <p>Consolidates logic that would otherwise be duplicated across proxy
 * terminal implementations — in particular, the human-readable summary
 * string used by {@code toString()} on generated proxies.</p>
 *
 * <p>This class is not meant to be instantiated.</p>
 *
 * @since 0.8.0
 */
public final class ProxyInvocationSupport {

    private ProxyInvocationSupport() {
        // Utility class — no instances
    }

    /**
     * Builds a human-readable summary string for a pipeline-backed proxy.
     *
     * <p>The output follows the format
     * {@code "{label}[InterfaceName → TargetClass, N elements: outer → ... → target]"},
     * for example
     * {@code "InqPipelineProxy[MyService → MyServiceImpl, 3 elements: CB → BH → RT → target]"}.
     * For empty pipelines, the element list is replaced with
     * {@code "no elements (pass-through)"}.</p>
     *
     * <p>Used by hybrid pipeline terminals in other packages to produce
     * consistent proxy-{@code toString()} output — the only difference
     * between call-sites is the {@code label} prefix.</p>
     *
     * @param label         the proxy-kind label (e.g. {@code "InqPipelineProxy"} or
     *                      {@code "HybridPipelineProxy"}) — appears at the start of
     *                      the summary string
     * @param interfaceType the interface the proxy implements — its simple name
     *                      appears in the summary
     * @param target        the real target object — its class simple name appears
     *                      in the summary
     * @param pipeline      the pipeline being traversed — its depth and element
     *                      names appear in the summary
     * @return a human-readable summary string (never {@code null})
     */
    public static String buildSummary(String label, Class<?> interfaceType,
                                      Object target, InqPipeline pipeline) {
        StringBuilder sb = new StringBuilder();
        sb.append(label)
                .append('[')
                .append(interfaceType.getSimpleName())
                .append(" → ")
                .append(target.getClass().getSimpleName())
                .append(", ");
        if (pipeline.isEmpty()) {
            sb.append("no elements (pass-through)");
        } else {
            sb.append(pipeline.depth()).append(" elements: ");
            sb.append(pipeline.chain("target",
                    (acc, element) -> element.name() + " → " + acc));
        }
        sb.append(']');
        return sb.toString();
    }
}
