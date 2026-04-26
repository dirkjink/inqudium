package eu.inqudium.config.runtime;

import java.util.Objects;

/**
 * The full lookup key for a component in the runtime: a {@code (name, paradigm)} tuple.
 *
 * <p>Component names are scoped per paradigm — the same name may exist as both an imperative and
 * a reactive bulkhead, which are two distinct runtime objects with two separate live snapshots.
 * Anywhere the framework needs to identify a component unambiguously across paradigms (build
 * reports, diagnose output, configuration view lookups), it uses {@code ComponentKey}.
 *
 * @param name     the component's stable name; non-null and non-blank.
 * @param paradigm the paradigm tag the component belongs to; non-null.
 */
public record ComponentKey(String name, ParadigmTag paradigm) {

    public ComponentKey {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(paradigm, "paradigm");
    }
}
