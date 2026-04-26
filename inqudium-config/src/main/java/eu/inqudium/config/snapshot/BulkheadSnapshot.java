package eu.inqudium.config.snapshot;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable configuration state of a single bulkhead.
 *
 * <p>The compact constructor enforces the class-2 invariants from ADR-027: non-blank name,
 * positive {@code maxConcurrentCalls}, non-negative {@code maxWaitDuration}, immutable tag set,
 * and a non-blank {@code derivedFromPreset} when set (the field itself is nullable to express
 * "no preset baseline"). The record is paradigm-agnostic per the phase-1 clarifications in
 * {@code REFACTORING.md}: a bulkhead's paradigm is carried by its handle and live container,
 * never by the snapshot.
 *
 * @param name                 the bulkhead's stable name; non-null and non-blank.
 * @param maxConcurrentCalls   the maximum number of concurrent calls; strictly positive.
 * @param maxWaitDuration      the maximum time a caller waits for a permit; non-null and
 *                             non-negative. {@link Duration#ZERO} means fail-fast.
 * @param tags                 operational tags; never null, defensively copied to an immutable
 *                             set.
 * @param derivedFromPreset    the preset label this snapshot was derived from
 *                             ({@code "protective"}, {@code "balanced"}, {@code "permissive"}),
 *                             or {@code null} if the snapshot was assembled without a preset
 *                             baseline. When non-null, must be non-blank.
 */
public record BulkheadSnapshot(
        String name,
        int maxConcurrentCalls,
        Duration maxWaitDuration,
        Set<String> tags,
        String derivedFromPreset)
        implements ComponentSnapshot {

    public BulkheadSnapshot {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (maxConcurrentCalls <= 0) {
            throw new IllegalArgumentException(
                    "maxConcurrentCalls must be positive, got: " + maxConcurrentCalls);
        }
        Objects.requireNonNull(maxWaitDuration, "maxWaitDuration");
        if (maxWaitDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "maxWaitDuration must not be negative, got: " + maxWaitDuration);
        }
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        if (derivedFromPreset != null && derivedFromPreset.isBlank()) {
            throw new IllegalArgumentException("derivedFromPreset must not be blank when set");
        }
    }
}
