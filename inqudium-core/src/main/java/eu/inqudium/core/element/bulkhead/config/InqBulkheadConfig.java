package eu.inqudium.core.element.bulkhead.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.element.InqElementConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.event.InqEventPublisher;

import java.time.Duration;

public record InqBulkheadConfig(
    GeneralConfig general,
    String name,
    InqElementType elementType,
    InqEventPublisher eventPublisher,
    int maxConcurrentCalls,
    BulkheadStrategy strategy,
    Duration maxWaitDuration,
    InqLimitAlgorithm limitAlgorithm
) implements ConfigExtension, InqElementConfig {
}

