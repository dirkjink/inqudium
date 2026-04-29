package eu.inqudium.core.config;

import eu.inqudium.core.config.compatibility.InqCompatibility;
import eu.inqudium.core.log.LoggerFactory;
import eu.inqudium.core.time.CachedInqClock;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;

import java.util.Map;
import java.util.Objects;

/**
 * Builder for the framework-wide {@link GeneralConfig}.
 *
 * <h2>Purpose</h2>
 * <p>Provides a fluent API for configuring the global infrastructure services that
 * are shared across all Inqudium elements: the wall clock, the nanosecond time source,
 * compatibility flags, and the logger factory.
 *
 * <h2>Sensible Defaults</h2>
 * <p>Every field has a production-ready default:
 * <ul>
 *   <li>{@code clock} — {@link CachedInqClock#getDefault()}</li>
 *   <li>{@code nanoTimeSource} — {@link InqNanoTimeSource#system()} (delegates to {@code System.nanoTime()})</li>
 *   <li>{@code compatibility} — {@link InqCompatibility#ofDefaults()}</li>
 *   <li>{@code loggerFactory} — {@link LoggerFactory#NO_OP_LOGGER_FACTORY} (silent by default)</li>
 * </ul>
 *
 * <h2>Package-Private Build</h2>
 * <p>The {@link #build(Map)} method is deliberately <strong>package-private</strong>.
 * External callers configure this builder via its setter methods; the framework's
 * internal assembly process ({@link InqConfig.BuilderState}) supplies the extension
 * map and triggers the build at the right time.
 *
 * @see GeneralConfig
 * @see InqConfig
 */
public class GeneralConfigBuilder {

    private InqClock clock = CachedInqClock.getDefault();
    private InqNanoTimeSource nanoTimeSource = InqNanoTimeSource.system();
    private InqCompatibility compatibility = InqCompatibility.ofDefaults();
    private LoggerFactory loggerFactory = LoggerFactory.NO_OP_LOGGER_FACTORY;
    private Boolean enableExceptionOptimization;


    /**
     * Enables or disables exception handling optimization.
     *
     * @param enableExceptionOptimization {@code true} to enable, {@code false} to disable
     * @return this builder for chaining
     * @throws NullPointerException if the argument is null
     */
    public GeneralConfigBuilder enableExceptionOptimization(Boolean enableExceptionOptimization) {
        this.enableExceptionOptimization = enableExceptionOptimization;
        return this;
    }

    /**
     * Sets the wall-clock implementation. Used for human-readable timestamps in logging
     * and event metadata.
     *
     * @param clock the clock implementation; must not be null
     * @return this builder for chaining
     */
    public GeneralConfigBuilder clock(InqClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        return this;
    }

    /**
     * Sets the logger factory. All framework-internal logging is routed through
     * loggers created by this factory.
     *
     * @param loggerFactory the logger factory; must not be null
     * @return this builder for chaining
     */
    public GeneralConfigBuilder loggerFactory(LoggerFactory loggerFactory) {
        this.loggerFactory = Objects.requireNonNull(loggerFactory,
                "loggerFactory must not be null");
        return this;
    }

    /**
     * Sets the nanosecond time source. This is the high-resolution, monotonic time source
     * used by time-sensitive components like circuit breaker metrics. Injecting a custom
     * implementation enables deterministic testing without {@code Thread.sleep()}.
     *
     * @param nanoTimeSource the nanosecond time source; must not be null
     * @return this builder for chaining
     */
    public GeneralConfigBuilder nanoTimeSource(InqNanoTimeSource nanoTimeSource) {
        this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource,
                "nanoTimeSource must not be null");
        return this;
    }

    /**
     * Sets the compatibility configuration for cross-version behavior adjustments.
     *
     * @param compatibility the compatibility settings; must not be null
     * @return this builder for chaining
     */
    public GeneralConfigBuilder compatibility(InqCompatibility compatibility) {
        this.compatibility = Objects.requireNonNull(compatibility,
                "compatibility must not be null");
        return this;
    }

    /**
     * Builds the {@link GeneralConfig} with the given extension map.
     *
     * <p><strong>Package-private by design.</strong> External callers should not invoke
     * this method directly. The framework's assembly process provides the extension map
     * (collected from all registered {@link ExtensionBuilder}s) and triggers the build
     * at the appropriate point in the configuration lifecycle.
     *
     * @param extensions the registered configuration extensions (unmodifiable)
     * @return the fully initialized general configuration
     * @throws NullPointerException if {@code extensions} is null
     */
    GeneralConfig build(Map<Class<?>, ConfigExtension<?>> extensions) {
        Objects.requireNonNull(extensions, "extensions map must not be null");
        if (enableExceptionOptimization == null) enableExceptionOptimization = true;
        return new GeneralConfig(
                clock,
                nanoTimeSource,
                compatibility,
                loggerFactory,
                enableExceptionOptimization,
                extensions
        );
    }
}
