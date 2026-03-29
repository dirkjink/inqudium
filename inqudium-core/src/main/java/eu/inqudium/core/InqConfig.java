package eu.inqudium.core;

import eu.inqudium.core.compatibility.InqCompatibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base interface for all element configurations.
 *
 * <p>Configurations are immutable. Every config provides:
 * <ul>
 *   <li>{@link InqCompatibility} — behavioral change flags (ADR-013)</li>
 *   <li>{@link InqCallIdGenerator} — call ID generation strategy</li>
 *   <li>{@link Logger} — SLF4J logger for the element's internal diagnostics</li>
 * </ul>
 *
 * @since 0.1.0
 */
public interface InqConfig {

    /**
     * Returns the compatibility flags for this configuration.
     *
     * @return the compatibility instance
     */
    InqCompatibility getCompatibility();

    /**
     * Returns the call ID generator for this configuration.
     *
     * @return the call ID generator
     */
    default InqCallIdGenerator getCallIdGenerator() {
        return InqCallIdGenerator.uuid();
    }

    /**
     * Returns the clock used for time-dependent operations and event timestamps.
     *
     * <p>Element implementations use this instead of {@code Instant.now()} to
     * ensure deterministic testability (ADR-016).
     *
     * <p>The default returns {@link InqClock#system()}.
     *
     * @return the clock
     */
    default InqClock getClock() {
        return InqClock.system();
    }

    /**
     * Returns the SLF4J logger for this configuration.
     *
     * <p>Used by element implementations for internal diagnostics (consumer errors,
     * provider failures, anti-pattern warnings). Override to route element logs
     * to a specific logger or to inject a mock in tests.
     *
     * <p>The default returns a logger named after the config's class.
     *
     * @return the SLF4J logger
     */
    default Logger getLogger() {
        return LoggerFactory.getLogger(getClass());
    }
}
