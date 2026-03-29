package eu.inqudium.retry;

import eu.inqudium.core.InqRegistry;
import eu.inqudium.core.retry.RetryConfig;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for named {@link Retry} instances (ADR-015).
 *
 * @since 0.1.0
 */
public final class RetryRegistry implements InqRegistry<Retry, RetryConfig> {

    private final RetryConfig defaultConfig;
    private final ConcurrentHashMap<String, Retry> instances = new ConcurrentHashMap<>();

    public RetryRegistry(RetryConfig defaultConfig) {
        this.defaultConfig = Objects.requireNonNull(defaultConfig);
    }

    public RetryRegistry() { this(RetryConfig.ofDefaults()); }

    @Override public Retry get(String name) { return get(name, defaultConfig); }
    @Override public Retry get(String name, RetryConfig config) { return instances.computeIfAbsent(name, n -> Retry.of(n, config)); }
    @Override public void register(String name, RetryConfig config) { instances.computeIfAbsent(name, n -> Retry.of(n, config)); }
    @Override public Optional<Retry> find(String name) { return Optional.ofNullable(instances.get(name)); }
    @Override public Set<String> getAllNames() { return Set.copyOf(instances.keySet()); }
    @Override public Map<String, Retry> getAll() { return Map.copyOf(instances); }
    @Override public RetryConfig getDefaultConfig() { return defaultConfig; }
}
