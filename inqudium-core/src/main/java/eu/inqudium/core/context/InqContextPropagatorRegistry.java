package eu.inqudium.core.context;

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
 * ServiceLoader I/O. Waiting threads park with exponential backoff. If the resolver
 * thread dies, the state falls back to {@code Open} — no permanent livelock.
 *
 * @since 0.1.0
 */
public final class InqContextPropagatorRegistry {

  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(InqContextPropagatorRegistry.class);

  private static final long PARK_INITIAL_NANOS = 1_000;
  private static final long PARK_MAX_NANOS = 1_000_000;

  // ── Global default instance ──

  private static final AtomicReference<InqContextPropagatorRegistry> DEFAULT_INSTANCE = new AtomicReference<>();
  private final AtomicReference<RegistryState> state = new AtomicReference<>(new Open());

  public InqContextPropagatorRegistry() {
  }

  // ── State machine ──

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
  private static List<InqContextPropagator> discoverAndMerge(List<InqContextPropagator> programmatic) {
    var serviceLoaderPropagators = new ArrayList<InqContextPropagator>();

    try {
      var loader = ServiceLoader.load(InqContextPropagator.class);
      Iterator<InqContextPropagator> iterator = loader.iterator();
      while (true) {
        boolean hasNext;
        try {
          hasNext = iterator.hasNext();
        } catch (Throwable t) {
          rethrowIfFatal(t);
          LOGGER.warn("ServiceLoader iterator.hasNext() failed for InqContextPropagator " +
              "— remaining providers skipped.", t);
          break;
        }
        if (!hasNext) {
          break;
        }
        try {
          serviceLoaderPropagators.add(iterator.next());
        } catch (Throwable t) {
          rethrowIfFatal(t);
          LOGGER.warn("Failed to load InqContextPropagator provider — provider skipped.", t);
        }
      }
    } catch (Throwable t) {
      rethrowIfFatal(t);
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

  private static void rethrowIfFatal(Throwable t) {
    if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
    if (t instanceof LinkageError) throw (LinkageError) t;
  }

  public void register(InqContextPropagator propagator) {
    Objects.requireNonNull(propagator, "propagator must not be null");
    while (true) {
      var current = state.get();
      if (current instanceof Open open) {
        var updated = new ArrayList<>(open.programmatic);
        updated.add(propagator);
        var next = new Open(List.copyOf(updated));
        if (state.compareAndSet(current, next)) {
          return;
        }
        continue;
      }
      throw new IllegalStateException(
          "InqContextPropagatorRegistry is frozen — propagators must be registered " +
              "before the first context propagation.");
    }
  }

  public List<InqContextPropagator> getPropagators() {
    long parkNanos = PARK_INITIAL_NANOS;
    while (true) {
      var current = state.get();

      if (current instanceof Frozen frozen) {
        return frozen.resolved;
      }

      if (current instanceof Open open) {
        var resolving = new Resolving(open.programmatic);
        if (state.compareAndSet(current, resolving)) {
          try {
            var resolved = discoverAndMerge(open.programmatic);
            state.set(new Frozen(resolved));
            return resolved;
          } catch (Throwable t) {
            state.set(new Open(List.copyOf(open.programmatic)));
            rethrowIfFatal(t);
            LOGGER.error("ServiceLoader discovery failed — registry reset to Open", t);
            return List.of();
          }
        }
        continue;
      }

      // Resolving — park with exponential backoff
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
