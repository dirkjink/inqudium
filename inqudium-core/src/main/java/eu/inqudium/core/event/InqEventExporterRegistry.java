package eu.inqudium.core.event;

import java.time.Instant;
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
 * <h2>Provider error audit trail</h2>
 * <p>When a ServiceLoader provider fails during discovery, the error is logged
 * <strong>and</strong> an {@link InqProviderErrorEvent} is collected. After the
 * registry freezes, these events are replayed to all successfully resolved exporters
 * — providing a full audit trail even for the bootstrap phase.
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
 * and a bounded total timeout measured via {@link System#nanoTime()} (immune to
 * spurious wakeups). If the resolver thread dies, state resets to {@code Open}.
 *
 * @since 0.1.0
 */
public final class InqEventExporterRegistry {

  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(InqEventExporterRegistry.class);

  private static final long PARK_INITIAL_NANOS = 1_000;
  private static final long PARK_MAX_NANOS = 1_000_000;
  private static final long RESOLVE_TIMEOUT_NANOS = 30_000_000_000L;
  private static final long REGISTER_WAIT_NANOS = 5_000_000_000L;
  private static final int MAX_CONSECUTIVE_HAS_NEXT_FAILURES = 10;

  // ── Global default instance ──

  private static final AtomicReference<InqEventExporterRegistry> DEFAULT_INSTANCE = new AtomicReference<>();
  private final AtomicReference<RegistryState> state = new AtomicReference<>(new Open());
  private final ClassLoader spiClassLoader;

  // ── State machine ──

  public InqEventExporterRegistry() {
    var tccl = Thread.currentThread().getContextClassLoader();
    this.spiClassLoader = tccl != null ? tccl : InqEventExporter.class.getClassLoader();
  }

  public static InqEventExporterRegistry getDefault() {
    var instance = DEFAULT_INSTANCE.get();
    if (instance != null) {
      return instance;
    }
    DEFAULT_INSTANCE.compareAndSet(null, new InqEventExporterRegistry());
    return DEFAULT_INSTANCE.get();
  }

  public static void setDefault(InqEventExporterRegistry registry) {
    DEFAULT_INSTANCE.set(registry);
  }

  @SuppressWarnings("unchecked")
  private static DiscoveryResult discoverAndMerge(List<InqEventExporter> programmatic,
                                                  ClassLoader classLoader) {
    var serviceLoaderExporters = new ArrayList<InqEventExporter>();
    var providerErrors = new ArrayList<InqProviderErrorEvent>();

    try {
      var loader = ServiceLoader.load(InqEventExporter.class, classLoader);
      Iterator<InqEventExporter> iterator = loader.iterator();
      int consecutiveHasNextFailures = 0;
      while (true) {
        boolean hasNext;
        try {
          hasNext = iterator.hasNext();
          consecutiveHasNextFailures = 0;
        } catch (Throwable t) {
          rethrowIfFatal(t);
          consecutiveHasNextFailures++;
          LOGGER.warn("ServiceLoader iterator.hasNext() failed for InqEventExporter " +
              "(consecutive failure #{}) — retrying.", consecutiveHasNextFailures, t);
          providerErrors.add(new InqProviderErrorEvent(
              "(unknown)", InqEventExporter.class.getName(),
              "construction", t.toString(), Instant.now()));
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
          serviceLoaderExporters.add(iterator.next());
        } catch (Throwable t) {
          rethrowIfFatal(t);
          LOGGER.warn("Failed to load InqEventExporter provider — provider skipped.", t);
          providerErrors.add(new InqProviderErrorEvent(
              "(unknown)", InqEventExporter.class.getName(),
              "construction", t.toString(), Instant.now()));
        }
      }
    } catch (Throwable t) {
      rethrowIfFatal(t);
      LOGGER.warn("ServiceLoader discovery for InqEventExporter failed.", t);
      providerErrors.add(new InqProviderErrorEvent(
          "(unknown)", InqEventExporter.class.getName(),
          "construction", t.toString(), Instant.now()));
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

    // Cache each exporter's subscribed event types at freeze time (stable contract)
    var cached = new ArrayList<CachedExporter>(all.size());
    for (var exporter : all) {
      Set<Class<? extends InqEvent>> types = null;
      try {
        var typeSet = exporter.subscribedEventTypes();
        if (typeSet != null && !typeSet.isEmpty()) {
          types = Set.copyOf(typeSet);
        }
      } catch (Throwable t) {
        rethrowIfFatal(t);
        LOGGER.warn("InqEventExporter.subscribedEventTypes() threw — exporter will receive all events.", t);
      }
      cached.add(new CachedExporter(exporter, types));
    }
    return new DiscoveryResult(List.copyOf(cached), List.copyOf(providerErrors));
  }

  private static void rethrowIfFatal(Throwable t) {
    if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
    if (t instanceof ThreadDeath) throw (ThreadDeath) t;
    if (t instanceof LinkageError) throw (LinkageError) t;
  }

  /**
   * Registers an exporter programmatically.
   *
   * <p>If the registry is currently resolving, waits briefly for resolution
   * to complete. Only {@code Frozen} state causes an immediate rejection.
   *
   * @param exporter the exporter to register
   * @throws IllegalStateException if the registry is frozen or registration times out
   */
  public void register(InqEventExporter exporter) {
    Objects.requireNonNull(exporter, "exporter must not be null");
    long waitStart = System.nanoTime();
    long parkNanos = PARK_INITIAL_NANOS;
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
      if (current instanceof Frozen) {
        throw new IllegalStateException(
            "InqEventExporterRegistry is frozen — " +
                "exporters must be registered before the first event is exported.");
      }
      // Resolving — wait briefly
      long elapsed = System.nanoTime() - waitStart;
      if (elapsed >= REGISTER_WAIT_NANOS) {
        throw new IllegalStateException(
            "InqEventExporterRegistry has been resolving for " +
                (elapsed / 1_000_000) + "ms — registration timed out. " +
                "Exporters must be registered before the first event is exported.");
      }
      LockSupport.parkNanos(parkNanos);
      parkNanos = Math.min(parkNanos * 2, PARK_MAX_NANOS);
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
   * <p>After freezing, replays any {@link InqProviderErrorEvent}s collected
   * during discovery to all resolved exporters — providing a full audit trail.
   */
  private List<CachedExporter> getExporters() {
    long parkNanos = PARK_INITIAL_NANOS;
    long waitStart = System.nanoTime();
    while (true) {
      var current = state.get();

      if (current instanceof Frozen frozen) {
        return frozen.exporters;
      }

      if (current instanceof Open open) {
        var resolving = new Resolving(open.programmatic);
        if (state.compareAndSet(current, resolving)) {
          try {
            var result = discoverAndMerge(open.programmatic, spiClassLoader);
            state.set(new Frozen(result.exporters));
            // Replay provider errors to the now-frozen exporters
            replayProviderErrors(result.exporters, result.providerErrors);
            return result.exporters;
          } catch (Throwable t) {
            state.set(new Open(List.copyOf(open.programmatic)));
            rethrowIfFatal(t);
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

  /**
   * Replays provider error events collected during discovery to all resolved exporters.
   * Best-effort — exporter failures during replay are logged but never propagated.
   */
  private void replayProviderErrors(List<CachedExporter> exporters, List<InqProviderErrorEvent> errors) {
    if (errors.isEmpty()) {
      return;
    }
    for (var error : errors) {
      for (var cached : exporters) {
        try {
          if (cached.isSubscribed(error)) {
            cached.exporter.export(error);
          }
        } catch (Throwable t) {
          rethrowIfFatal(t);
          LOGGER.debug("Exporter {} failed during provider error replay — skipped",
              cached.exporter.getClass().getName(), t);
        }
      }
    }
  }

  private sealed interface RegistryState permits Open, Resolving, Frozen {
  }

  private record Open(List<InqEventExporter> programmatic) implements RegistryState {
    Open() {
      this(List.of());
    }
  }

  private record Resolving(List<InqEventExporter> programmatic) implements RegistryState {
  }

  private record Frozen(List<CachedExporter> exporters) implements RegistryState {
  }

  /**
   * Pairs an exporter with its pre-resolved event type filter.
   * {@code subscribedEventTypes()} is called once at freeze time (stable contract).
   */
  private record CachedExporter(InqEventExporter exporter, Set<Class<? extends InqEvent>> eventTypes) {
    boolean isSubscribed(InqEvent event) {
      if (eventTypes == null || eventTypes.isEmpty()) {
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

  /**
   * Result of ServiceLoader discovery — exporters + collected provider errors.
   */
  private record DiscoveryResult(
      List<CachedExporter> exporters,
      List<InqProviderErrorEvent> providerErrors
  ) {
  }
}
