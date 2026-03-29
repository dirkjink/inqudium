package eu.inqudium.circuitbreaker;

import eu.inqudium.core.InqConfig;
import eu.inqudium.core.InqElement;
import eu.inqudium.core.InqRegistry;
import eu.inqudium.core.circuitbreaker.CircuitBreakerConfig;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for named {@link CircuitBreaker} instances.
 *
 * <p>Thread-safe, first-registration-wins (ADR-015).
 *
 * @since 0.1.0
 */
public final class CircuitBreakerRegistry implements InqRegistry<CircuitBreaker, CircuitBreakerConfig> {

    private final CircuitBreakerConfig defaultConfig;
    private final ConcurrentHashMap<String, CircuitBreaker> instances = new ConcurrentHashMap<>();

    /**
     * Creates a registry with a custom default configuration.
     *
     * @param defaultConfig the default config for on-demand creation
     */
    public CircuitBreakerRegistry(CircuitBreakerConfig defaultConfig) {
        this.defaultConfig = Objects.requireNonNull(defaultConfig);
    }

    /**
     * Creates a registry with default configuration.
     */
    public CircuitBreakerRegistry() {
        this(CircuitBreakerConfig.ofDefaults());
    }

    @Override
    public CircuitBreaker get(String name) {
        return get(name, defaultConfig);
    }

    @Override
    public CircuitBreaker get(String name, CircuitBreakerConfig config) {
        return instances.computeIfAbsent(name, n -> CircuitBreaker.of(n, config));
    }

    @Override
    public void register(String name, CircuitBreakerConfig config) {
        instances.computeIfAbsent(name, n -> CircuitBreaker.of(n, config));
    }

    @Override
    public Optional<CircuitBreaker> find(String name) {
        return Optional.ofNullable(instances.get(name));
    }

    @Override
    public Set<String> getAllNames() {
        return Set.copyOf(instances.keySet());
    }

    @Override
    public Map<String, CircuitBreaker> getAll() {
        return Map.copyOf(instances);
    }

    @Override
    public CircuitBreakerConfig getDefaultConfig() {
        return defaultConfig;
    }
}
