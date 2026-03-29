package eu.inqudium.ratelimiter;

import eu.inqudium.core.InqRegistry;
import eu.inqudium.core.ratelimiter.RateLimiterConfig;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for named {@link RateLimiter} instances (ADR-015).
 *
 * @since 0.1.0
 */
public final class RateLimiterRegistry implements InqRegistry<RateLimiter, RateLimiterConfig> {

    private final RateLimiterConfig defaultConfig;
    private final ConcurrentHashMap<String, RateLimiter> instances = new ConcurrentHashMap<>();

    public RateLimiterRegistry(RateLimiterConfig defaultConfig) { this.defaultConfig = Objects.requireNonNull(defaultConfig); }
    public RateLimiterRegistry() { this(RateLimiterConfig.ofDefaults()); }

    @Override public RateLimiter get(String name) { return get(name, defaultConfig); }
    @Override public RateLimiter get(String name, RateLimiterConfig config) { return instances.computeIfAbsent(name, n -> RateLimiter.of(n, config)); }
    @Override public void register(String name, RateLimiterConfig config) { instances.computeIfAbsent(name, n -> RateLimiter.of(n, config)); }
    @Override public Optional<RateLimiter> find(String name) { return Optional.ofNullable(instances.get(name)); }
    @Override public Set<String> getAllNames() { return Set.copyOf(instances.keySet()); }
    @Override public Map<String, RateLimiter> getAll() { return Map.copyOf(instances); }
    @Override public RateLimiterConfig getDefaultConfig() { return defaultConfig; }
}
