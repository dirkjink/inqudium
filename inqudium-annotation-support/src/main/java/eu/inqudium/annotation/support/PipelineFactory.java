package eu.inqudium.annotation.support;

import eu.inqudium.core.element.InqElementRegistry;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.PipelineOrdering;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.Objects;

/**
 * Builds an {@link InqPipeline} from annotation scan results and a registry.
 *
 * <p>This is the central integration point that connects the three concerns:</p>
 * <ol>
 *   <li>{@link InqAnnotationScanner} — reads annotations from methods/classes</li>
 *   <li>{@link InqElementRegistry} — resolves element names to instances</li>
 *   <li>{@link InqPipeline} — composes elements into an ordered pipeline</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // One-shot: scan + build
 * InqPipeline pipeline = PipelineFactory.create(method, registry);
 *
 * // Two-step: scan separately, then build
 * ScanResult scan = InqAnnotationScanner.scan(method);
 * InqPipeline pipeline = PipelineFactory.create(scan, registry);
 *
 * // Full example in an Aspect:
 * @Around("@annotation(InqCircuitBreaker) || @annotation(InqRetry) || ...")
 * public Object around(ProceedingJoinPoint pjp) throws Throwable {
 *     Method method = ((MethodSignature) pjp.getSignature()).getMethod();
 *     InqPipeline pipeline = PipelineFactory.create(method, registry);
 *     return HybridAspectPipelineTerminal.of(pipeline).executeAround(pjp);
 * }
 * }</pre>
 *
 * <h3>Ordering resolution</h3>
 * <p>The ordering is determined by {@link InqAnnotationScanner.ScanResult#ordering()}:</p>
 * <table>
 *   <tr><th>Value</th><th>Behavior</th></tr>
 *   <tr><td>{@code "INQUDIUM"}</td>
 *       <td>{@link PipelineOrdering#standard()} — ADR-017 canonical order</td></tr>
 *   <tr><td>{@code "RESILIENCE4J"}</td>
 *       <td>{@link PipelineOrdering#resilience4j()} — R4J-compatible order</td></tr>
 *   <tr><td>{@code "CUSTOM"}</td>
 *       <td>Annotation declaration order — elements appear in the pipeline
 *           in the order they were declared on the method/class</td></tr>
 * </table>
 *
 * <h3>Thread safety</h3>
 * <p>All methods are stateless and safe for concurrent use. For hot-path
 * usage, cache the resulting {@link InqPipeline} per {@link Method}.</p>
 *
 * @since 0.8.0
 */
public final class PipelineFactory {

    private PipelineFactory() {
    }

    /**
     * Scans the method for Inqudium annotations and builds a pipeline by
     * resolving element names from the registry.
     *
     * <p>Convenience method — equivalent to
     * {@code create(InqAnnotationScanner.scan(method), registry)}.</p>
     *
     * @param method   the method to scan
     * @param registry the element registry for name → instance lookup
     * @return the built pipeline, or an empty pipeline if no annotations found
     * @throws InqElementRegistry.InqElementNotFoundException if a scanned
     *         element name is not found in the registry
     */
    public static InqPipeline create(Method method, InqElementRegistry registry) {
        Objects.requireNonNull(method, "Method must not be null");
        Objects.requireNonNull(registry, "Registry must not be null");

        InqAnnotationScanner.ScanResult scan = InqAnnotationScanner.scan(method);
        return create(scan, registry);
    }

    /**
     * Builds a pipeline from a pre-scanned result by resolving element names
     * from the registry.
     *
     * <p>Each {@link ScannedElement} in the scan result is looked up by
     * {@link ScannedElement#name()} in the registry. The ordering is
     * determined by {@link InqAnnotationScanner.ScanResult#ordering()}.</p>
     *
     * @param scan     the annotation scan result
     * @param registry the element registry for name → instance lookup
     * @return the built pipeline, or an empty pipeline if the scan is empty
     * @throws InqElementRegistry.InqElementNotFoundException if a scanned
     *         element name is not found in the registry
     */
    public static InqPipeline create(InqAnnotationScanner.ScanResult scan,
                                     InqElementRegistry registry) {
        Objects.requireNonNull(scan, "ScanResult must not be null");
        Objects.requireNonNull(registry, "Registry must not be null");

        if (scan.isEmpty()) {
            return InqPipeline.builder().build();
        }

        InqPipeline.Builder builder = InqPipeline.builder();

        // Resolve each scanned element from the registry
        for (ScannedElement scanned : scan.elements()) {
            builder.shield(registry.get(scanned.name()));
        }

        // Apply ordering
        builder.order(resolveOrdering(scan));

        return builder.build();
    }

    /**
     * Returns {@code true} if the method (or its declaring class) has any
     * Inqudium element annotations that would produce a non-empty pipeline.
     *
     * <p>Useful for fast pre-filtering in proxy handlers to decide between
     * pipeline execution and passthrough.</p>
     *
     * @param method the method to check
     * @return {@code true} if the method is annotated with Inqudium elements
     */
    public static boolean isProtected(Method method) {
        return InqAnnotationScanner.hasInqAnnotations(method);
    }

    // ======================== Internal ========================

    /**
     * Maps the ordering string from {@code @InqShield} to a
     * {@link PipelineOrdering} instance.
     *
     * <p>For {@code CUSTOM} ordering, elements are sorted by their
     * {@link ScannedElement#declarationOrder()} — i.e. the order in which
     * annotations appeared on the method/class.</p>
     */
    private static PipelineOrdering resolveOrdering(InqAnnotationScanner.ScanResult scan) {
        return switch (scan.ordering()) {
            case "RESILIENCE4J" -> PipelineOrdering.resilience4j();
            case "CUSTOM" -> customOrdering(scan);
            default -> PipelineOrdering.standard();
        };
    }

    /**
     * Creates a custom ordering that preserves annotation declaration order.
     *
     * <p>Maps each element type to the declaration order of the corresponding
     * scanned element. Element types not found in the scan fall back to
     * their default pipeline order.</p>
     */
    private static PipelineOrdering customOrdering(InqAnnotationScanner.ScanResult scan) {
        // Build a type → declaration-order map
        var orderByType = new java.util.EnumMap<InqElementType, Integer>(InqElementType.class);
        for (ScannedElement element : scan.elements()) {
            orderByType.put(element.type(), element.declarationOrder());
        }
        return type -> orderByType.getOrDefault(type, type.defaultPipelineOrder());
    }
}
