package eu.inqudium.core.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registry for {@link InqContextPropagator} instances.
 *
 * <p>Propagators are discovered via ServiceLoader and/or registered programmatically.
 * Follows ADR-014 conventions: lazy discovery, Comparable ordering, error isolation,
 * frozen after first access.
 *
 * @since 0.1.0
 */
public final class InqContextPropagatorRegistry {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(InqContextPropagatorRegistry.class);

    private static final CopyOnWriteArrayList<InqContextPropagator> programmatic = new CopyOnWriteArrayList<>();
    private static volatile List<InqContextPropagator> resolved;
    private static final AtomicBoolean frozen = new AtomicBoolean(false);

    private InqContextPropagatorRegistry() {}

    /**
     * Registers a propagator programmatically.
     *
     * <p>Must be called before the first context propagation occurs.
     *
     * @param propagator the propagator to register
     * @throws IllegalStateException if the registry is already frozen
     */
    public static void register(InqContextPropagator propagator) {
        Objects.requireNonNull(propagator, "propagator must not be null");
        if (frozen.get()) {
            throw new IllegalStateException(
                    "InqContextPropagatorRegistry is frozen — propagators must be registered before the first context propagation.");
        }
        programmatic.add(propagator);
    }

    /**
     * Returns the ordered list of all registered propagators.
     *
     * <p>On first call, triggers ServiceLoader discovery and freezes the registry.
     *
     * @return unmodifiable list of propagators
     */
    public static List<InqContextPropagator> getPropagators() {
        var result = resolved;
        if (result != null) {
            return result;
        }
        synchronized (InqContextPropagatorRegistry.class) {
            if (resolved != null) {
                return resolved;
            }
            resolved = discoverAndMerge();
            frozen.set(true);
            return resolved;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<InqContextPropagator> discoverAndMerge() {
        var serviceLoaderPropagators = new ArrayList<InqContextPropagator>();

        try {
            var loader = ServiceLoader.load(InqContextPropagator.class);
            for (var provider : loader) {
                try {
                    serviceLoaderPropagators.add(provider);
                } catch (Exception e) {
                    LOGGER.warn(
                            "Failed to instantiate InqContextPropagator: {} — Provider skipped.", e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.warn(
                    "ServiceLoader discovery for InqContextPropagator failed: {}", e.getMessage());
        }

        // Sort: Comparable first (ascending), then non-Comparable
        var comparable = new ArrayList<InqContextPropagator>();
        var nonComparable = new ArrayList<InqContextPropagator>();
        for (var p : serviceLoaderPropagators) {
            if (p instanceof Comparable) {
                comparable.add(p);
            } else {
                nonComparable.add(p);
            }
        }
        comparable.sort((a, b) -> ((Comparable<InqContextPropagator>) a).compareTo(b));

        var result = new ArrayList<InqContextPropagator>(comparable.size() + nonComparable.size() + programmatic.size());
        result.addAll(comparable);
        result.addAll(nonComparable);
        result.addAll(programmatic);
        return List.copyOf(result);
    }

    /**
     * Resets the registry for testing purposes.
     * <strong>Not for production use.</strong>
     */
    static void reset() {
        synchronized (InqContextPropagatorRegistry.class) {
            resolved = null;
            frozen.set(false);
            programmatic.clear();
        }
    }
}
