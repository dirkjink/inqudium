/**
 * Patch types — the unit of change between configurations.
 *
 * <p>A {@link eu.inqudium.config.patch.ComponentPatch ComponentPatch&lt;S&gt;} carries a {@code BitSet}
 * of "touched" fields plus the new values. Applying a patch to a snapshot is a per-field choice:
 * touched fields take the new value, untouched fields inherit from the base snapshot. Initialization
 * applies a patch to a system-default snapshot and demands completeness; updates apply a patch to the
 * current snapshot and tolerate partial coverage. The DSL, runtime updates, and format adapters all
 * produce patches — there is no second mutation path.
 */
package eu.inqudium.config.patch;
