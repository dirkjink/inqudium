package eu.inqudium.config.dsl;

import java.util.function.Consumer;

/**
 * The DSL section for imperative components — entry through which users declare and patch
 * imperative bulkheads (and, in later phases, circuit breakers, retries, time limiters).
 *
 * <p>The section is used in both initial-config and runtime-update modes (per ADR-025: same
 * builder types, only the starting snapshot differs). Calling {@code .bulkhead(name, ...)}
 * against an existing name patches the existing component; against a new name it adds one.
 * Calling {@code .removeBulkhead(name)} (runtime-update mode only) marks the named bulkhead for
 * structural removal — the runtime tears down the component and pulls it from the paradigm map
 * after the veto chain accepts.
 *
 * <p>Within one DSL traversal each name carries at most one operation; calling both
 * {@code bulkhead(name, ...)} and {@code removeBulkhead(name)} on the same traversal collapses
 * to whichever was called last (ADR-026 last-writer-wins semantics).
 */
public interface ImperativeSection {

    /**
     * Configure or patch a bulkhead in this paradigm section. If {@code name} was previously
     * marked for removal in this traversal, the removal is rescinded.
     *
     * @param name       the bulkhead's name; non-null and non-blank.
     * @param configurer fills the supplied builder.
     * @return this section, for chaining.
     */
    ImperativeSection bulkhead(String name, Consumer<ImperativeBulkheadBuilder> configurer);

    /**
     * Mark the named bulkhead for structural removal (ADR-026). On apply, the runtime routes
     * the removal through the veto chain like any other patch — listeners can veto and the
     * component-internal mutability check has the final say. After an accepted removal the
     * bulkhead's hot phase is shut down, the bulkhead is dropped from the paradigm map, and a
     * {@code RuntimeComponentRemovedEvent} is published on the runtime publisher.
     *
     * <p>If the name does not exist at apply time the operation reports
     * {@link eu.inqudium.config.validation.ApplyOutcome#UNCHANGED UNCHANGED} — the runtime is
     * already in the requested state. If the same name was previously configured via
     * {@link #bulkhead(String, Consumer)} in this traversal, that configuration is rescinded.
     *
     * @param name the bulkhead's name; non-null and non-blank.
     * @return this section, for chaining.
     */
    ImperativeSection removeBulkhead(String name);
}
