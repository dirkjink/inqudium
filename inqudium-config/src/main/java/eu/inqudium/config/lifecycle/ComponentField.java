package eu.inqudium.config.lifecycle;

/**
 * Marker for the typed field enums that each component publishes to identify patchable fields.
 *
 * <p>Each component defines an enum (e.g. {@code BulkheadField}, {@code CircuitBreakerField}) that
 * implements this interface. The enum is the lookup key for {@link ChangeRequest#proposedValue}
 * and the membership type of {@link ChangeRequest#touchedFields()}. Using an enum per component
 * keeps the API typed and makes exhaustive switches in listener code safe.
 */
public interface ComponentField {

    /**
     * @return the field's stable identifier — by convention the enum constant name.
     */
    String name();
}
