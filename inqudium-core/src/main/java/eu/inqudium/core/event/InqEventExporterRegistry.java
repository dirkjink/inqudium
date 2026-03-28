package eu.inqudium.core.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * @since 0.1.0
 */
public final class InqEventExporterRegistry {

  private static final System.Logger LOGGER = System.getLogger(InqEventExporterRegistry.class.getName());

  private static final CopyOnWriteArrayList<InqEventExporter> programmatic = new CopyOnWriteArrayList<>();
  private static final AtomicBoolean frozen = new AtomicBoolean(false);
  private static volatile List<InqEventExporter> resolved;

  private InqEventExporterRegistry() {
    // utility class
  }

  /**
   * Registers an exporter programmatically.
   *
   * <p>Must be called before the first event is exported. Registrations after
   * the first export throw {@link IllegalStateException} (ADR-014, Convention 5).
   *
   * @param exporter the exporter to register
   * @throws IllegalStateException if the registry is already frozen
   */
  public static void register(InqEventExporter exporter) {
    Objects.requireNonNull(exporter, "exporter must not be null");
    if (frozen.get()) {
      throw new IllegalStateException(
          "InqEventExporterRegistry is frozen — exporters must be registered before the first event is exported.");
    }
    programmatic.add(exporter);
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
      } catch (Exception e) {
        LOGGER.log(System.Logger.Level.WARNING,
            "InqEventExporter {0} threw on event {1}: {2}",
            exporter.getClass().getName(), event.getClass().getSimpleName(), e.getMessage());
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

  private static List<InqEventExporter> getExporters() {
    var result = resolved;
    if (result != null) {
      return result;
    }
    synchronized (InqEventExporterRegistry.class) {
      if (resolved != null) {
        return resolved;
      }
      resolved = discoverAndMerge();
      frozen.set(true);
      return resolved;
    }
  }

  @SuppressWarnings("unchecked")
  private static List<InqEventExporter> discoverAndMerge() {
    var serviceLoaderExporters = new ArrayList<InqEventExporter>();

    try {
      var loader = ServiceLoader.load(InqEventExporter.class);
      for (var provider : loader) {
        try {
          serviceLoaderExporters.add(provider);
        } catch (Exception e) {
          LOGGER.log(System.Logger.Level.WARNING,
              "Failed to instantiate InqEventExporter: {0} — Provider skipped.",
              e.getMessage());
        }
      }
    } catch (Exception e) {
      LOGGER.log(System.Logger.Level.WARNING,
          "ServiceLoader discovery for InqEventExporter failed: {0}", e.getMessage());
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
   * Resets the registry for testing purposes.
   * <strong>Not for production use.</strong>
   */
  static void reset() {
    synchronized (InqEventExporterRegistry.class) {
      resolved = null;
      frozen.set(false);
      programmatic.clear();
    }
  }
}
