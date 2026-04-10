package eu.inqudium.imperative.bulkhead;

import eu.inqudium.core.InqRegistry;
import eu.inqudium.imperative.bulkhead.config.InqImperativeBulkheadConfig;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for named {@link Bulkhead} instances (ADR-015).
 *
 * @since 0.1.0
 */
public final class BulkheadRegistry implements InqRegistry<Bulkhead, InqImperativeBulkheadConfig> {

    private final InqImperativeBulkheadConfig defaultConfig;
    private final ConcurrentHashMap<String, Bulkhead> instances = new ConcurrentHashMap<>();

    public BulkheadRegistry(InqImperativeBulkheadConfig defaultConfig) {
        this.defaultConfig = Objects.requireNonNull(defaultConfig);
    }

    @Override
    public Bulkhead get(String name) {
        return get(name, defaultConfig);
    }

    @Override
    public Bulkhead get(String name, InqImperativeBulkheadConfig config) {
        return instances.computeIfAbsent(name, n -> Bulkhead.of(config));
    }

    @Override
    public void register(String name, InqImperativeBulkheadConfig config) {
        instances.computeIfAbsent(name, n -> Bulkhead.of(config));
    }

    @Override
    public Optional<Bulkhead> find(String name) {
        return Optional.ofNullable(instances.get(name));
    }

    @Override
    public Set<String> getAllNames() {
        return Set.copyOf(instances.keySet());
    }

    @Override
    public Map<String, Bulkhead> getAll() {
        return Map.copyOf(instances);
    }

    @Override
    public InqImperativeBulkheadConfig getDefaultConfig() {
        return defaultConfig;
    }
}
