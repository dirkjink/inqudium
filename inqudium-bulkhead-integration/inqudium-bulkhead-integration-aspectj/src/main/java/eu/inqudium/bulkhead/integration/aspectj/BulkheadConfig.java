package eu.inqudium.bulkhead.integration.aspectj;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.InqRuntime;

/**
 * Single point where Inqudium is configured for this example.
 *
 * <p>The configuration is identical to the function-based and proxy-based examples: one
 * bulkhead named {@code orderBh}, the {@code balanced} preset overridden to two concurrent
 * permits. The configuration code does not change between integration styles — the runtime is
 * the same; only the wiring on top differs. This module exercises the runtime through a
 * compile-time-woven aspect rather than by wrapping method references at the call site or
 * by interposing a JDK dynamic proxy.
 */
public final class BulkheadConfig {

    /** The name under which the example's bulkhead is registered. */
    public static final String BULKHEAD_NAME = "orderBh";

    private BulkheadConfig() {
        // utility class
    }

    /**
     * @return a freshly built runtime with a single bulkhead named {@link #BULKHEAD_NAME}.
     *         The runtime is independent of any other call site and must be closed by its
     *         caller — typically via try-with-resources.
     */
    public static InqRuntime newRuntime() {
        return Inqudium.configure()
                .imperative(im -> im.bulkhead(BULKHEAD_NAME,
                        b -> b.balanced().maxConcurrentCalls(2)))
                .build();
    }
}
