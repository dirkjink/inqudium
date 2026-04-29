package eu.inqudium.annotation.support;

import eu.inqudium.core.element.InqElementType;

import java.util.Objects;

/**
 * Represents a single Inqudium element annotation found on a method or class.
 *
 * <p>Produced by {@link InqAnnotationScanner} — captures the parsed annotation
 * data in a framework-agnostic form that can be used to build an
 * {@code InqPipeline} via registry lookup.</p>
 *
 * @param type             the element kind (e.g. {@link InqElementType#CIRCUIT_BREAKER})
 * @param name             the instance name from the annotation's {@code value()}
 *                         attribute (e.g. "paymentCb")
 * @param fallbackMethod   the fallback method name, or empty string if not specified
 * @param declarationOrder the position in the annotation list (0-based), used for
 *                         {@code CUSTOM} ordering where declaration order determines
 *                         pipeline position
 * @since 0.8.0
 */
public record ScannedElement(
        InqElementType type,
        String name,
        String fallbackMethod,
        int declarationOrder) {

    public ScannedElement {
        Objects.requireNonNull(type, "Element type must not be null");
        Objects.requireNonNull(name, "Element name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Element name must not be blank");
        }
        Objects.requireNonNull(fallbackMethod, "Fallback method must not be null (use empty string)");
    }

    /**
     * Returns {@code true} if a fallback method is configured.
     */
    public boolean hasFallback() {
        return !fallbackMethod.isEmpty();
    }
}
