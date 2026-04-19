package eu.inqudium.core.element;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry that maps names to {@link InqElement} instances.
 *
 * <p>The registry is the central lookup mechanism for the annotation-driven
 * pipeline: when {@code @InqCircuitBreaker("paymentCb")} is scanned, the
 * name {@code "paymentCb"} is resolved to the registered element instance
 * via this registry.</p>
 *
 * <h3>Population</h3>
 * <p>The registry can be populated from any source — the mechanism depends
 * on the runtime environment:</p>
 * <pre>
 *   Programmatic:           registry.register("paymentCb", circuitBreaker);
 *   Spring Auto-Config:     all @Bean InqElement → registry (auto-discovered)
 *   YAML/Properties:        config → element factory → registry
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Build
 * InqElementRegistry registry = InqElementRegistry.builder()
 *         .register("paymentCb", circuitBreaker)
 *         .register("paymentRetry", retry)
 *         .register("paymentBh", bulkhead)
 *         .build();
 *
 * // Lookup (throws if missing)
 * InqElement cb = registry.get("paymentCb");
 *
 * // Safe lookup
 * Optional<InqElement> maybeCb = registry.find("paymentCb");
 *
 * // Used by PipelineFactory (in inqudium-annotation-processor):
 * ScanResult scan = InqAnnotationScanner.scan(method);
 * InqPipeline pipeline = PipelineFactory.build(scan, registry);
 * }</pre>
 *
 * <h3>Thread safety</h3>
 * <p>All operations are thread-safe. The registry supports concurrent
 * registration and lookup. For immutable registries, use the {@link Builder}
 * to construct once and share freely.</p>
 *
 * @since 0.8.0
 */
public final class InqElementRegistry {

    private final ConcurrentHashMap<String, InqElement> elements;

    private InqElementRegistry(ConcurrentHashMap<String, InqElement> elements) {
        this.elements = elements;
    }

    /**
     * Creates an empty, mutable registry.
     *
     * @return an empty registry
     */
    public static InqElementRegistry create() {
        return new InqElementRegistry(new ConcurrentHashMap<>());
    }

    /**
     * Creates a new builder for constructing an immutable-style registry.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // ======================== Registration ========================

    /**
     * Registers an element under the given name.
     *
     * <p>If an element with the same name already exists, it is replaced
     * and the previous element is returned.</p>
     *
     * @param name    the lookup name (e.g. "paymentCb")
     * @param element the element instance
     * @return the previously registered element, or {@code null} if none
     * @throws NullPointerException     if name or element is null
     * @throws IllegalArgumentException if name is blank
     */
    public InqElement register(String name, InqElement element) {
        Objects.requireNonNull(name, "Name must not be null");
        Objects.requireNonNull(element, "Element must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Name must not be blank");
        }
        return elements.put(name, element);
    }

    // ======================== Lookup ========================

    /**
     * Returns the element registered under the given name.
     *
     * @param name the lookup name
     * @return the element
     * @throws InqElementNotFoundException if no element is registered under this name
     * @throws NullPointerException        if name is null
     */
    public InqElement get(String name) {
        Objects.requireNonNull(name, "Name must not be null");
        InqElement element = elements.get(name);
        if (element == null) {
            throw new InqElementNotFoundException(name, names());
        }
        return element;
    }

    /**
     * Returns the element registered under the given name, cast to the
     * expected type.
     *
     * @param name the lookup name
     * @param type the expected element type
     * @param <T>  the element type
     * @return the element
     * @throws InqElementNotFoundException if no element is registered
     * @throws ClassCastException          if the element is not of the expected type
     */
    @SuppressWarnings("unchecked")
    public <T extends InqElement> T get(String name, Class<T> type) {
        InqElement element = get(name);
        if (!type.isInstance(element)) {
            throw new ClassCastException(
                    "Element '" + name + "' is " + element.getClass().getName()
                            + ", expected " + type.getName());
        }
        return (T) element;
    }

    /**
     * Returns the element registered under the given name, or empty if not found.
     *
     * @param name the lookup name
     * @return an Optional containing the element, or empty
     */
    public Optional<InqElement> find(String name) {
        Objects.requireNonNull(name, "Name must not be null");
        return Optional.ofNullable(elements.get(name));
    }

    /**
     * Returns {@code true} if an element is registered under the given name.
     *
     * @param name the lookup name
     * @return {@code true} if registered
     */
    public boolean contains(String name) {
        return name != null && elements.containsKey(name);
    }

    // ======================== Introspection ========================

    /**
     * Returns all registered names.
     *
     * @return an unmodifiable set of names
     */
    public Set<String> names() {
        return Collections.unmodifiableSet(elements.keySet());
    }

    /**
     * Returns all registered elements.
     *
     * @return an unmodifiable collection of elements
     */
    public List<InqElement> elements() {
        return List.copyOf(elements.values());
    }

    /**
     * Returns the number of registered elements.
     */
    public int size() {
        return elements.size();
    }

    /**
     * Returns {@code true} if the registry is empty.
     */
    public boolean isEmpty() {
        return elements.isEmpty();
    }

    @Override
    public String toString() {
        return "InqElementRegistry[" + elements.size() + " elements: "
                + String.join(", ", elements.keySet()) + "]";
    }

    // ======================== Builder ========================

    /**
     * Builder for constructing a registry with pre-registered elements.
     *
     * <pre>{@code
     * InqElementRegistry registry = InqElementRegistry.builder()
     *         .register("paymentCb", circuitBreaker)
     *         .register("paymentRetry", retry)
     *         .build();
     * }</pre>
     */
    public static final class Builder {

        private final ConcurrentHashMap<String, InqElement> elements =
                new ConcurrentHashMap<>();

        private Builder() {
        }

        /**
         * Registers an element under the given name.
         *
         * @param name    the lookup name
         * @param element the element instance
         * @return this builder
         */
        public Builder register(String name, InqElement element) {
            Objects.requireNonNull(name, "Name must not be null");
            Objects.requireNonNull(element, "Element must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Name must not be blank");
            }
            elements.put(name, element);
            return this;
        }

        /**
         * Builds the registry. The builder can still be used after this call.
         *
         * @return the registry with all registered elements
         */
        public InqElementRegistry build() {
            return new InqElementRegistry(new ConcurrentHashMap<>(elements));
        }
    }

    // ======================== Exception ========================

    /**
     * Thrown when an element is not found in the registry.
     */
    public static final class InqElementNotFoundException extends RuntimeException {

        private final String name;

        InqElementNotFoundException(String name, Set<String> available) {
            super("No InqElement registered under name '" + name + "'. "
                    + "Available: " + (available.isEmpty() ? "(none)" : available));
            this.name = name;
        }

        /**
         * Returns the name that was not found.
         */
        public String name() {
            return name;
        }
    }
}
