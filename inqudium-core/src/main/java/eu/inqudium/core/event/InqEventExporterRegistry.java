package eu.inqudium.core.event;

import java.lang.ref.WeakReference;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static eu.inqudium.core.exception.InqException.rethrowIfFatal;

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
  private static final long PARK_MAX_NANOS = 100_000_000;
  private static final long RESOLVE_TIMEOUT_NANOS = 30_000_000_000L;
  private static final long REGISTER_WAIT_NANOS = 5_000_000_000L;
  private static final int MAX_CONSECUTIVE_HAS_NEXT_FAILURES = 10;

  // ── Global default instance ──

  private static final AtomicReference<InqEventExporterRegistry> DEFAULT_INSTANCE = new AtomicReference<>();
  private static final String CONSTRUCTION_PHASE = "construction";
  private final AtomicReference<RegistryState> state = new AtomicReference<>(new Open());

  // FIX #2: volatile ensures cross-thread visibility when nulled after freeze,
  // independent of the AtomicReference happens-before on `state`.
  private volatile WeakReference<ClassLoader> spiClassLoaderRef;

  // ── State machine ──

  public InqEventExporterRegistry() {
    var tccl = Thread.currentThread().getContextClassLoader();
    // Wir erfassen den TCCL zur Erstellungszeit, halten ihn aber nicht stark fest
    this.spiClassLoaderRef = new WeakReference<>(
        tccl != null ? tccl : InqEventExporter.class.getClassLoader()
    );
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

  /**
   * FIX #3: Recursively checks the entire type hierarchy for Comparable&lt;InqEventExporter&gt;.
   *
   * <p>The original implementation only checked directly implemented interfaces,
   * missing cases where Comparable was declared on a superclass. This version
   * walks the full class hierarchy including superclasses.
   */
  private static boolean isCorrectlyComparable(InqEventExporter exporter) {
    if (!(exporter instanceof Comparable)) {
      return false;
    }

    // Walk the entire type hierarchy: the class itself, all superclasses,
    // and all interfaces at each level
    Class<?> current = exporter.getClass();
    while (current != null && current != Object.class) {
      if (checkInterfacesForComparable(current.getGenericInterfaces())) {
        return true;
      }
      current = current.getSuperclass();
    }

    return false;
  }

  /**
   * Checks a set of generic interfaces for Comparable&lt;InqEventExporter&gt;.
   */
  private static boolean checkInterfacesForComparable(Type[] interfaces) {
    for (Type type : interfaces) {
      if (type instanceof ParameterizedType pType) {
        if (pType.getRawType() == Comparable.class) {
          Type[] typeArgs = pType.getActualTypeArguments();
          return typeArgs.length == 1 && typeArgs[0] == InqEventExporter.class;
        }
      }
    }
    return false;
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
              CONSTRUCTION_PHASE, t.toString(), Instant.now()));
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
              CONSTRUCTION_PHASE, t.toString(), Instant.now()));
        }
      }
    } catch (Throwable t) {
      rethrowIfFatal(t);
      LOGGER.warn("ServiceLoader discovery for InqEventExporter failed.", t);
      providerErrors.add(new InqProviderErrorEvent(
          "(unknown)", InqEventExporter.class.getName(),
          CONSTRUCTION_PHASE, t.toString(), Instant.now()));
    }

    // Sort: Comparable first (ascending), then non-Comparable
    var comparable = new ArrayList<InqEventExporter>();
    var nonComparable = new ArrayList<InqEventExporter>();
    for (var exporter : serviceLoaderExporters) {
      if (exporter instanceof Comparable) {
        if (isCorrectlyComparable(exporter)) {
          comparable.add(exporter);
        } else {
          // Incorrectly implemented! We log this and downgrade it to "nonComparable"
          String errorMsg = "Provider implements Comparable incorrectly. Expected Comparable<InqEventExporter>. Sorting ignored.";
          LOGGER.warn("[{}] {}", exporter.getClass().getName(), errorMsg);

          providerErrors.add(new InqProviderErrorEvent(
              exporter.getClass().getName(),
              InqEventExporter.class.getName(),
              CONSTRUCTION_PHASE,
              errorMsg,
              Instant.now()
          ));

          nonComparable.add(exporter);
        }
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
          types = typeSet.stream()
              .filter(Objects::nonNull)
              .collect(java.util.stream.Collectors.toUnmodifiableSet());

        }
      } catch (Throwable t) {
        rethrowIfFatal(t);
        LOGGER.warn("InqEventExporter.subscribedEventTypes() threw — exporter will receive all events.", t);
      }
      cached.add(new CachedExporter(exporter, types));
    }
    return new DiscoveryResult(List.copyOf(cached), List.copyOf(providerErrors));
  }

  private ClassLoader getClassLoader() {
    ClassLoader classLoader = null;
    WeakReference<ClassLoader> ref = this.spiClassLoaderRef;
    if (ref != null) {
      classLoader = ref.get();
    }

    // Fallback if the garbage collector has already cleared it
    // (or if tccl was null in the constructor and the fallback applies)
    if (classLoader == null) {
      classLoader = InqEventExporter.class.getClassLoader();
    }
    return classLoader;
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
          // Separate try-blocks so that a replay failure does not
          // reset the successfully frozen registry back to Open.
          List<CachedExporter> exporters;
          List<InqProviderErrorEvent> providerErrors;
          try {
            var result = discoverAndMerge(open.programmatic, getClassLoader());
            exporters = result.exporters;
            providerErrors = result.providerErrors;
          } catch (Throwable t) {
            // Discovery itself failed — reset to Open so next caller can retry
            state.compareAndSet(resolving, new Open(List.copyOf(open.programmatic)));
            rethrowIfFatal(t);
            LOGGER.error("ServiceLoader discovery failed — registry reset to Open", t);
            return List.of();
          }

          // Use CAS instead of unconditional set. If another thread
          // performed a timeout-reset (Resolving→Open) while we were discovering,
          // our resolving instance is no longer current. In that case, discard our
          // result and use whatever state the winning thread established.
          if (!state.compareAndSet(resolving, new Frozen(exporters))) {
            LOGGER.debug("Resolver CAS failed — another thread reset the state. " +
                "Discarding discovery result and retrying.");
            // Loop will re-read state and either find Frozen or Open
            continue;
          }

          // Only null the classloader after successful CAS — guaranteed
          // that we are the thread that actually froze the registry.
          this.spiClassLoaderRef = null;

          // Replay errors in a separate try-block. A failure here must
          // not undo the successfully frozen state.
          try {
            replayProviderErrors(exporters, providerErrors);
          } catch (Throwable t) {
            rethrowIfFatal(t);
            LOGGER.warn("Provider error replay failed — registry is frozen, replay skipped", t);
          }

          return exporters;
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

  /**
   * Resets this registry to its initial open state.
   *
   * <p><strong>Testing only.</strong> This method is intended for test isolation —
   * it allows a frozen registry to be reused across test methods without creating
   * a new instance. Not safe to call while events are being published concurrently.
   *
   * <p>After reset, the registry accepts new {@link #register(InqEventExporter)}
   * calls and will re-discover ServiceLoader providers on the next
   * {@link #export(InqEvent)} call.
   */
  void reset() {
    state.set(new Open());
    var tccl = Thread.currentThread().getContextClassLoader();
    this.spiClassLoaderRef = new WeakReference<>(
        tccl != null ? tccl : InqEventExporter.class.getClassLoader()
    );
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
