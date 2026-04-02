package eu.inqudium.core.config;

import eu.inqudium.core.callid.InqCallIdGenerator;
import eu.inqudium.core.config.compatibility.InqCompatibility;
import eu.inqudium.core.log.LoggerFactory;
import eu.inqudium.core.time.CachedInqClock;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;

import java.util.Map;
import java.util.Objects;

/**
 * Builder for the core configuration.
 *
 * <p>This builder is intentionally designed so that {@link #build(Map)} is
 * package-private. Construction of the {@link GeneralConfig} is orchestrated
 * by the framework's internal assembly process, which provides the extension
 * map. External callers configure this builder via its setter methods; the
 * framework takes care of calling {@code build()} at the right time.
 */
public class GeneralConfigBuilder {
  private InqClock clock = CachedInqClock.getDefault();
  private InqNanoTimeSource nanoTimeSource = InqNanoTimeSource.system();
  private InqCompatibility compatibility = InqCompatibility.ofDefaults();
  private InqCallIdGenerator callIdGenerator = InqCallIdGenerator.uuid();
  private LoggerFactory loggerFactory = LoggerFactory.NO_OP_LOGGER_FACTORY;

  public GeneralConfigBuilder clock(InqClock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    return this;
  }

  public GeneralConfigBuilder loggerFactory(LoggerFactory loggerFactory) {
    this.loggerFactory = Objects.requireNonNull(loggerFactory,
        "loggerFactory must not be null");
    return this;
  }

  public GeneralConfigBuilder nanoTimeSource(InqNanoTimeSource nanoTimeSource) {
    this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource,
        "nanoTimeSource must not be null");
    return this;
  }

  public GeneralConfigBuilder compatibility(InqCompatibility compatibility) {
    this.compatibility = Objects.requireNonNull(compatibility,
        "compatibility must not be null");
    return this;
  }

  public GeneralConfigBuilder callIdGenerator(InqCallIdGenerator callIdGenerator) {
    this.callIdGenerator = Objects.requireNonNull(callIdGenerator,
        "callIdGenerator must not be null");
    return this;
  }

  /**
   * Builds the {@link GeneralConfig} with the given extension map.
   *
   * <p><strong>Package-private by design.</strong> External callers should not
   * invoke this method directly. The framework's assembly process provides
   * the extension map and triggers the build.
   *
   * @param extensions the registered configuration extensions
   * @return the built general configuration
   */
  GeneralConfig build(Map<Class<? extends ConfigExtension>, ConfigExtension> extensions) {
    Objects.requireNonNull(extensions, "extensions map must not be null");
    return new GeneralConfig(
        clock,
        nanoTimeSource,
        compatibility,
        callIdGenerator,
        loggerFactory,
        extensions
    );
  }
}
