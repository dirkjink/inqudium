package eu.inqudium.bulkhead.integration.function;

import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.validation.BuildReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Operational entry point that demonstrates the runtime-configuration-change shape (sub-step
 * 6.C of {@code REFACTORING_BULKHEAD_LOGGING_AND_RUNTIME_CONFIG.md}, decision&nbsp;6).
 *
 * <p>Models a thin admin surface a real application would expose to a flag-flip mechanism (a
 * scheduled job, an admin endpoint, a feature-flag callback). Two operations:
 *
 * <ul>
 *   <li>{@link #startSellPromotion()} — patches the bulkhead from {@code balanced/2} to
 *       {@code permissive/50}, raising the concurrent-call ceiling for the duration of a
 *       campaign.</li>
 *   <li>{@link #endSellPromotion()} — patches it back to {@code balanced/2}, restoring the
 *       safe-by-default protection level.</li>
 * </ul>
 *
 * <p>The patches are issued through the standard {@link InqRuntime#update(java.util.function.Consumer)
 * runtime.update(...)} entry point. The {@code OrderService}'s wrapped functions need not be
 * rebuilt — the bulkhead component is the same instance before and after the patch, only its
 * snapshot changes.
 */
public class AdminService {

    private static final Logger LOG = LoggerFactory.getLogger(AdminService.class);

    private final InqRuntime runtime;

    public AdminService(InqRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * Patches the bulkhead to {@code permissive().maxConcurrentCalls(50)}. Logs an INFO line
     * before issuing the patch so a reader of the log timeline can correlate the AdminService
     * call with the {@code RuntimeComponentPatchedEvent} that follows.
     */
    public void startSellPromotion() {
        LOG.info("Sell promotion starting — patching bulkhead '{}' to permissive/50 permits",
                BulkheadConfig.BULKHEAD_NAME);
        BuildReport report = runtime.update(u -> u.imperative(im -> im
                .bulkhead(BulkheadConfig.BULKHEAD_NAME, b -> b
                        .permissive()
                        .maxConcurrentCalls(50))));
        if (!report.isSuccess()) {
            throw new IllegalStateException(
                    "start-sell-promotion patch failed: " + report);
        }
    }

    /**
     * Patches the bulkhead back to {@code balanced().maxConcurrentCalls(2)}. Logs an INFO line
     * before issuing the patch.
     */
    public void endSellPromotion() {
        LOG.info("Sell promotion ending — patching bulkhead '{}' back to balanced/2 permits",
                BulkheadConfig.BULKHEAD_NAME);
        BuildReport report = runtime.update(u -> u.imperative(im -> im
                .bulkhead(BulkheadConfig.BULKHEAD_NAME, b -> b
                        .balanced()
                        .maxConcurrentCalls(2))));
        if (!report.isSuccess()) {
            throw new IllegalStateException(
                    "end-sell-promotion patch failed: " + report);
        }
    }
}
