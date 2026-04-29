package eu.inqudium.core.element.circuitbreaker;

/**
 * Result of a permission check against the circuit breaker.
 *
 * <p>Contains the (possibly updated) snapshot and whether the call is permitted.
 * The snapshot may differ from the input if a state transition occurred
 * (e.g., OPEN → HALF_OPEN when the wait duration expired).
 *
 * @param snapshot  the current or transitioned snapshot
 * @param permitted whether the call may proceed
 */
public record PermissionResult(
        CircuitBreakerSnapshot snapshot,
        boolean permitted
) {

    public static PermissionResult permitted(CircuitBreakerSnapshot snapshot) {
        return new PermissionResult(snapshot, true);
    }

    public static PermissionResult rejected(CircuitBreakerSnapshot snapshot) {
        return new PermissionResult(snapshot, false);
    }
}
