package eu.inqudium.core.context;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

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
 * instances via the constructor, avoiding cross-test pollution in parallel execution.
 *
 * <h2>Thread safety</h2>
 * <p>All state is held in a single {@link AtomicReference AtomicReference&lt;RegistryState&gt;}.
 * Both {@link #register(InqContextPropagator)} and resolution use CAS operations —
 * no {@code synchronized}, no locks, safe for virtual threads.
 *
 * @since 0.1.0
 */
public final class InqContextPropagatorRegistry {

  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(InqContextPropagatorRegistry.class);

  // ── Global default instance ──

  private static volatile InqContextPropagatorRegistry defaultInstance;
  private final AtomicReference<RegistryState> state = new AtomicReference<>(new Open());

  /**
   * Creates a new, empty registry.
   *
   * <p>Use for testing or when you need isolated propagator scopes.
   * For production, use {@link #getDefault()}.
   */
  public InqContextPropagatorRegistry() {
  }

  // ── State machine ──

  /**
   * Returns the shared global registry instance.
   *
   * <p>Created lazily on first access. For production use — all elements
   * share this instance by default.
   *
   * @return the global default registry
   */
  public static InqContextPropagatorRegistry getDefault() {
    var instance = defaultInstance;
    if (instance != null) {
      return instance;
    }
    defaultInstance = new InqContextPropagatorRegistry();
    return defaultInstance;
  }

  /**
   * Replaces the global default registry.
   *
   * <p><strong>For testing only.</strong> Allows tests to install an isolated
   * registry or reset the global state between test runs.
   *
   * @param registry the new default registry (or {@code null} to reset to lazy creation)
   */
  public static void setDefault(InqContextPropagatorRegistry registry) {
    defaultInstance = registry;
  }

  @SuppressWarnings("unchecked")
  private static List<InqContextPropagator> discoverAndMerge(List<InqContextPropagator> programmatic) {
    var serviceLoaderPropagators = new ArrayList<InqContextPropagator>();

    try {
      var loader = ServiceLoader.load(InqContextPropagator.class);
      Iterator<InqContextPropagator> iterator = loader.iterator();
      while (true) {
        try {
          if (!iterator.hasNext()) {
            break;
          }
          serviceLoaderPropagators.add(iterator.next());
        } catch (Throwable t) {
          rethrowIfFatal(t);
          LOGGER.warn("Failed to load InqContextPropagator provider — Provider skipped. " +
              "Type: {}, Message: {}", t.getClass().getName(), t.getMessage());
        }
      }
    } catch (Throwable t) {
      rethrowIfFatal(t);
      LOGGER.warn("ServiceLoader discovery for InqContextPropagator failed. " +
          "Type: {}, Message: {}", t.getClass().getName(), t.getMessage());
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

  private static void rethrowIfFatal(Throwable t) {
    if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
    if (t instanceof LinkageError) throw (LinkageError) t;
  }

  /**
   * Registers a propagator programmatically.
   *
   * <p>Must be called before the first context propagation occurs.
   * Registrations after the first access throw {@link IllegalStateException}.
   *
   * <p>Lock-free: uses CAS to append to the immutable programmatic list.
   *
   * @param propagator the propagator to register
   * @throws IllegalStateException if the registry is already frozen
   */
  public void register(InqContextPropagator propagator) {
    Objects.requireNonNull(propagator, "propagator must not be null");
    while (true) {
      var current = state.get();
      if (current instanceof Frozen) {
        throw new IllegalStateException(
            "InqContextPropagatorRegistry is frozen — propagators must be registered " +
                "before the first context propagation.");
      }
      var open = (Open) current;
      var updated = new ArrayList<>(open.programmatic);
      updated.add(propagator);
      var next = new Open(List.copyOf(updated));
      if (state.compareAndSet(current, next)) {
        return;
      }
    }
  }

  /**
   * Returns the ordered list of all registered propagators.
   *
   * <p>On first call, triggers ServiceLoader discovery and freezes the registry.
   * Uses a CAS retry loop to atomically transition from {@code Open} to
   * {@code Frozen}.
   *
   * @return unmodifiable list of propagators
   */
  public List<InqContextPropagator> getPropagators() {
    while (true) {
      var current = state.get();
      if (current instanceof Frozen frozen) {
        return frozen.resolved;
      }
      var open = (Open) current;
      var resolved = discoverAndMerge(open.programmatic);
      var frozen = new Frozen(resolved);
      if (state.compareAndSet(current, frozen)) {
        return resolved;
      }
    }
  }

  private sealed interface RegistryState permits Open, Frozen {
  }

  private record Open(List<InqContextPropagator> programmatic) implements RegistryState {
    Open() {
      this(List.of());
    }
  }

  private record Frozen(List<InqContextPropagator> resolved) implements RegistryState {
  }
}
