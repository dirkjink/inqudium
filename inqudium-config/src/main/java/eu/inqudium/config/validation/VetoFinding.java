package eu.inqudium.config.validation;

import eu.inqudium.config.lifecycle.ComponentField;

import java.util.Objects;
import java.util.Set;

/**
 * A single veto outcome, recorded when a hot-state patch is declined either by a registered
 * listener or by the component-internal mutability check (ADR-028).
 *
 * <p>The reason is required and must be non-blank — silent vetoes are debugging nightmares. The
 * {@link Source} discriminator separates listener vetoes from component-internal ones, which lets
 * operational tooling distinguish "policy rejected the change" from "the component cannot apply
 * the change in its current state".
 *
 * <p>The veto chain itself is implemented in phase&nbsp;2 of the configuration refactor; this
 * record is part of the foundational types so that downstream code in phase&nbsp;1 can already
 * carry an empty {@code List&lt;VetoFinding&gt;} on its {@link BuildReport}.
 *
 * @param componentName the component the veto concerns; non-null.
 * @param touchedFields the patch's touched fields, captured for diagnostics; never null,
 *                      defensively copied.
 * @param reason        the veto explanation; non-null and non-blank.
 * @param source        whether a listener or the component-internal check vetoed.
 */
public record VetoFinding(
        String componentName,
        Set<? extends ComponentField> touchedFields,
        String reason,
        Source source) {

    public VetoFinding {
        Objects.requireNonNull(componentName, "componentName");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        Objects.requireNonNull(source, "source");
        touchedFields = touchedFields == null ? Set.of() : Set.copyOf(touchedFields);
    }

    /** Where the veto came from. */
    public enum Source {

        /** A registered {@code ChangeRequestListener} returned a veto. */
        LISTENER,

        /** The component's own mutability check rejected the patch. */
        COMPONENT_INTERNAL
    }
}
