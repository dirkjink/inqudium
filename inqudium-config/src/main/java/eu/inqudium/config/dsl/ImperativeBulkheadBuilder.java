package eu.inqudium.config.dsl;

import eu.inqudium.config.runtime.ImperativeTag;

/**
 * Imperative-paradigm extension of {@link BulkheadBuilder}.
 *
 * <p>In phase&nbsp;1.5 the imperative variant adds no methods of its own — the inherited setters
 * cover every field of {@link eu.inqudium.config.snapshot.BulkheadSnapshot BulkheadSnapshot}. The
 * sub-interface exists so that paradigm-specific extensions in later phases (strategy injection,
 * adaptive-limit sub-builders such as {@code .codel(...)} and {@code .aimd(...)}) can be added
 * without forcing them onto the reactive or coroutine variants.
 */
public interface ImperativeBulkheadBuilder extends BulkheadBuilder<ImperativeTag> {
}
