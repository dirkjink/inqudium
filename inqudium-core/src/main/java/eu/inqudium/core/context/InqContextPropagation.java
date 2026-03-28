package eu.inqudium.core.context;

import eu.inqudium.core.InqElementType;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class that encapsulates the context propagation lifecycle.
 *
 * <p>Element implementations use this as a one-liner instead of manually
 * iterating over propagators:
 * <pre>{@code
 * try (var scope = InqContextPropagation.activateFor(callId, elementName, elementType)) {
 *     return protectedCall.execute();
 * }
 * }</pre>
 *
 * <p>If no propagators are registered, the returned scope is a no-op with
 * near-zero overhead.
 *
 * @since 0.1.0
 */
public final class InqContextPropagation {

  private InqContextPropagation() {
  }

  /**
   * Captures context from all registered propagators, restores it on the
   * current thread, and enriches it with Inqudium-specific entries.
   *
   * <p>The returned scope must be closed in a finally block to restore
   * the previous context.
   *
   * @param callId      the unique call identifier
   * @param elementName the element instance name
   * @param elementType the element type
   * @return a scope that restores all contexts when closed
   */
  public static InqContextScope activateFor(String callId, String elementName, InqElementType elementType) {
    var propagators = InqContextPropagatorRegistry.getPropagators();
    if (propagators.isEmpty()) {
      return InqContextScope.NOOP;
    }

    var scopes = new ArrayList<InqContextScope>(propagators.size());
    var snapshots = captureAll(propagators);

    for (int i = 0; i < propagators.size(); i++) {
      var propagator = propagators.get(i);
      try {
        var scope = propagator.restore(snapshots.get(i));
        propagator.enrich(callId, elementName, elementType);
        scopes.add(scope);
      } catch (Exception e) {
        System.getLogger(InqContextPropagation.class.getName())
            .log(System.Logger.Level.WARNING,
                "Context propagator {0} failed during restore/enrich: {1}",
                propagator.getClass().getName(), e.getMessage());
      }
    }

    return compositeScope(scopes);
  }

  /**
   * Captures context from all registered propagators without restoring.
   *
   * <p>Used by paradigm modules that need to capture on one thread/scope
   * and restore on another (e.g. Kotlin's CoroutineContext element).
   *
   * @return a list of snapshots, one per registered propagator (same order)
   */
  public static List<InqContextSnapshot> captureAll() {
    return captureAll(InqContextPropagatorRegistry.getPropagators());
  }

  private static List<InqContextSnapshot> captureAll(List<InqContextPropagator> propagators) {
    var snapshots = new ArrayList<InqContextSnapshot>(propagators.size());
    for (var propagator : propagators) {
      try {
        snapshots.add(propagator.capture());
      } catch (Exception e) {
        System.getLogger(InqContextPropagation.class.getName())
            .log(System.Logger.Level.WARNING,
                "Context propagator {0} failed during capture: {1}",
                propagator.getClass().getName(), e.getMessage());
        snapshots.add(NoopSnapshot.INSTANCE);
      }
    }
    return snapshots;
  }

  /**
   * Restores previously captured snapshots and enriches with Inqudium entries.
   *
   * <p>Used by paradigm modules (e.g. Kotlin CoroutineContext element) that
   * captured on one scope and restore on another.
   *
   * @param snapshots   the captured snapshots (from {@link #captureAll()})
   * @param callId      the unique call identifier
   * @param elementName the element instance name
   * @param elementType the element type
   * @return a composite scope that restores all contexts when closed
   */
  public static InqContextScope restoreAndEnrich(List<InqContextSnapshot> snapshots,
                                                 String callId, String elementName, InqElementType elementType) {
    var propagators = InqContextPropagatorRegistry.getPropagators();
    if (propagators.isEmpty()) {
      return InqContextScope.NOOP;
    }

    var scopes = new ArrayList<InqContextScope>(propagators.size());
    for (int i = 0; i < propagators.size() && i < snapshots.size(); i++) {
      var propagator = propagators.get(i);
      try {
        var scope = propagator.restore(snapshots.get(i));
        propagator.enrich(callId, elementName, elementType);
        scopes.add(scope);
      } catch (Exception e) {
        System.getLogger(InqContextPropagation.class.getName())
            .log(System.Logger.Level.WARNING,
                "Context propagator {0} failed during restore/enrich: {1}",
                propagator.getClass().getName(), e.getMessage());
      }
    }

    return compositeScope(scopes);
  }

  private static InqContextScope compositeScope(List<InqContextScope> scopes) {
    if (scopes.isEmpty()) {
      return InqContextScope.NOOP;
    }
    if (scopes.size() == 1) {
      return scopes.getFirst();
    }
    // Close in reverse order
    return () -> {
      for (int i = scopes.size() - 1; i >= 0; i--) {
        try {
          scopes.get(i).close();
        } catch (Exception e) {
          System.getLogger(InqContextPropagation.class.getName())
              .log(System.Logger.Level.WARNING,
                  "Context scope close failed: {0}", e.getMessage());
        }
      }
    };
  }

  private enum NoopSnapshot implements InqContextSnapshot {
    INSTANCE
  }
}
