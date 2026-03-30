package eu.inqudium.timelimiter;

import eu.inqudium.core.InqRegistry;
import eu.inqudium.core.timelimiter.TimeLimiterConfig;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for named {@link TimeLimiter} instances (ADR-015).
 *
 * @since 0.1.0
 */
public final class TimeLimiterRegistry implements InqRegistry<TimeLimiter, TimeLimiterConfig> {

  private final TimeLimiterConfig defaultConfig;
  private final ConcurrentHashMap<String, TimeLimiter> instances = new ConcurrentHashMap<>();

  public TimeLimiterRegistry(TimeLimiterConfig defaultConfig) {
    this.defaultConfig = Objects.requireNonNull(defaultConfig);
  }

  public TimeLimiterRegistry() {
    this(TimeLimiterConfig.ofDefaults());
  }

  @Override
  public TimeLimiter get(String name) {
    return get(name, defaultConfig);
  }

  @Override
  public TimeLimiter get(String name, TimeLimiterConfig config) {
    return instances.computeIfAbsent(name, n -> TimeLimiter.of(n, config));
  }

  @Override
  public void register(String name, TimeLimiterConfig config) {
    instances.computeIfAbsent(name, n -> TimeLimiter.of(n, config));
  }

  @Override
  public Optional<TimeLimiter> find(String name) {
    return Optional.ofNullable(instances.get(name));
  }

  @Override
  public Set<String> getAllNames() {
    return Set.copyOf(instances.keySet());
  }

  @Override
  public Map<String, TimeLimiter> getAll() {
    return Map.copyOf(instances);
  }

  @Override
  public TimeLimiterConfig getDefaultConfig() {
    return defaultConfig;
  }
}
