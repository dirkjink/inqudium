package eu.inqudium.core.event;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Global registry for {@link InqEventExporter} instances.
 *
 * <p>Exporters receive all events from all elements in the application.
 * Registration follows ADR-014 conventions:
 * <ul>
 *   <li>ServiceLoader discovery: lazy on first access, cached for JVM lifetime.</li>
 *   <li>Comparable ordering: Comparable exporters sorted first, then non-Comparable,
 *       then programmatically registered.</li>
 *   <li>Error isolation: a failing exporter is caught and logged, never affects
 *       other exporters or the resilience element.</li>
 *   <li>Frozen after first access: programmatic registration must happen before
 *       the first {@link #export(InqEvent)} call.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>All state is held in a single {@link AtomicReference AtomicReference&lt;RegistryState&gt;}.
 * Both {@link #register(InqEventExporter)} and {@link #getExporters()} use CAS
 * operations — no {@code synchronized}, no locks, safe for virtual threads.
 *
 * <p>The state transitions are:
 * <pre>{@code
 * Open(programmatic=[])  ──register()──►  Open(programmatic=[a])
 *                         ──register()──►  Open(programmatic=[a, b])
 *                         ──getExporters()──►  Frozen(resolved=[...])
 * }</pre>
 * Once frozen, any {@code register()} call fails with {@link IllegalStateException}.
 *
 * @since 0.1.0
 */
public final class InqEventExporterRegistry {

  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(InqEventExporterRegistry.class);

  // ── State machine ──
  private static final AtomicReference<RegistryState> STATE = new AtomicReference<>(new Open());

  private InqEventExporterRegistry() {
    // utility class
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
  public static void register(InqEventExporter exporter) {
    Objects.requireNonNull(exporter, "exporter must not be null");
    while (true) {
      var current = STATE.get();
      if (current instanceof Frozen) {
        throw new IllegalStateException(
            "InqEventExporterRegistry is frozen — exporters must be registered " +
                "before the first event is exported.");
      }
      var open = (Open) current;
      // Build a new immutable list with the new exporter appended
      var updated = new ArrayList<>(open.programmatic);
      updated.add(exporter);
      var next = new Open(List.copyOf(updated));
      if (STATE.compareAndSet(current, next)) {
        return;
      }
      // CAS failed — another thread modified state concurrently, retry
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
  static void export(InqEvent event) {
    for (var exporter : getExporters()) {
      try {
        if (isSubscribed(exporter, event)) {
          exporter.export(event);
        }
      } catch (Throwable t) {
        LOGGER.warn(
            "[{}] InqEventExporter {} threw on event {}: {}",
            event.getCallId(), exporter.getClass().getName(),
            event.getClass().getSimpleName(), t.getMessage());
      }
    }
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

  /**
   * Returns the resolved exporter list, triggering discovery on first access.
   *
   * <p>Uses CAS to atomically transition from {@code Open} to {@code Frozen}.
   * The programmatic list is read from the same {@code Open} state that is
   * being replaced — no window for concurrent {@code register()} to slip in.
   */
  private static List<InqEventExporter> getExporters() {
    var current = STATE.get();
    if (current instanceof Frozen frozen) {
      return frozen.resolved;
    }
    // Transition from Open → Frozen via CAS
    // Multiple threads may race here — only the CAS winner performs discovery
    var open = (Open) current;
    var resolved = discoverAndMerge(open.programmatic);
    var frozen = new Frozen(resolved);
    if (STATE.compareAndSet(current, frozen)) {
      return resolved;
    }
    // CAS failed — another thread won the race and already froze
    // Read the winner's resolved list
    return ((Frozen) STATE.get()).resolved;
  }

  @SuppressWarnings("unchecked")
  private static List<InqEventExporter> discoverAndMerge(List<InqEventExporter> programmatic) {
    var serviceLoaderExporters = new ArrayList<InqEventExporter>();

    // Use explicit Iterator and catch Throwable — ServiceLoader throws
    // ServiceConfigurationError (an Error, not Exception) when a provider
    // cannot be instantiated.
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
          LOGGER.warn("Failed to load InqEventExporter provider: {} — Provider skipped.",
              t.getMessage());
          logProviderError(t);
        }
      }
    } catch (Throwable t) {
      LOGGER.warn("ServiceLoader discovery for InqEventExporter failed: {}", t.getMessage());
      logProviderError(t);
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

  /**
   * Logs a provider error for diagnostics.
   * Called during discovery — best-effort, never crashes the discovery process.
   */
  private static void logProviderError(Throwable t) {
    try {
      var phase = (t instanceof java.util.ServiceConfigurationError) ? "construction" : "execution";
      var className = extractProviderClassName(t);
      LOGGER.warn("Provider error [{}]: {} in phase '{}' — {}",
          InqEventExporter.class.getSimpleName(), className, phase, t.getMessage());
    } catch (Throwable suppressed) {
      // Best-effort — never let provider error reporting crash discovery
      LOGGER.debug("Could not log provider error: {}", suppressed.getMessage());
    }
  }

  private static String extractProviderClassName(Throwable t) {
    var msg = t.getMessage();
    if (msg != null && msg.startsWith("Provider ")) {
      var end = msg.indexOf(' ', 9);
      if (end > 0) {
        return msg.substring(9, end);
      }
    }
    return "(unknown)";
  }

  /**
   * Resets the registry for testing purposes.
   * <strong>Not for production use.</strong>
   */
  static void reset() {
    STATE.set(new Open());
  }

  private sealed interface RegistryState permits Open, Frozen {
  }

  /**
   * Accepts registrations. Contains the accumulated programmatic exporters.
   */
  private record Open(List<InqEventExporter> programmatic) implements RegistryState {
    Open() {
      this(List.of());
    }
  }

  /**
   * Resolved and frozen. No more registrations accepted.
   */
  private record Frozen(List<InqEventExporter> resolved) implements RegistryState {
  }
}
