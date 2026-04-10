package eu.inqudium.core.context;

import eu.inqudium.core.exception.InqException;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Registry for {@link InqContextPropagator} instances.
 *
 * <p>Propagators are discovered via ServiceLoader and/or registered programmatically.
 * Follows ADR-014 conventions: lazy discovery, Comparable ordering, error isolation,
 * frozen after first access.
 *
 * <h2>Instance-based design</h2>
 * <p>The registry is an instance — not a static utility. A shared global instance
 * is available via {@link #getDefault()} for production use. Tests create isolated
 * instances via the constructor.
 *
 * <h2>Thread safety</h2>
 * <p>All state is held in a single {@link AtomicReference}. The state machine uses
 * a {@code Resolving} intermediate state so that exactly one thread performs
 * ServiceLoader I/O. Waiting threads park with exponential backoff and bounded
 * total timeout measured via {@link System#nanoTime()}.
 *
 * @since 0.1.0
 */
public final class InqContextPropagatorRegistry {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(InqContextPropagatorRegistry.class);

    private static final long PARK_INITIAL_NANOS = 1_000;
    private static final long PARK_MAX_NANOS = 1_000_000;
    private static final long RESOLVE_TIMEOUT_NANOS = 30_000_000_000L;
    private static final long REGISTER_WAIT_NANOS = 5_000_000_000L;
    private static final int MAX_CONSECUTIVE_HAS_NEXT_FAILURES = 10;

    // ── Global default instance ──

    private static final AtomicReference<InqContextPropagatorRegistry> DEFAULT_INSTANCE = new AtomicReference<>();
    private final AtomicReference<RegistryState> state = new AtomicReference<>(new Open());
    private final ClassLoader spiClassLoader;

    // ── State machine ──

    public InqContextPropagatorRegistry() {
        var tccl = Thread.currentThread().getContextClassLoader();
        this.spiClassLoader = tccl != null ? tccl : InqContextPropagator.class.getClassLoader();
    }

    public static InqContextPropagatorRegistry getDefault() {
        var instance = DEFAULT_INSTANCE.get();
        if (instance != null) {
            return instance;
        }
        DEFAULT_INSTANCE.compareAndSet(null, new InqContextPropagatorRegistry());
        return DEFAULT_INSTANCE.get();
    }

    public static void setDefault(InqContextPropagatorRegistry registry) {
        DEFAULT_INSTANCE.set(registry);
    }

    @SuppressWarnings("unchecked")
    private static List<InqContextPropagator> discoverAndMerge(List<InqContextPropagator> programmatic,
                                                               ClassLoader classLoader) {
        var serviceLoaderPropagators = new ArrayList<InqContextPropagator>();

        try {
            var loader = ServiceLoader.load(InqContextPropagator.class, classLoader);
            Iterator<InqContextPropagator> iterator = loader.iterator();
            int consecutiveHasNextFailures = 0;
            while (true) {
                boolean hasNext;
                try {
                    hasNext = iterator.hasNext();
                    consecutiveHasNextFailures = 0;
                } catch (Throwable t) {
                    InqException.rethrowIfFatal(t);
                    consecutiveHasNextFailures++;
                    LOGGER.warn("ServiceLoader iterator.hasNext() failed for InqContextPropagator " +
                            "(consecutive failure #{}) — retrying.", consecutiveHasNextFailures, t);
                    if (consecutiveHasNextFailures >= MAX_CONSECUTIVE_HAS_NEXT_FAILURES) {
                        LOGGER.warn("Giving up after {} consecutive hasNext() failures " +
                                "— remaining providers skipped.", MAX_CONSECUTIVE_HAS_NEXT_FAILURES);
                        break;
                    }
                    continue;
                }
                if (!hasNext) {
                    break;
                }
                try {
                    serviceLoaderPropagators.add(iterator.next());
                } catch (Throwable t) {
                    InqException.rethrowIfFatal(t);
                    LOGGER.warn("Failed to load InqContextPropagator provider — provider skipped.", t);
                }
            }
        } catch (Throwable t) {
            InqException.rethrowIfFatal(t);
            LOGGER.warn("ServiceLoader discovery for InqContextPropagator failed.", t);
        }

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
     * Registers a propagator programmatically.
     *
     * <p>If the registry is currently resolving, waits briefly for resolution
     * to complete. Only {@code Frozen} state causes an immediate rejection.
     *
     * @param propagator the propagator to register
     * @throws IllegalStateException if the registry is frozen or registration times out
     */
    public void register(InqContextPropagator propagator) {
        Objects.requireNonNull(propagator, "propagator must not be null");
        long waitStart = System.nanoTime();
        long parkNanos = PARK_INITIAL_NANOS;
        while (true) {
            var current = state.get();
            if (current instanceof Open(List<InqContextPropagator> programmatic)) {
                var updated = new ArrayList<>(programmatic);
                updated.add(propagator);
                var next = new Open(List.copyOf(updated));
                if (state.compareAndSet(current, next)) {
                    return;
                }
                continue;
            }
            if (current instanceof Frozen) {
                throw new IllegalStateException(
                        "InqContextPropagatorRegistry is frozen — " +
                                "propagators must be registered before the first context propagation.");
            }
            // Resolving — wait briefly
            long elapsed = System.nanoTime() - waitStart;
            if (elapsed >= REGISTER_WAIT_NANOS) {
                throw new IllegalStateException(
                        "InqContextPropagatorRegistry has been resolving for " +
                                (elapsed / 1_000_000) + "ms — registration timed out. " +
                                "Propagators must be registered before the first context propagation.");
            }
            LockSupport.parkNanos(parkNanos);
            parkNanos = Math.min(parkNanos * 2, PARK_MAX_NANOS);
        }
    }

    /**
     * Returns the ordered list of all registered propagators.
     *
     * <p>On first call, triggers ServiceLoader discovery and freezes the registry.
     * Elapsed time measured via {@link System#nanoTime()} — immune to spurious wakeups.
     *
     * @return unmodifiable list of propagators
     */
    public List<InqContextPropagator> getPropagators() {
        long parkNanos = PARK_INITIAL_NANOS;
        long waitStart = System.nanoTime();
        while (true) {
            var current = state.get();

            if (current instanceof Frozen(List<InqContextPropagator> resolved1)) {
                return resolved1;
            }

            if (current instanceof Open(List<InqContextPropagator> programmatic)) {
                var resolving = new Resolving(programmatic);
                if (state.compareAndSet(current, resolving)) {
                    try {
                        var resolved = discoverAndMerge(programmatic, spiClassLoader);
                        state.set(new Frozen(resolved));
                        return resolved;
                    } catch (Throwable t) {
                        state.set(new Open(List.copyOf(programmatic)));
                        InqException.rethrowIfFatal(t);
                        LOGGER.error("ServiceLoader discovery failed — registry reset to Open", t);
                        return List.of();
                    }
                }
                continue;
            }

            // Resolving — park with real elapsed time tracking
            long elapsed = System.nanoTime() - waitStart;
            if (elapsed >= RESOLVE_TIMEOUT_NANOS) {
                var resolving = (Resolving) current;
                if (state.compareAndSet(current, new Open(List.copyOf(resolving.programmatic)))) {
                    LOGGER.warn("Resolver thread appears stuck after {}ms — forced reset to Open",
                            elapsed / 1_000_000);
                }
                parkNanos = PARK_INITIAL_NANOS;
                waitStart = System.nanoTime();
                continue;
            }

            LockSupport.parkNanos(parkNanos);
            parkNanos = Math.min(parkNanos * 2, PARK_MAX_NANOS);
        }
    }

    private sealed interface RegistryState permits Open, Resolving, Frozen {
    }

    private record Open(List<InqContextPropagator> programmatic) implements RegistryState {
        Open() {
            this(List.of());
        }
    }

    private record Resolving(List<InqContextPropagator> programmatic) implements RegistryState {
    }

    private record Frozen(List<InqContextPropagator> resolved) implements RegistryState {
    }
}
