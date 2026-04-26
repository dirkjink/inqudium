package eu.inqudium.config.dsl;

import java.util.function.Consumer;

/**
 * The DSL section for imperative components — entry through which users declare and patch
 * imperative bulkheads (and, in later phases, circuit breakers, retries, time limiters).
 *
 * <p>The section is used in both initial-config and runtime-update modes (per ADR-025: same
 * builder types, only the starting snapshot differs). Calling {@code .bulkhead(name, ...)}
 * against an existing name patches the existing component; against a new name it adds one.
 *
 * <p>Structural removal ({@code .removeBulkhead(name)}) is deferred to phase&nbsp;2 of the
 * configuration refactor.
 */
public interface ImperativeSection {

    /**
     * Configure or patch a bulkhead in this paradigm section.
     *
     * @param name       the bulkhead's name; non-null and non-blank.
     * @param configurer fills the supplied builder.
     * @return this section, for chaining.
     */
    ImperativeSection bulkhead(String name, Consumer<ImperativeBulkheadBuilder> configurer);
}
