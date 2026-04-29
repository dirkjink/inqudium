package eu.inqudium.aspect.pipeline.example;

import eu.inqudium.aspect.pipeline.AbstractPipelineAspect;
import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.util.List;

/**
 * Concrete aspect that weaves a three-layer pipeline around every method
 * annotated with {@link Pipelined}.
 *
 * <p>This aspect is intentionally minimal — all pipeline infrastructure
 * (caching, execution, introspection) lives in {@link AbstractPipelineAspect}.
 * The concrete aspect only defines:</p>
 * <ul>
 *   <li>The production layer stack (no-arg constructor)</li>
 *   <li>The pointcut ({@code @Around} advice)</li>
 * </ul>
 *
 * <h3>AspectJ-generated methods</h3>
 * <p>The AspectJ compiler ({@code ajc}) automatically generates
 * {@code aspectOf()} and {@code hasAspect()} methods during compile-time
 * weaving. These methods are <strong>not</strong> declared in the source —
 * {@code ajc} creates them in the bytecode. To use them:</p>
 * <pre>{@code
 * // Only available after CTW or LTW has been applied:
 * PipelinedAspect singleton = PipelinedAspect.aspectOf();
 * ResolvedPipeline pipeline = singleton.getResolvedPipeline(method);
 * }</pre>
 * <p>In unit tests without weaving, use {@code new PipelinedAspect()} instead —
 * the no-arg constructor wires the same production providers as the singleton.</p>
 *
 * <h3>Pipeline structure (outermost → innermost)</h3>
 * <ol>
 *   <li><strong>AUTHORIZATION</strong> (order 10) — checks access rights;
 *       short-circuits the chain if denied.</li>
 *   <li><strong>LOGGING</strong> (order 20) — records entry, exit, and
 *       exception events with chain/call IDs.</li>
 *   <li><strong>TIMING</strong> (order 30) — measures the execution
 *       duration of the core method (only for {@code @Pipelined} methods).</li>
 * </ol>
 *
 * <h3>Example call flow</h3>
 * <pre>
 *   caller → greet("World")
 *     → [AspectJ woven advice] → executeAround(pjp)
 *       → AUTHORIZATION → LOGGING → TIMING → pjp.proceed()
 *     ← "Hello, World!"
 * </pre>
 */
@Aspect
public class PipelinedAspect extends AbstractPipelineAspect {

    // ======================== Constructors ========================

    /**
     * Production constructor — used by AspectJ's singleton instantiation.
     * Wires the standard three-layer stack.
     */
    public PipelinedAspect() {
        super(List.of(
                new AuthorizationLayerProvider(),
                new LoggingLayerProvider(),
                new TimingLayerProvider()
        ));
    }

    /**
     * Test constructor — injectable providers for controlled testing.
     *
     * @param providers the layer providers (e.g. with trace lists)
     */
    public PipelinedAspect(List<AspectLayerProvider<Object>> providers) {
        super(providers);
    }

    @Override
    public Object execute(JoinPointExecutor<Object> coreExecutor) throws Throwable {
        return super.execute(coreExecutor);
    }
// ======================== Pointcut ========================

    /**
     * Around-advice that intercepts all {@code @Pipelined} methods.
     */
    @Around("@annotation(eu.inqudium.aspect.pipeline.example.Pipelined)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        return executeAround(pjp);
    }
}
