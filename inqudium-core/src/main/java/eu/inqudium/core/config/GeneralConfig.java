package eu.inqudium.core.config;

import eu.inqudium.core.callid.InqCallIdGenerator;
import eu.inqudium.core.config.compatibility.InqCompatibility;
import eu.inqudium.core.log.LoggerFactory;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;

import java.util.Map;
import java.util.Optional;

public record GeneralConfig(
    InqClock clock,
    InqNanoTimeSource nanoTimesource,
    InqCompatibility compatibility,
    InqCallIdGenerator callIdGenerator,
    LoggerFactory loggerFactory,
    Map<Class<?>, ConfigExtension<?>> extensions
) {

  public <T extends ConfigExtension<?>> Optional<T> of(Class<T> type) {
    ConfigExtension<?> extension = extensions.get(type);
    return Optional.ofNullable(type.cast(extension));
  }

}

