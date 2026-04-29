package eu.inqudium.config.dsl;

import eu.inqudium.config.runtime.ImperativeTag;

/**
 * Imperative-paradigm extension of {@link BulkheadBuilder}.
 *
 * <p>The imperative variant currently adds no methods of its own — the inherited setters cover
 * every field of {@link eu.inqudium.config.snapshot.BulkheadSnapshot BulkheadSnapshot} including
 * the strategy DSL ({@code .semaphore() / .codel(...) / .adaptive(...) /
 * .adaptiveNonBlocking(...)}). The sub-interface exists so that imperative-only extensions can
 * be added later without forcing them onto the reactive or coroutine variants.
 */
public interface ImperativeBulkheadBuilder extends BulkheadBuilder<ImperativeTag> {
}
