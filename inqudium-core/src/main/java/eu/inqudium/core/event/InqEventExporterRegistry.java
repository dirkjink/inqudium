package eu.inqudium.core.event;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

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
 * The state machine uses a {@code Resolving} intermediate state so that exactly one
 * thread performs ServiceLoader I/O. Waiting threads park with exponential backoff
 * (not busy-spin). If the resolver thread dies, the state falls back to {@code Open}
 * so another thread can retry — no permanent livelock.
 *
 * @since 0.1.0
 */
public final class InqEventExporterRegistry {

  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(InqEventExporterRegistry.class);

  private static final long PARK_INITIAL_NANOS = 1_000;       // 1 μs
  private static final long PARK_MAX_NANOS = 1_000_000;   // 1 ms

  // ── Global default instance ──

  private static final AtomicReference<InqEventExporterRegistry> DEFAULT_INSTANCE = new AtomicReference<>();
  private final AtomicReference<RegistryState> state = new AtomicReference<>(new Open());

  public InqEventExporterRegistry() {
  }

  // ── State machine ──

  /**
   * Returns the shared global registry instance.
   *
   * <p>Created atomically on first access via CAS — no split-brain risk.
   *
   * @return the global default registry
   */
  public static InqEventExporterRegistry getDefault() {
    var instance = DEFAULT_INSTANCE.get();
    if (instance != null) {
      return instance;
    }
    DEFAULT_INSTANCE.compareAndSet(null, new InqEventExporterRegistry());
    return DEFAULT_INSTANCE.get();
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
    DEFAULT_INSTANCE.set(registry);
  }

  @SuppressWarnings("unchecked")
  private static List<CachedExporter> discoverAndMerge(List<InqEventExporter> programmatic) {
    var serviceLoaderExporters = new ArrayList<InqEventExporter>();

    try {
      var loader = ServiceLoader.load(InqEventExporter.class);
      Iterator<InqEventExporter> iterator = loader.iterator();
      while (true) {
        // hasNext() and next() separated — a stuck hasNext() breaks
        // the loop instead of spinning forever
        boolean hasNext;
        try {
          hasNext = iterator.hasNext();
        } catch (Throwable t) {
          rethrowIfFatal(t);
          LOGGER.warn("ServiceLoader iterator.hasNext() failed for InqEventExporter " +
              "— remaining providers skipped.", t);
          break;
        }
        if (!hasNext) {
          break;
        }
        try {
          serviceLoaderExporters.add(iterator.next());
        } catch (Throwable t) {
          rethrowIfFatal(t);
          LOGGER.warn("Failed to load InqEventExporter provider — provider skipped.", t);
        }
      }
    } catch (Throwable t) {
      rethrowIfFatal(t);
      LOGGER.warn("ServiceLoader discovery for InqEventExporter failed.", t);
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

    var all = new ArrayList<InqEventExporter>(comparable.size() + nonComparable.size() + programmatic.size());
    all.addAll(comparable);
    all.addAll(nonComparable);
    all.addAll(programmatic);

    // Cache each exporter's subscribed event types at freeze time (Fix #6)
    var cached = new ArrayList<CachedExporter>(all.size());
    for (var exporter : all) {
      Class<? extends InqEvent>[] types = null;
      try {
        var typeSet = exporter.subscribedEventTypes();
        if (typeSet != null && !typeSet.isEmpty()) {
          types = typeSet.toArray(new Class[0]);
        }
      } catch (Throwable t) {
        rethrowIfFatal(t);
        LOGGER.warn("InqEventExporter.subscribedEventTypes() threw — exporter will receive all events.", t);
      }
      cached.add(new CachedExporter(exporter, types));
    }
    return List.copyOf(cached);
  }

  private static void rethrowIfFatal(Throwable t) {
    if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
    if (t instanceof LinkageError) throw (LinkageError) t;
  }

  /**
   * Registers an exporter programmatically.
   *
   * @param exporter the exporter to register
   * @throws IllegalStateException if the registry is already frozen or resolving
   */
  public void register(InqEventExporter exporter) {
    Objects.requireNonNull(exporter, "exporter must not be null");
    while (true) {
      var current = state.get();
      if (current instanceof Open open) {
        var updated = new ArrayList<>(open.programmatic);
        updated.add(exporter);
        var next = new Open(List.copyOf(updated));
        if (state.compareAndSet(current, next)) {
          return;
        }
        continue;
      }
      throw new IllegalStateException(
          "InqEventExporterRegistry is frozen — exporters must be registered " +
              "before the first event is exported.");
    }
  }

  /**
   * Exports an event to all registered exporters.
   *
   * @param event the event to export
   */
  public void export(InqEvent event) {
    for (var cached : getExporters()) {
      try {
        if (cached.isSubscribed(event)) {
          cached.exporter.export(event);
        }
      } catch (Throwable t) {
        rethrowIfFatal(t);
        LOGGER.warn("[{}] InqEventExporter {} threw on event {}",
            event.getCallId(), cached.exporter.getClass().getName(),
            event.getClass().getSimpleName(), t);
      }
    }
  }

  /**
   * Returns the resolved exporter list, triggering discovery on first access.
   *
   * <p>Only the thread that CAS-es {@code Open → Resolving} performs I/O.
   * If that thread fails (fatal error), it resets state to {@code Open} so
   * another thread can retry — no permanent livelock. Waiting threads park
   * with exponential backoff — no busy-spin.
   */
  private List<CachedExporter> getExporters() {
    long parkNanos = PARK_INITIAL_NANOS;
    while (true) {
      var current = state.get();

      if (current instanceof Frozen frozen) {
        return frozen.exporters;
      }

      if (current instanceof Open open) {
        var resolving = new Resolving(open.programmatic);
        if (state.compareAndSet(current, resolving)) {
          // We won the resolver role — perform discovery with livelock protection
          try {
            var resolved = discoverAndMerge(open.programmatic);
            state.set(new Frozen(resolved));
            return resolved;
          } catch (Throwable t) {
            // Reset to Open so another thread can retry — no permanent livelock
            state.set(new Open(List.copyOf(open.programmatic)));
            rethrowIfFatal(t);
            LOGGER.error("ServiceLoader discovery failed — registry reset to Open", t);
            return List.of();
          }
        }
        continue;
      }

      // Resolving — park with exponential backoff instead of busy-spin
      LockSupport.parkNanos(parkNanos);
      parkNanos = Math.min(parkNanos * 2, PARK_MAX_NANOS);
    }
  }

  private sealed interface RegistryState permits Open, Resolving, Frozen {
  }

  private record Open(List<InqEventExporter> programmatic) implements RegistryState {
    Open() {
      this(List.of());
    }
  }

  /**
   * Discovery in progress. Only one thread performs I/O; others park.
   */
  private record Resolving(List<InqEventExporter> programmatic) implements RegistryState {
  }

  /**
   * Resolved and frozen. Each exporter is paired with its cached event type filter.
   */
  private record Frozen(List<CachedExporter> exporters) implements RegistryState {
  }

  /**
   * Pairs an exporter with its pre-resolved event type filter.
   * {@code subscribedEventTypes()} is called once at freeze time — not on every event.
   */
  private record CachedExporter(InqEventExporter exporter, Class<? extends InqEvent>[] eventTypes) {
    boolean isSubscribed(InqEvent event) {
      if (eventTypes == null || eventTypes.length == 0) {
        return true;
      }
      for (var type : eventTypes) {
        if (type.isInstance(event)) {
          return true;
        }
      }
      return false;
    }
  }
}
