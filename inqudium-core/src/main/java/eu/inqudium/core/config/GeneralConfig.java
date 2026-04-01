package eu.inqudium.core.config;

import eu.inqudium.core.callid.InqCallIdGenerator;
import eu.inqudium.core.config.compatibility.InqCompatibility;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;

public record GeneralConfig(
    InqClock clock,
    InqNanoTimeSource nanoTimesource,
    InqCompatibility compatibility,
    InqCallIdGenerator callIdGenerator
) {
}

