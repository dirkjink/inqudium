package eu.inqudium.core.config;

import eu.inqudium.core.config.compatibility.InqCompatibility;
import eu.inqudium.core.log.LoggerFactory;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable, framework-wide configuration that is shared across all Inqudium elements.
 *
 * <h2>Purpose</h2>
 * <p>Holds the global infrastructure services (clock, nanosecond time source, logger factory,
 * compatibility settings) as well as a type-keyed map of all registered
 * {@link ConfigExtension}s. Every element builder receives this configuration via
 * {@link ExtensionBuilder#general(GeneralConfig)} before it is built, enabling elements
 * to access shared services and cross-cutting configuration.
 *
 * <h2>Extension Lookup</h2>
 * <p>The {@link #of(Class)} method provides type-safe retrieval of registered extensions.
 * Because the map is keyed by the extension's concrete class, lookups are O(1) and
 * fully type-safe thanks to the cast inside the method.
 *
 * @param clock          the wall-clock abstraction used for human-readable timestamps
 * @param nanoTimesource the nanosecond time source used for high-resolution, monotonic
 *                       timing throughout the framework (e.g., circuit breaker metrics)
 * @param compatibility  compatibility flags for cross-version behavior adjustments
 * @param loggerFactory  the factory for creating loggers within the framework
 * @param extensions     an unmodifiable map of registered {@link ConfigExtension}s,
 *                       keyed by their concrete class
 * @see GeneralConfigBuilder
 * @see InqConfig
 */
public record GeneralConfig(
    InqClock clock,
    InqNanoTimeSource nanoTimesource,
    InqCompatibility compatibility,
    LoggerFactory loggerFactory,
    Map<Class<?>, ConfigExtension<?>> extensions
) {

  static public GeneralConfig standard() {
    return new GeneralConfig(
        Instant::now,
        System::nanoTime,
        null,
        LoggerFactory.NO_OP_LOGGER_FACTORY,
        Map.of()
    );
  }

  /**
   * Retrieves a registered configuration extension by its concrete class.
   *
   * <p>Returns {@link Optional#empty()} if no extension of the given type has been
   * registered. The cast is safe because extensions are stored under their own class key.
   *
   * @param type the concrete class of the desired extension
   * @param <T>  the extension type
   * @return an {@link Optional} containing the extension, or empty if not registered
   */
  public <T extends ConfigExtension<?>> Optional<T> of(Class<T> type) {
    ConfigExtension<?> extension = extensions.get(type);
    return Optional.ofNullable(type.cast(extension));
  }
}
