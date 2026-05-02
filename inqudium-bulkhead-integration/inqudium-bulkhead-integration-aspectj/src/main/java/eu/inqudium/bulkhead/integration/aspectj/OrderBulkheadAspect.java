package eu.inqudium.bulkhead.integration.aspectj;

import eu.inqudium.aspect.pipeline.HybridAspectPipelineTerminal;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * Compile-time-woven aspect that protects every {@link BulkheadProtected}-annotated method
 * with the example's bulkhead.
 *
 * <p>Headline shape of the AspectJ-native pattern: the aspect owns the {@link InqRuntime},
 * builds an {@link InqPipeline} containing the bulkhead, and lifts the pipeline through a
 * {@link HybridAspectPipelineTerminal}. The terminal reads each woven method's return type
 * and dispatches sync methods through the {@code InqDecorator} chain and methods returning
 * {@link java.util.concurrent.CompletionStage} through the {@code InqAsyncDecorator} chain;
 * a single bulkhead instance therefore protects both shapes through one terminal.
 *
 * <p>The aspect is an AspectJ singleton — by default {@code ajc} weaves an
 * {@code aspectOf()} accessor and constructs exactly one instance per classloader on first
 * use. The constructor allocates the runtime; the runtime lives for the lifetime of the
 * aspect (effectively the JVM's). For an example/demo this is acceptable, and matches what
 * a 2026 application would write when it owns its own AspectJ wiring without a DI container.
 * In production with Spring or another container, the runtime would normally be a managed
 * bean injected into the aspect — see the Spring Framework and Spring Boot example modules
 * for that pattern.
 *
 * <h3>Why a hybrid terminal, not {@code AbstractPipelineAspect}</h3>
 * <p>{@link eu.inqudium.aspect.pipeline.AbstractPipelineAspect} is sync-only: its layer
 * providers ({@link eu.inqudium.aspect.pipeline.ElementLayerProvider}) decline async methods,
 * so async {@code @BulkheadProtected} methods would slip through unprotected if the aspect
 * extended the sync base class. {@link HybridAspectPipelineTerminal} is the supported
 * hybrid surface: one composed pipeline, sync/async dispatch by return type, both halves
 * sharing one permit pool. Its Javadoc shows the exact pattern used here.
 */
@Aspect
public class OrderBulkheadAspect {

    private final InqRuntime runtime;
    private final HybridAspectPipelineTerminal terminal;

    /**
     * Production constructor — invoked by AspectJ when {@code aspectOf()} is first called.
     *
     * <p>Builds the runtime, looks up the bulkhead by name, composes a one-element pipeline
     * around it, and lifts the pipeline through the hybrid terminal. The four steps are the
     * AspectJ-native equivalent of what {@code Main} would otherwise have done up-front in
     * the function-based or proxy-based example: by happening inside the aspect, they become
     * invisible to every call site.
     */
    public OrderBulkheadAspect() {
        this.runtime = BulkheadConfig.newRuntime();
        InqBulkhead<Object, Object> bulkhead = orderBulkhead(runtime);
        this.terminal = HybridAspectPipelineTerminal.of(
                InqPipeline.builder().shield(bulkhead).build());
    }

    /**
     * Around-advice that intercepts every {@link BulkheadProtected}-annotated method.
     *
     * <p>The implementation forwards directly to {@link HybridAspectPipelineTerminal#executeAround
     * terminal.executeAround(pjp)}; the terminal handles the sync-vs-async dispatch decision
     * once per {@link java.lang.reflect.Method} (cached) and routes the call through the
     * appropriate chain. Synchronous methods see the bulkhead's
     * {@link eu.inqudium.core.element.bulkhead.InqBulkheadFullException} thrown directly;
     * async methods see it surfaced through the failed-stage channel that
     * {@link HybridAspectPipelineTerminal} documents as its uniform error-channel policy.
     *
     * <p>The pointcut is qualified with {@code execution(...)} — without that qualifier,
     * AspectJ matches both call and execution join points for the annotation, which would
     * cause every call site compiled in this module to advise the same call twice (once at
     * the caller, once at the callee). Restricting to {@code execution} attaches the advice
     * exactly once to the method body, regardless of where the caller lives.
     *
     * @param pjp the proceeding join point provided by AspectJ
     * @return the woven method's return value, unchanged
     * @throws Throwable any exception from the proxied method or the pipeline
     */
    @Around("execution(* *(..)) && @annotation(eu.inqudium.bulkhead.integration.aspectj.BulkheadProtected)")
    public Object aroundBulkheadProtected(ProceedingJoinPoint pjp) throws Throwable {
        return terminal.executeAround(pjp);
    }

    /**
     * Returns the runtime owned by this aspect singleton. The accessor exists for
     * test-driven introspection — for example, to read {@code availablePermits()} on the
     * underlying bulkhead — and for the rare case in {@code Main} where the runtime needs to
     * be referenced to demonstrate that one and the same bulkhead drives both halves.
     */
    public InqRuntime runtime() {
        return runtime;
    }

    /**
     * Returns the bulkhead that this aspect protects join points with. A convenience to
     * spare callers a cast at every read site; the cast is safe because the runtime
     * registry stores the same instance under both views.
     */
    @SuppressWarnings("unchecked")
    public InqBulkhead<Object, Object> bulkhead() {
        return (InqBulkhead<Object, Object>) runtime.imperative()
                .bulkhead(BulkheadConfig.BULKHEAD_NAME);
    }

    @SuppressWarnings("unchecked")
    private static InqBulkhead<Object, Object> orderBulkhead(InqRuntime runtime) {
        return (InqBulkhead<Object, Object>) runtime.imperative()
                .bulkhead(BulkheadConfig.BULKHEAD_NAME);
    }
}
