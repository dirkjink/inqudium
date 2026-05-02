package eu.inqudium.bulkhead.integration.function;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.snapshot.BulkheadEventConfig;

/**
 * Single point where Inqudium is configured for this example.
 *
 * <p>A reader sees the entire runtime configuration in one place: one bulkhead named
 * {@code orderBh}, the {@code balanced} preset overridden to two concurrent permits.
 * The semaphore strategy is the default of {@code balanced()}; {@code maxConcurrentCalls(2)}
 * is small enough that saturation is observable in tests without parallel pressure.
 *
 * <p>The bulkhead is configured to emit acquire / release / reject / rollback events on its
 * per-component publisher (the four event types covered by sub-step&nbsp;6.C of
 * {@code REFACTORING_BULKHEAD_LOGGING_AND_RUNTIME_CONFIG.md}). The DSL default
 * ({@link BulkheadEventConfig#disabled()}) keeps the hot path event-free; the example opts
 * back in so the {@code OrderService}'s subscribers have something to log. The fifth flag
 * ({@code waitTrace}, gating {@code BulkheadWaitTraceEvent}) is intentionally left off — the
 * plan's decision&nbsp;4 enumerates four event types and {@code BulkheadEventConfig.allEnabled()}
 * would silently include the fifth.
 */
public final class BulkheadConfig {

    /** The name under which the example's bulkhead is registered. */
    public static final String BULKHEAD_NAME = "orderBh";

    /**
     * Per-event flag set the example opts into. The four flags map one-to-one to the four
     * bulkhead event types subscribed in {@code OrderService}.
     */
    private static final BulkheadEventConfig EXAMPLE_EVENTS = new BulkheadEventConfig(
            true,   // onAcquire   -> BulkheadOnAcquireEvent (TRACE)
            true,   // onRelease   -> BulkheadOnReleaseEvent (TRACE)
            true,   // onReject    -> BulkheadOnRejectEvent (WARN)
            false,  // waitTrace   -> not in scope for sub-step 6.C
            true);  // rollback    -> BulkheadRollbackTraceEvent (ERROR)

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
                        b -> b.balanced()
                                .maxConcurrentCalls(2)
                                .events(EXAMPLE_EVENTS)))
                .build();
    }
}
