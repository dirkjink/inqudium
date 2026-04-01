package eu.inqudium.core.config;

import eu.inqudium.core.callid.InqCallIdGenerator;
import eu.inqudium.core.config.compatibility.InqCompatibility;
import eu.inqudium.core.time.CachedInqClock;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;

/**
 * Builder for the core configuration. No parent reference needed.
 */
public class GeneralExtensionBuilder {
  private InqClock clock = CachedInqClock.getDefault();
  private InqNanoTimeSource nanoTimeSource = InqNanoTimeSource.system();
  private InqCompatibility compatibility = InqCompatibility.ofDefaults();
  private InqCallIdGenerator callIdGenerator = InqCallIdGenerator.uuid();

  public GeneralExtensionBuilder clock(InqClock clock) {
    this.clock = clock;
    return this;
  }

  public GeneralExtensionBuilder nanoTimeSource(InqNanoTimeSource nanoTimeSource) {
    this.nanoTimeSource = nanoTimeSource;
    return this;
  }

  public GeneralExtensionBuilder compatibility(InqCompatibility compatibility) {
    this.compatibility = compatibility;
    return this;
  }

  public GeneralExtensionBuilder callIdGenerator(InqCallIdGenerator callIdGenerator) {
    this.callIdGenerator = callIdGenerator;
    return this;
  }

  GeneralConfig build() {
    return new GeneralConfig(
        clock,
        nanoTimeSource,
        compatibility,
        callIdGenerator
    );
  }
}