package eu.inqudium.config.dsl;

import eu.inqudium.config.patch.BulkheadPatch;
import eu.inqudium.config.runtime.ParadigmTag;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Abstract base implementation of {@link BulkheadBuilder} shared by every paradigm.
 *
 * <p>Owns the underlying {@link BulkheadPatch}, the {@code customized} flag that drives the
 * preset-then-customize discipline, and the validation logic that is identical across paradigms.
 * Concrete paradigm-specific subclasses live in their respective paradigm modules and add
 * paradigm-specific setters; they may override the inherited methods if they need covariant
 * return types but typically just inherit them as-is.
 *
 * <p>The component name is required at construction time. The base class validates it, marks
 * {@link eu.inqudium.config.snapshot.BulkheadField#NAME NAME} as touched on the patch, and
 * exposes the patch via {@link #toPatch()} so the runtime can extract it later. Setting the
 * name through the constructor (rather than via a setter) is the canonical way to identify a
 * component — it removes "forgot to set the name" failures by signature.
 *
 * <h2>Preset values</h2>
 *
 * <p>The three preset baselines hard-coded here:
 *
 * <ul>
 *   <li>{@code protective} — {@code maxConcurrentCalls = 10},
 *       {@code maxWaitDuration = Duration.ZERO} (fail-fast).</li>
 *   <li>{@code balanced} — {@code maxConcurrentCalls = 50},
 *       {@code maxWaitDuration = 500 ms}.</li>
 *   <li>{@code permissive} — {@code maxConcurrentCalls = 200},
 *       {@code maxWaitDuration = 5 s}.</li>
 * </ul>
 *
 * <p>Each preset additionally touches
 * {@link eu.inqudium.config.snapshot.BulkheadField#DERIVED_FROM_PRESET DERIVED_FROM_PRESET} with
 * its own label so that class-3 consistency rules (e.g. {@code BULKHEAD_PROTECTIVE_WITH_LONG_WAIT})
 * can recognise the preset baseline of a snapshot.
 *
 * @param <P> the paradigm tag.
 */
public abstract class BulkheadBuilderBase<P extends ParadigmTag> implements BulkheadBuilder<P> {

    static final int PROTECTIVE_MAX_CONCURRENT_CALLS = 10;
    static final Duration PROTECTIVE_MAX_WAIT_DURATION = Duration.ZERO;
    static final String PROTECTIVE_LABEL = "protective";

    static final int BALANCED_MAX_CONCURRENT_CALLS = 50;
    static final Duration BALANCED_MAX_WAIT_DURATION = Duration.ofMillis(500);
    static final String BALANCED_LABEL = "balanced";

    static final int PERMISSIVE_MAX_CONCURRENT_CALLS = 200;
    static final Duration PERMISSIVE_MAX_WAIT_DURATION = Duration.ofSeconds(5);
    static final String PERMISSIVE_LABEL = "permissive";

    private final BulkheadPatch patch;
    private boolean customized;

    /**
     * @param name the bulkhead's name; non-null and non-blank. The base class touches the patch's
     *             {@code NAME} field with this value so that the patch carries the name through
     *             to {@code applyTo}.
     */
    protected BulkheadBuilderBase(String name) {
        // Class-1 validation (ADR-027): fail at the call site, not at applyTo time. The
        // snapshot's compact constructor enforces the same invariants, but the exception's
        // stack trace should point at the user's bulkhead("name", ...) call site, not at the
        // later patch application.
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.patch = new BulkheadPatch();
        this.patch.touchName(name);
        // Events are intentionally NOT touched here. Touching events with disabled() in the
        // constructor was a defensive shortcut to keep the snapshot's non-null events
        // invariant trivially satisfied — but it broke the update path: a runtime.update
        // built through this builder would always overwrite the live snapshot's events with
        // disabled(), even when the user only meant to patch maxConcurrentCalls. The correct
        // semantics (analogous to TAGS and DERIVED_FROM_PRESET) is "untouched fields inherit
        // from the base snapshot". The non-null invariant is satisfied by the system-default
        // snapshot in ImperativeProvider, which sets events to BulkheadEventConfig.disabled()
        // — so initial materialization still produces a valid snapshot, and updates correctly
        // inherit the live value.
    }

    @Override
    public BulkheadBuilder<P> maxConcurrentCalls(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "maxConcurrentCalls must be positive, got: " + value);
        }
        patch.touchMaxConcurrentCalls(value);
        customized = true;
        return this;
    }

    @Override
    public BulkheadBuilder<P> maxWaitDuration(Duration value) {
        Objects.requireNonNull(value, "maxWaitDuration");
        if (value.isNegative()) {
            throw new IllegalArgumentException(
                    "maxWaitDuration must not be negative, got: " + value);
        }
        patch.touchMaxWaitDuration(value);
        customized = true;
        return this;
    }

    @Override
    public BulkheadBuilder<P> tags(String... tags) {
        Objects.requireNonNull(tags, "tags");
        for (String tag : tags) {
            Objects.requireNonNull(tag, "tag element");
        }
        // Duplicates are silently deduped: a builder should be construction-friendly, and the
        // resulting tag set is unordered, so duplicate values carry no semantic information.
        // Throwing on duplicates would force users to defensively sanitize their inputs before
        // calling the DSL, which is not what a fluent builder should ask of them.
        patch.touchTags(new HashSet<>(Arrays.asList(tags)));
        customized = true;
        return this;
    }

    @Override
    public BulkheadBuilder<P> tags(Set<String> tags) {
        Objects.requireNonNull(tags, "tags");
        for (String tag : tags) {
            Objects.requireNonNull(tag, "tag element");
        }
        patch.touchTags(Set.copyOf(tags));
        customized = true;
        return this;
    }

    @Override
    public BulkheadBuilder<P> events(BulkheadEventConfig value) {
        Objects.requireNonNull(value, "events");
        patch.touchEvents(value);
        customized = true;
        return this;
    }

    @Override
    public BulkheadBuilder<P> protective() {
        guardPresetOrdering();
        patch.touchMaxConcurrentCalls(PROTECTIVE_MAX_CONCURRENT_CALLS);
        patch.touchMaxWaitDuration(PROTECTIVE_MAX_WAIT_DURATION);
        patch.touchDerivedFromPreset(PROTECTIVE_LABEL);
        return this;
    }

    @Override
    public BulkheadBuilder<P> balanced() {
        guardPresetOrdering();
        patch.touchMaxConcurrentCalls(BALANCED_MAX_CONCURRENT_CALLS);
        patch.touchMaxWaitDuration(BALANCED_MAX_WAIT_DURATION);
        patch.touchDerivedFromPreset(BALANCED_LABEL);
        return this;
    }

    @Override
    public BulkheadBuilder<P> permissive() {
        guardPresetOrdering();
        patch.touchMaxConcurrentCalls(PERMISSIVE_MAX_CONCURRENT_CALLS);
        patch.touchMaxWaitDuration(PERMISSIVE_MAX_WAIT_DURATION);
        patch.touchDerivedFromPreset(PERMISSIVE_LABEL);
        return this;
    }

    @Override
    public BulkheadBuilder<P> semaphore() {
        // Strategy setters do not engage the preset-then-customize guard: presets configure
        // capacity (maxConcurrentCalls / maxWaitDuration / preset label) while strategy setters
        // configure permit-management algorithm. The two are orthogonal — picking a strategy
        // does not imply customization of the preset's other fields. The customized flag stays
        // off so a subsequent .balanced() / .protective() preset call still works.
        patch.touchStrategy(new SemaphoreStrategyConfig());
        return this;
    }

    @Override
    public BulkheadBuilder<P> codel(Consumer<CoDelConfigBuilder> configurer) {
        Objects.requireNonNull(configurer, "configurer");
        CoDelConfigBuilder sub = new CoDelConfigBuilder();
        configurer.accept(sub);
        patch.touchStrategy(sub.build());
        return this;
    }

    @Override
    public BulkheadBuilder<P> adaptive(Consumer<AdaptiveConfigBuilder> configurer) {
        Objects.requireNonNull(configurer, "configurer");
        AdaptiveConfigBuilder sub = new AdaptiveConfigBuilder();
        configurer.accept(sub);
        patch.touchStrategy(sub.build());
        return this;
    }

    @Override
    public BulkheadBuilder<P> adaptiveNonBlocking(
            Consumer<AdaptiveNonBlockingConfigBuilder> configurer) {
        Objects.requireNonNull(configurer, "configurer");
        AdaptiveNonBlockingConfigBuilder sub = new AdaptiveNonBlockingConfigBuilder();
        configurer.accept(sub);
        patch.touchStrategy(sub.build());
        return this;
    }

    /**
     * @return the underlying patch, ready for {@code applyTo} or for inspection by the runtime
     *         container that materializes this component. Exposed for the DSL infrastructure;
     *         user code does not call this method.
     */
    public final BulkheadPatch toPatch() {
        return patch;
    }

    private void guardPresetOrdering() {
        if (customized) {
            throw new IllegalStateException(
                    "Cannot apply a preset after individual setters have been called. "
                            + "Presets are baselines: call them first, then customize. "
                            + "Example: bulkhead(\"x\", b -> b.protective().maxConcurrentCalls(15))");
        }
    }
}
