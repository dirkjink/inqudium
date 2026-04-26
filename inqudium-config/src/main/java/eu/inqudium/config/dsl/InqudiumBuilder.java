package eu.inqudium.config.dsl;

import eu.inqudium.config.runtime.InqRuntime;

import java.util.function.Consumer;

/**
 * Top-level builder for {@code Inqudium.configure()...build()}.
 *
 * <p>Receives a {@code .general(...)} block, zero or more paradigm sections
 * ({@code .imperative(...)} for now; reactive, RxJava 3, and coroutines join in later phases),
 * and an optional {@code .strict()} toggle. {@link #build()} runs the complete validation
 * pipeline (classes 1, 2, 3 per ADR-027), invokes the matching
 * {@link eu.inqudium.config.spi.ParadigmProvider ParadigmProvider}s for the declared sections,
 * and returns the live {@link InqRuntime}.
 *
 * <p>Phase&nbsp;1 ships only the imperative section. Reactive, RxJava 3, and coroutine entry
 * points will be added when their providers come online; until then, a configuration that
 * requires one of them has no DSL surface to express it.
 */
public interface InqudiumBuilder {

    /**
     * Configure the runtime-level snapshot (clock, observability defaults, time source). May be
     * omitted; the {@link GeneralSnapshotBuilder} supplies sensible defaults for every field.
     *
     * @param configurer fills the supplied builder.
     * @return this builder, for chaining.
     */
    InqudiumBuilder general(Consumer<GeneralSnapshotBuilder> configurer);

    /**
     * Configure the imperative paradigm section.
     *
     * @param configurer fills the supplied section.
     * @return this builder, for chaining.
     */
    InqudiumBuilder imperative(Consumer<ImperativeSection> configurer);

    /**
     * Elevate {@link eu.inqudium.config.validation.Severity#WARNING WARNING} findings to
     * {@link eu.inqudium.config.validation.Severity#ERROR ERROR} during {@link #build()}.
     * Stub in phase&nbsp;1; fully wired up in phase&nbsp;1.8 alongside the consistency-rule
     * pipeline.
     *
     * @return this builder, for chaining.
     */
    InqudiumBuilder strict();

    /**
     * Run validation, materialize paradigm containers, and return the live runtime.
     *
     * @return the constructed {@link InqRuntime}.
     */
    InqRuntime build();
}
