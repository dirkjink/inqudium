package eu.inqudium.bulkhead.integration.aspectj;

import eu.inqudium.aspect.pipeline.HybridAspectPipelineTerminal;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Compile-time-woven aspect that protects every {@link eu.inqudium.annotation.InqBulkhead
 * @InqBulkhead}-annotated method with the bulkhead the annotation names.
 *
 * <p>Headline shape of the AspectJ-native pattern: the aspect owns the {@link InqRuntime},
 * builds an {@link InqPipeline} containing a bulkhead, and lifts the pipeline through a
 * {@link HybridAspectPipelineTerminal}. The terminal reads each woven method's return type
 * and dispatches sync methods through the {@code InqDecorator} chain and methods returning
 * {@link java.util.concurrent.CompletionStage} through the {@code InqAsyncDecorator} chain;
 * a single bulkhead instance therefore protects both shapes through one terminal.
 *
 * <h3>Annotation-driven resolution</h3>
 * <p>The pointcut binds the {@code @InqBulkhead} annotation as an advice parameter rather
 * than only matching its presence. The around-advice reads {@link
 * eu.inqudium.annotation.InqBulkhead#value() inqBulkhead.value()} on every invocation and
 * resolves the bulkhead by that name from the runtime's registry. A {@link ConcurrentMap}
 * caches one terminal per bulkhead name, so a given name is looked up and lifted into a
 * pipeline exactly once for the JVM's lifetime; subsequent invocations reuse the cached
 * terminal. This shape scales to multiple bulkheads with no additional code — a method
 * annotated {@code @InqBulkhead("orderBh")} and another annotated {@code @InqBulkhead(
 * "shippingBh")} would each end up routed through their own cached terminal — and makes
 * the dependence on the annotation's {@code value()} explicit at the dispatch surface.
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
 * so async {@code @InqBulkhead}-annotated methods would slip through unprotected if the
 * aspect extended the sync base class. {@link HybridAspectPipelineTerminal} is the supported
 * hybrid surface: one composed pipeline, sync/async dispatch by return type, both halves
 * sharing one permit pool. Its Javadoc shows the exact pattern used here.
 */
@Aspect
public class OrderBulkheadAspect {

    private final InqRuntime runtime;

    /**
     * Caches one {@link HybridAspectPipelineTerminal} per bulkhead name.
     *
     * <p>The runtime's element registry already caches the bulkhead instance behind
     * {@link InqRuntime#imperative() runtime.imperative()}{@code .bulkhead(name)} — the same
     * name returns the same instance. This cache, however, sits one layer above and holds
     * the {@code Pipeline+Terminal} object built around that bulkhead, which is decidedly
     * not free to reconstruct (see {@code HybridAspectPipelineTerminal}'s constructor):
     *
     * <ul>
     *   <li>walks the pipeline and reverses it to outermost-first order,</li>
     *   <li>validates every element via {@code instanceof}/cast against
     *       {@link eu.inqudium.core.pipeline.InqDecorator} <em>and</em>
     *       {@link eu.inqudium.imperative.core.pipeline.InqAsyncDecorator},</li>
     *   <li>allocates two layer-action arrays, including one bound
     *       {@code asyncDec::executeAsync} method reference per element,</li>
     *   <li>creates a {@code ResolvedPipelineState} with a fresh {@code chainId} — the
     *       identity anchor that diagnostic tooling uses to recognize the same pipeline
     *       across calls,</li>
     *   <li>allocates a per-{@link java.lang.reflect.Method} sync/async-flag cache that the
     *       terminal documents as the very thing that keeps its hot path cheap.</li>
     * </ul>
     *
     * <p>Without a cache at this layer, every advised invocation would build a fresh
     * pipeline and terminal, repeat the validation loop, allocate new method references,
     * mint a new {@code chainId} (breaking diagnostic continuity), and start the async-flag
     * cache empty so the per-method lookup never hits. Building the terminal eagerly in the
     * constructor is also not an option here: the example demonstrates that the
     * {@code @InqBulkhead} annotation's {@code value()} drives the resolution, which means
     * the set of names is, in principle, only known at advice time. {@link
     * java.util.concurrent.ConcurrentMap#computeIfAbsent computeIfAbsent} therefore
     * materializes one terminal per name on first use and reuses it forever after — the
     * shape that {@code HybridAspectPipelineTerminal}'s hot-path assumptions actually rely
     * on.
     */
    private final ConcurrentMap<String, HybridAspectPipelineTerminal> terminalsByName =
            new ConcurrentHashMap<>();

    /**
     * Production constructor — invoked by AspectJ when {@code aspectOf()} is first called.
     *
     * <p>Allocates the runtime that the rest of the aspect's lookups draw from. The
     * pipeline-and-terminal construction happens lazily on first invocation per bulkhead
     * name (see {@link #terminalFor}), so a name that never matches any annotation never
     * pays for its terminal, and a name that matches many invocations pays exactly once.
     */
    public OrderBulkheadAspect() {
        this.runtime = BulkheadConfig.newRuntime();
    }

    /**
     * Around-advice that intercepts every method annotated with
     * {@link eu.inqudium.annotation.InqBulkhead}.
     *
     * <p>The pointcut binds the annotation as the {@code inqBulkhead} advice parameter, so
     * AspectJ delivers the annotation instance — not just the fact of its presence — to this
     * method on every invocation. The advice reads {@code inqBulkhead.value()} and looks up
     * the corresponding terminal: a cache miss builds the terminal once and stores it
     * (atomic via {@link ConcurrentMap#computeIfAbsent}); a cache hit returns the existing
     * terminal directly. The terminal handles the sync-vs-async dispatch decision once per
     * {@link java.lang.reflect.Method} (cached internally) and routes the call through the
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
     * @param pjp         the proceeding join point provided by AspectJ
     * @param inqBulkhead the {@code @InqBulkhead} annotation instance attached to the woven
     *                    method; its {@code value()} names the bulkhead to dispatch through
     * @return the woven method's return value, unchanged
     * @throws Throwable any exception from the proxied method or the pipeline
     */
    @Around("execution(* *(..)) && @annotation(inqBulkhead)")
    public Object aroundInqBulkhead(ProceedingJoinPoint pjp,
                                    eu.inqudium.annotation.InqBulkhead inqBulkhead) throws Throwable {
        return terminalFor(inqBulkhead.value()).executeAround(pjp);
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
     * Returns the example's primary bulkhead — the one named
     * {@link BulkheadConfig#BULKHEAD_NAME}. A convenience for tests and {@code Main} that
     * spares callers a cast at every read site; the cast is safe because the runtime
     * registry stores the same instance under both views.
     */
    @SuppressWarnings("unchecked")
    public InqBulkhead<Object, Object> bulkhead() {
        return (InqBulkhead<Object, Object>) runtime.imperative()
                .bulkhead(BulkheadConfig.BULKHEAD_NAME);
    }

    /**
     * Lazily resolves and caches one {@link HybridAspectPipelineTerminal} per bulkhead name.
     *
     * <p>The terminal is built on first invocation for a given name and reused on every
     * subsequent call carrying the same {@code @InqBulkhead(name)}. If the name does not
     * resolve to a registered bulkhead, the underlying registry surfaces its own error —
     * appropriate semantics for an example, where the configuration file and the annotation
     * values are written by the same hand. A production aspect that wanted a graceful
     * degradation path would catch the lookup failure and either pass-through unprotected or
     * throw a domain-specific configuration exception.
     */
    private HybridAspectPipelineTerminal terminalFor(String bulkheadName) {
        return terminalsByName.computeIfAbsent(bulkheadName, name -> {
            @SuppressWarnings("unchecked")
            InqBulkhead<Object, Object> bh =
                    (InqBulkhead<Object, Object>) runtime.imperative().bulkhead(name);
            return HybridAspectPipelineTerminal.of(
                    InqPipeline.builder().shield(bh).build());
        });
    }
}
