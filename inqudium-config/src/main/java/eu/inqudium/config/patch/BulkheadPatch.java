package eu.inqudium.config.patch;

import eu.inqudium.config.lifecycle.ComponentField;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadField;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.BulkheadStrategyConfig;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Mutable, single-use patch carrying the field changes a DSL invocation or format-adapter
 * translation wants to apply to a {@link BulkheadSnapshot}.
 *
 * <p>The patch is built up incrementally via the {@code touchXxx(...)} methods. Each call records
 * the field as touched in an internal {@link EnumSet} and stores the new value. {@link #applyTo}
 * then produces a fresh snapshot in which touched fields take the new value and untouched fields
 * inherit from the base. The result is validated by the snapshot's compact constructor like any
 * other snapshot — there is no path through this patch that produces an invalid snapshot.
 *
 * <p>The class is not thread-safe. Patches are short-lived: the DSL builder constructs one,
 * populates it, and applies it once. They are not shared across threads.
 *
 * <p>The {@code touchXxx} methods are {@code public} because the DSL builders that drive them live
 * in paradigm modules outside this package. They are not part of the application-facing API and
 * should not be invoked from user code; the documented entry point is the DSL.
 */
public final class BulkheadPatch implements ComponentPatch<BulkheadSnapshot> {

    private final EnumSet<BulkheadField> touched = EnumSet.noneOf(BulkheadField.class);

    private String name;
    private int maxConcurrentCalls;
    private Duration maxWaitDuration;
    private Set<String> tags;
    private String derivedFromPreset;
    private BulkheadEventConfig events;
    private BulkheadStrategyConfig strategy;

    /**
     * Mark {@link BulkheadField#NAME} as touched and record the new value.
     *
     * @param value the new name; nullability and blank-ness are validated by the snapshot's
     *              compact constructor when {@link #applyTo} runs.
     */
    public void touchName(String value) {
        touched.add(BulkheadField.NAME);
        this.name = value;
    }

    /**
     * Mark {@link BulkheadField#MAX_CONCURRENT_CALLS} as touched and record the new value.
     *
     * @param value the new limit; positivity is validated by the snapshot's compact constructor.
     */
    public void touchMaxConcurrentCalls(int value) {
        touched.add(BulkheadField.MAX_CONCURRENT_CALLS);
        this.maxConcurrentCalls = value;
    }

    /**
     * Mark {@link BulkheadField#MAX_WAIT_DURATION} as touched and record the new value.
     *
     * @param value the new wait duration; nullability and non-negativity are validated by the
     *              snapshot's compact constructor.
     */
    public void touchMaxWaitDuration(Duration value) {
        touched.add(BulkheadField.MAX_WAIT_DURATION);
        this.maxWaitDuration = value;
    }

    /**
     * Mark {@link BulkheadField#TAGS} as touched and record the new value.
     *
     * @param value the new tag set; the snapshot's compact constructor defensively copies it.
     *              {@code null} is allowed and resolves to an empty set in the produced snapshot.
     */
    public void touchTags(Set<String> value) {
        touched.add(BulkheadField.TAGS);
        this.tags = value;
    }

    /**
     * Mark {@link BulkheadField#DERIVED_FROM_PRESET} as touched and record the new value.
     *
     * @param value the preset label, or {@code null} to clear the previous preset baseline.
     *              Blank strings are rejected by the snapshot's compact constructor.
     */
    public void touchDerivedFromPreset(String value) {
        touched.add(BulkheadField.DERIVED_FROM_PRESET);
        this.derivedFromPreset = value;
    }

    /**
     * Mark {@link BulkheadField#EVENTS} as touched and record the new value.
     *
     * @param value the new event-gating configuration; non-null is enforced by the snapshot's
     *              compact constructor.
     */
    public void touchEvents(BulkheadEventConfig value) {
        touched.add(BulkheadField.EVENTS);
        this.events = value;
    }

    /**
     * Mark {@link BulkheadField#STRATEGY} as touched and record the new value.
     *
     * <p>The paradigm hot phase materializes the matching strategy at the cold-to-hot
     * transition and re-materializes on a snapshot-side strategy change after the
     * mutability check has accepted the swap. The patch itself is paradigm-agnostic — it
     * simply transports the new {@link BulkheadStrategyConfig} value.
     *
     * @param value the new strategy configuration; non-null is enforced by the snapshot's
     *              compact constructor on apply.
     */
    public void touchStrategy(BulkheadStrategyConfig value) {
        touched.add(BulkheadField.STRATEGY);
        this.strategy = value;
    }

    /**
     * @return an immutable view of the fields this patch will overwrite when applied. The
     *         {@link eu.inqudium.config.lifecycle.ChangeRequest ChangeRequest} the dispatcher
     *         hands to listeners and to the component-internal mutability check is built from
     *         this set plus the corresponding values.
     */
    @Override
    public Set<BulkheadField> touchedFields() {
        return Collections.unmodifiableSet(EnumSet.copyOf(touched));
    }

    @Override
    public Map<ComponentField, Object> proposedValues() {
        if (touched.isEmpty()) {
            return Map.of();
        }
        Map<ComponentField, Object> values = new HashMap<>(touched.size() * 2);
        for (BulkheadField field : touched) {
            values.put(field, valueOf(field));
        }
        return Collections.unmodifiableMap(values);
    }

    private Object valueOf(BulkheadField field) {
        return switch (field) {
            case NAME -> name;
            case MAX_CONCURRENT_CALLS -> maxConcurrentCalls;
            case MAX_WAIT_DURATION -> maxWaitDuration;
            case TAGS -> tags;
            case DERIVED_FROM_PRESET -> derivedFromPreset;
            case EVENTS -> events;
            case STRATEGY -> strategy;
        };
    }

    /**
     * @param field the field to inspect.
     * @return {@code true} iff the patch will overwrite this field on application.
     */
    public boolean isTouched(BulkheadField field) {
        Objects.requireNonNull(field, "field");
        return touched.contains(field);
    }

    @Override
    public BulkheadSnapshot applyTo(BulkheadSnapshot base) {
        Objects.requireNonNull(base, "base");
        return new BulkheadSnapshot(
                touched.contains(BulkheadField.NAME) ? name : base.name(),
                touched.contains(BulkheadField.MAX_CONCURRENT_CALLS)
                        ? maxConcurrentCalls : base.maxConcurrentCalls(),
                touched.contains(BulkheadField.MAX_WAIT_DURATION)
                        ? maxWaitDuration : base.maxWaitDuration(),
                touched.contains(BulkheadField.TAGS) ? tags : base.tags(),
                touched.contains(BulkheadField.DERIVED_FROM_PRESET)
                        ? derivedFromPreset : base.derivedFromPreset(),
                touched.contains(BulkheadField.EVENTS) ? events : base.events(),
                touched.contains(BulkheadField.STRATEGY) ? strategy : base.strategy());
    }
}
