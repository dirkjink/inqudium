package eu.inqudium.annotation.support;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.annotation.InqCircuitBreaker;
import eu.inqudium.annotation.InqRateLimiter;
import eu.inqudium.annotation.InqRetry;
import eu.inqudium.annotation.InqShield;
import eu.inqudium.annotation.InqTimeLimiter;
import eu.inqudium.annotation.InqTrafficShaper;
import eu.inqudium.core.element.InqElementType;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans a {@link Method} (and its declaring class) for Inqudium element
 * annotations and produces a {@link ScanResult}.
 *
 * <h3>Merge semantics (METHOD + TYPE)</h3>
 * <ol>
 *   <li>Class-level annotations are collected as defaults.</li>
 *   <li>Method-level annotations <strong>override</strong> class-level
 *       annotations of the same {@link InqElementType}.</li>
 *   <li>If an element type appears only on the class, it is inherited.</li>
 *   <li>If an element type appears only on the method, it is added.</li>
 * </ol>
 *
 * <pre>{@code
 * @InqCircuitBreaker("defaultCb") // TYPE-level
 * @InqRetry("defaultRt") // TYPE-level
 * public class PaymentService {
 *
 *     @InqCircuitBreaker("specialCb") // overrides TYPE-level
 *     public Result placeOrder() { ... }
 *     // → [specialCb (CB), defaultRt (RT)]
 *
 *     public Status getStatus() { ... }
 *     // → [defaultCb (CB), defaultRt (RT)]  (inherits TYPE)
 * }
 * }</pre>
 *
 * <h3>{@code @InqShield} merge</h3>
 * <p>Method-level {@link InqShield} overrides class-level {@code @InqShield}.
 * If neither is present, {@code "INQUDIUM"} ordering is used as default.</p>
 *
 * <h3>Thread safety</h3>
 * <p>This class is stateless and safe for concurrent use. For hot-path
 * usage, cache the {@link ScanResult} per {@link Method} in a
 * {@link java.util.concurrent.ConcurrentHashMap}.</p>
 *
 * @since 0.8.0
 */
public final class InqAnnotationScanner {

    private InqAnnotationScanner() {
    }

    /**
     * Scans the given method and its declaring class for Inqudium annotations.
     *
     * @param method the method to scan
     * @return the scan result with merged elements and ordering
     */
    public static ScanResult scan(Method method) {
        // 1. Collect class-level annotations as defaults
        Map<InqElementType, ScannedElement> merged = new LinkedHashMap<>();
        int order = 0;
        for (Annotation annotation : method.getDeclaringClass().getAnnotations()) {
            ScannedElement element = toElement(annotation, order);
            if (element != null) {
                merged.put(element.type(), element);
                order++;
            }
        }

        // 2. Method-level annotations override class-level (same type → replace)
        for (Annotation annotation : method.getAnnotations()) {
            ScannedElement element = toElement(annotation, order);
            if (element != null) {
                merged.put(element.type(), element);
                order++;
            }
        }

        // 3. Resolve @InqShield ordering (METHOD overrides TYPE)
        String ordering = resolveOrdering(method);

        return new ScanResult(List.copyOf(merged.values()), ordering);
    }

    /**
     * Returns {@code true} if the method or its declaring class has any
     * Inqudium element annotation.
     *
     * <p>Useful for fast pre-filtering in proxy handlers or aspect
     * pointcuts to avoid full scanning on unannotated methods.</p>
     *
     * @param method the method to check
     * @return {@code true} if any Inq annotation is present
     */
    public static boolean hasInqAnnotations(Method method) {
        for (Annotation a : method.getAnnotations()) {
            if (isInqElementAnnotation(a)) return true;
        }
        for (Annotation a : method.getDeclaringClass().getAnnotations()) {
            if (isInqElementAnnotation(a)) return true;
        }
        return false;
    }

    // ======================== Internal: annotation → element mapping ========================

    /**
     * Maps a single annotation to a {@link ScannedElement}, or returns
     * {@code null} if the annotation is not an Inqudium element annotation.
     */
    private static ScannedElement toElement(Annotation annotation, int order) {
        if (annotation instanceof InqCircuitBreaker a) {
            return new ScannedElement(InqElementType.CIRCUIT_BREAKER,
                    a.value(), a.fallbackMethod(), order);
        }
        if (annotation instanceof InqRetry a) {
            return new ScannedElement(InqElementType.RETRY,
                    a.value(), a.fallbackMethod(), order);
        }
        if (annotation instanceof InqRateLimiter a) {
            return new ScannedElement(InqElementType.RATE_LIMITER,
                    a.value(), a.fallbackMethod(), order);
        }
        if (annotation instanceof InqBulkhead a) {
            return new ScannedElement(InqElementType.BULKHEAD,
                    a.value(), a.fallbackMethod(), order);
        }
        if (annotation instanceof InqTimeLimiter a) {
            return new ScannedElement(InqElementType.TIME_LIMITER,
                    a.value(), a.fallbackMethod(), order);
        }
        if (annotation instanceof InqTrafficShaper a) {
            return new ScannedElement(InqElementType.TRAFFIC_SHAPER,
                    a.value(), a.fallbackMethod(), order);
        }
        return null;
    }

    /**
     * Returns {@code true} if the annotation is an Inqudium element annotation.
     */
    private static boolean isInqElementAnnotation(Annotation annotation) {
        return annotation instanceof InqCircuitBreaker
                || annotation instanceof InqRetry
                || annotation instanceof InqRateLimiter
                || annotation instanceof InqBulkhead
                || annotation instanceof InqTimeLimiter
                || annotation instanceof InqTrafficShaper;
    }

    /**
     * Resolves the ordering from {@link InqShield}: method-level overrides
     * class-level. Returns {@code "INQUDIUM"} if no {@code @InqShield} is present.
     */
    private static String resolveOrdering(Method method) {
        // Method-level @InqShield takes precedence
        InqShield methodShield = method.getAnnotation(InqShield.class);
        if (methodShield != null) {
            return methodShield.order();
        }

        // Fall back to class-level @InqShield
        InqShield classShield = method.getDeclaringClass().getAnnotation(InqShield.class);
        if (classShield != null) {
            return classShield.order();
        }

        // Default: INQUDIUM canonical ordering
        return "INQUDIUM";
    }

    // ======================== Scan result ========================

    /**
     * The result of scanning a method for Inqudium annotations.
     *
     * @param elements the merged element list (TYPE + METHOD, METHOD wins)
     * @param ordering the ordering name from {@link InqShield}, or "INQUDIUM"
     */
    public record ScanResult(List<ScannedElement> elements, String ordering) {

        /**
         * Returns {@code true} if no Inqudium element annotations were found.
         */
        public boolean isEmpty() {
            return elements.isEmpty();
        }

        /**
         * Returns the number of elements found.
         */
        public int size() {
            return elements.size();
        }

        /**
         * Returns {@code true} if the ordering is the default ("INQUDIUM").
         */
        public boolean isDefaultOrdering() {
            return "INQUDIUM".equals(ordering);
        }

        /**
         * Returns {@code true} if CUSTOM ordering is requested — elements
         * should be applied in annotation declaration order.
         */
        public boolean isCustomOrdering() {
            return "CUSTOM".equals(ordering);
        }
    }
}
