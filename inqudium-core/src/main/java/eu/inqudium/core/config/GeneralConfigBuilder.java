package eu.inqudium.core.config;

import eu.inqudium.core.callid.InqCallIdGenerator;
import eu.inqudium.core.config.compatibility.InqCompatibility;
import eu.inqudium.core.log.LoggerFactory;
import eu.inqudium.core.time.CachedInqClock;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;

import java.util.Map;

/**
 * Builder for the core configuration. No parent reference needed.
 */
public class GeneralConfigBuilder {
  private InqClock clock = CachedInqClock.getDefault();
  private InqNanoTimeSource nanoTimeSource = InqNanoTimeSource.system();
  private InqCompatibility compatibility = InqCompatibility.ofDefaults();
  private InqCallIdGenerator callIdGenerator = InqCallIdGenerator.uuid();
  private LoggerFactory loggerFactory = LoggerFactory.NO_OP_LOGGER_FACTORY;

  public GeneralConfigBuilder clock(InqClock clock) {
    this.clock = clock;
    return this;
  }

  public GeneralConfigBuilder loggerFactory(LoggerFactory loggerFactory) {
    this.loggerFactory = loggerFactory;
    return this;
  }

  public GeneralConfigBuilder nanoTimeSource(InqNanoTimeSource nanoTimeSource) {
    this.nanoTimeSource = nanoTimeSource;
    return this;
  }

  public GeneralConfigBuilder compatibility(InqCompatibility compatibility) {
    this.compatibility = compatibility;
    return this;
  }

  public GeneralConfigBuilder callIdGenerator(InqCallIdGenerator callIdGenerator) {
    this.callIdGenerator = callIdGenerator;
    return this;
  }

  GeneralConfig build(Map<Class<? extends ConfigExtension>, ConfigExtension> extensions) {
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