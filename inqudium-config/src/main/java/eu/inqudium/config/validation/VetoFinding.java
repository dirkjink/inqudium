package eu.inqudium.config.validation;

import eu.inqudium.config.lifecycle.ComponentField;
import eu.inqudium.config.runtime.ComponentKey;

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
 * <p>This record is the unit of veto reporting in {@link BuildReport#vetoFindings()}.
 *
 * @param componentKey  the {@code (name, paradigm)} key of the component the veto concerns;
 *                      non-null. Same-name components in different paradigms cannot collide.
 * @param touchedFields the patch's touched fields, captured for diagnostics; never null,
 *                      defensively copied.
 * @param reason        the veto explanation; non-null and non-blank.
 * @param source        whether a listener or the component-internal check vetoed.
 */
public record VetoFinding(
        ComponentKey componentKey,
        Set<? extends ComponentField> touchedFields,
        String reason,
        Source source) {

    public VetoFinding {
        Objects.requireNonNull(componentKey, "componentKey");
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
