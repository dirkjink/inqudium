package eu.inqudium.config.dsl;

import eu.inqudium.config.runtime.ParadigmTag;

import java.time.Duration;
import java.util.Set;

/**
 * Paradigm-agnostic fluent surface for configuring a single bulkhead.
 *
 * <p>The component's name is supplied as a method argument to the enclosing
 * {@code .bulkhead("name", b -> ...)} DSL call rather than via a setter on this builder, so
 * "forgot to set the name" cannot occur. Setters here cover only the patchable configuration
 * fields; per-paradigm sub-interfaces add paradigm-specific extensions.
 *
 * <p>Three named presets establish the most common baselines. The
 * preset-then-customize discipline (ADR-027 strategy A) requires that presets are called
 * <em>before</em> any individual setter — calling a preset after a setter throws
 * {@link IllegalStateException}. After a preset is applied, individual setters override the
 * specific fields they touch; untouched preset fields stay at their preset values.
 *
 * <p>Setters validate their arguments at the call site (ADR-027 class 1). Snapshot-internal
 * invariants (ADR-027 class 2) are enforced when {@code applyTo} runs against the underlying
 * patch — the DSL never assembles an invalid snapshot.
 *
 * @param <P> the paradigm tag.
 */
public interface BulkheadBuilder<P extends ParadigmTag> {

    /**
     * Set the maximum number of concurrent calls.
     *
     * @param value strictly positive.
     * @return this builder, for chaining.
     * @throws IllegalArgumentException if {@code value} is zero or negative.
     */
    BulkheadBuilder<P> maxConcurrentCalls(int value);

    /**
     * Set the maximum time a caller waits for a permit.
     *
     * @param value non-null and non-negative. {@link Duration#ZERO} expresses fail-fast.
     * @return this builder, for chaining.
     * @throws NullPointerException     if {@code value} is null.
     * @throws IllegalArgumentException if {@code value} is negative.
     */
    BulkheadBuilder<P> maxWaitDuration(Duration value);

    /**
     * Replace the operational tag set.
     *
     * @param tags non-null tag values; the array itself is defensively copied into an immutable
     *             set.
     * @return this builder, for chaining.
     * @throws NullPointerException if {@code tags} or any element is null.
     */
    BulkheadBuilder<P> tags(String... tags);

    /**
     * Replace the operational tag set.
     *
     * @param tags non-null tag set; defensively copied to an immutable set.
     * @return this builder, for chaining.
     * @throws NullPointerException if {@code tags} or any element is null.
     */
    BulkheadBuilder<P> tags(Set<String> tags);

    /**
     * Apply the {@code protective} preset baseline (conservative limits, fail-fast, low
     * overhead — intended for critical downstream services).
     *
     * @return this builder, for chaining.
     * @throws IllegalStateException if any individual setter has already been called on this
     *                               builder (preset-then-customize discipline).
     */
    BulkheadBuilder<P> protective();

    /**
     * Apply the {@code balanced} preset baseline (production default — reasonable headroom and
     * moderate wait).
     *
     * @return this builder, for chaining.
     * @throws IllegalStateException if any individual setter has already been called on this
     *                               builder (preset-then-customize discipline).
     */
    BulkheadBuilder<P> balanced();

    /**
     * Apply the {@code permissive} preset baseline (generous limits, longer wait — intended for
     * elastic downstream services).
     *
     * @return this builder, for chaining.
     * @throws IllegalStateException if any individual setter has already been called on this
     *                               builder (preset-then-customize discipline).
     */
    BulkheadBuilder<P> permissive();
}
