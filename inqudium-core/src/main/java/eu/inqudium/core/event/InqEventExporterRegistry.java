package eu.inqudium.core.event;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registry for {@link InqEventExporter} instances.
 *
 * <p>Exporters receive all events from all elements that use this registry.
 * Registration follows ADR-014 conventions:
 * <ul>
 *   <li>ServiceLoader discovery: lazy on first access, cached for lifetime.</li>
 *   <li>Comparable ordering: Comparable exporters sorted first, then non-Comparable,
 *       then programmatically registered.</li>
 *   <li>Error isolation: a failing exporter is caught and logged, never affects
 *       other exporters or the resilience element.</li>
 *   <li>Frozen after first access: programmatic registration must happen before
 *       the first {@link #export(InqEvent)} call.</li>
 * </ul>
 *
 * <h2>Instance-based design</h2>
 * <p>The registry is an instance — not a static utility. A shared global instance
 * is available via {@link #getDefault()} for production use. Tests create isolated
 * instances via the constructor, avoiding cross-test pollution in parallel execution.
 *
 * <h2>Thread safety</h2>
 * <p>All state is held in a single {@link AtomicReference AtomicReference&lt;RegistryState&gt;}.
 * Both {@link #register(InqEventExporter)} and resolution use CAS operations —
 * no {@code synchronized}, no locks, safe for virtual threads.
 *
 * @since 0.1.0
 */
public final class InqEventExporterRegistry {

  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(InqEventExporterRegistry.class);

  // ── Global default instance ──

  private static volatile InqEventExporterRegistry defaultInstance;
  private final AtomicReference<RegistryState> state = new AtomicReference<>(new Open());

  /**
   * Creates a new, empty registry.
   *
   * <p>Use for testing or when you need isolated exporter scopes.
   * For production, use {@link #getDefault()}.
   */
  public InqEventExporterRegistry() {
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
  public static InqEventExporterRegistry getDefault() {
    var instance = defaultInstance;
    if (instance != null) {
      return instance;
    }
    // Benign race — multiple threads may create an instance, but only one wins
    defaultInstance = new InqEventExporterRegistry();
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
  public static void setDefault(InqEventExporterRegistry registry) {
    defaultInstance = registry;
  }

  private static boolean isSubscribed(InqEventExporter exporter, InqEvent event) {
    var types = exporter.subscribedEventTypes();
    if (types == null || types.isEmpty()) {
      return true;
    }
    for (var type : types) {
      if (type.isInstance(event)) {
        return true;
      }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static List<InqEventExporter> discoverAndMerge(List<InqEventExporter> programmatic) {
    var serviceLoaderExporters = new ArrayList<InqEventExporter>();

    try {
      var loader = ServiceLoader.load(InqEventExporter.class);
      Iterator<InqEventExporter> iterator = loader.iterator();
      while (true) {
        try {
          if (!iterator.hasNext()) {
            break;
          }
          serviceLoaderExporters.add(iterator.next());
        } catch (Throwable t) {
          rethrowIfFatal(t);
          LOGGER.warn("Failed to load InqEventExporter provider — Provider skipped. " +
              "Type: {}, Message: {}", t.getClass().getName(), t.getMessage());
        }
      }
    } catch (Throwable t) {
      rethrowIfFatal(t);
      LOGGER.warn("ServiceLoader discovery for InqEventExporter failed. " +
          "Type: {}, Message: {}", t.getClass().getName(), t.getMessage());
    }

    // Sort: Comparable first (ascending), then non-Comparable
    var comparable = new ArrayList<InqEventExporter>();
    var nonComparable = new ArrayList<InqEventExporter>();
    for (var exporter : serviceLoaderExporters) {
      if (exporter instanceof Comparable) {
        comparable.add(exporter);
      } else {
        nonComparable.add(exporter);
      }
    }
    comparable.sort((a, b) -> ((Comparable<InqEventExporter>) a).compareTo(b));

    var result = new ArrayList<InqEventExporter>(comparable.size() + nonComparable.size() + programmatic.size());
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
   * Registers an exporter programmatically.
   *
   * <p>Must be called before the first event is exported. Registrations after
   * the first export throw {@link IllegalStateException} (ADR-014, Convention 5).
   *
   * <p>Lock-free: uses CAS to append to the immutable programmatic list.
   *
   * @param exporter the exporter to register
   * @throws IllegalStateException if the registry is already frozen
   */
  public void register(InqEventExporter exporter) {
    Objects.requireNonNull(exporter, "exporter must not be null");
    while (true) {
      var current = state.get();
      if (current instanceof Frozen) {
        throw new IllegalStateException(
            "InqEventExporterRegistry is frozen — exporters must be registered " +
                "before the first event is exported.");
      }
      var open = (Open) current;
      var updated = new ArrayList<>(open.programmatic);
      updated.add(exporter);
      var next = new Open(List.copyOf(updated));
      if (state.compareAndSet(current, next)) {
        return;
      }
    }
  }

  /**
   * Exports an event to all registered exporters.
   *
   * <p>On first call, triggers ServiceLoader discovery and freezes the registry.
   * Exporter exceptions are caught and logged — they never propagate.
   *
   * @param event the event to export
   */
  public void export(InqEvent event) {
    for (var exporter : getExporters()) {
      try {
        if (isSubscribed(exporter, event)) {
          exporter.export(event);
        }
      } catch (Throwable t) {
        rethrowIfFatal(t);
        LOGGER.warn(
            "[{}] InqEventExporter {} threw on event {}: {}",
            event.getCallId(), exporter.getClass().getName(),
            event.getClass().getSimpleName(), t.getMessage());
      }
    }
  }

  /**
   * Returns the resolved exporter list, triggering discovery on first access.
   *
   * <p>Uses a CAS retry loop to atomically transition from {@code Open} to
   * {@code Frozen}. If a concurrent {@code register()} changes the {@code Open}
   * state between our read and our CAS, we re-read and retry.
   */
  private List<InqEventExporter> getExporters() {
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

  private record Open(List<InqEventExporter> programmatic) implements RegistryState {
    Open() {
      this(List.of());
    }
  }

  private record Frozen(List<InqEventExporter> resolved) implements RegistryState {
  }
}
