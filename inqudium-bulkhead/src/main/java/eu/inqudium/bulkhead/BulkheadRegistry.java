package eu.inqudium.bulkhead;

import eu.inqudium.core.InqRegistry;
import eu.inqudium.core.bulkhead.BulkheadConfig;

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
public final class BulkheadRegistry implements InqRegistry<Bulkhead, BulkheadConfig> {

    private final BulkheadConfig defaultConfig;
    private final ConcurrentHashMap<String, Bulkhead> instances = new ConcurrentHashMap<>();

    public BulkheadRegistry(BulkheadConfig defaultConfig) { this.defaultConfig = Objects.requireNonNull(defaultConfig); }
    public BulkheadRegistry() { this(BulkheadConfig.ofDefaults()); }

    @Override public Bulkhead get(String name) { return get(name, defaultConfig); }
    @Override public Bulkhead get(String name, BulkheadConfig config) { return instances.computeIfAbsent(name, n -> Bulkhead.of(n, config)); }
    @Override public void register(String name, BulkheadConfig config) { instances.computeIfAbsent(name, n -> Bulkhead.of(n, config)); }
    @Override public Optional<Bulkhead> find(String name) { return Optional.ofNullable(instances.get(name)); }
    @Override public Set<String> getAllNames() { return Set.copyOf(instances.keySet()); }
    @Override public Map<String, Bulkhead> getAll() { return Map.copyOf(instances); }
    @Override public BulkheadConfig getDefaultConfig() { return defaultConfig; }
}
