package eu.inqudium.config.dsl;

import java.util.function.Consumer;

/**
 * Top-level builder for {@code runtime.update(u -> ...)} — the DSL surface for runtime
 * mutations.
 *
 * <p>Per ADR-025 the update path uses the same paradigm-section types as initial configuration
 * — only the starting snapshot for each component differs (live vs. system-default). The
 * difference at the top level is what {@code InqudiumUpdateBuilder} excludes: no
 * {@code .general(...)} (the general snapshot is fixed at runtime build time), no
 * {@code .strict()} (validation strictness is set once), no {@code .build()} (the runtime
 * already exists; the update applies in place).
 */
public interface InqudiumUpdateBuilder {

    /**
     * Apply updates to the imperative paradigm section.
     *
     * @param configurer fills the supplied section.
     * @return this builder, for chaining.
     */
    InqudiumUpdateBuilder imperative(Consumer<ImperativeSection> configurer);
}
