package eu.inqudium.core.element.bulkhead.dsl;

import java.time.Duration;

public interface BulkheadProtection {

    // Modifiers
    BulkheadProtection limitingConcurrentCallsTo(int maxCalls);

    BulkheadProtection waitingAtMostFor(Duration maxWait);

    // Terminal Operations (Profiles)
    BulkheadConfig applyProtectiveProfile();

    BulkheadConfig applyBalancedProfile();

    BulkheadConfig applyPermissiveProfile();

    // Terminal Operation for custom configuration
    BulkheadConfig apply();
}
