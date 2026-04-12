package eu.inqudium.aspect.pipeline.integration.perlayer;

import eu.inqudium.aspect.pipeline.AbstractPipelineAspect;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;

import java.util.List;

/**
 * Aspect that fires when <strong>any</strong> of the three layer annotations
 * ({@link Authorized}, {@link Logged}, {@link Timed}) is present on a method.
 *
 * <p>The pointcut is the union of all three annotations. The individual layer
 * providers then filter themselves via {@code canHandle(Method)} — each checks
 * only for its own annotation. This produces a per-method pipeline that
 * contains exactly the layers whose annotations are present:</p>
 *
 * <pre>
 *   @Authorized @Logged @Timed  →  AUTH → LOG → TIME
 *   @Authorized @Timed          →  AUTH → TIME
 *   @Logged                     →  LOG
 *   (none)                      →  not intercepted
 * </pre>
 */
@Aspect
public class PerLayerAspect extends AbstractPipelineAspect {

    public PerLayerAspect() {
        super(List.of(
                new AuthLayer(),
                new LogLayer(),
                new TimeLayer()
        ));
    }

    /**
     * Matches methods annotated with any of the three layer annotations.
     */
    @Pointcut("@annotation(eu.inqudium.aspect.pipeline.integration.perlayer.Authorized)"
            + " || @annotation(eu.inqudium.aspect.pipeline.integration.perlayer.Logged)"
            + " || @annotation(eu.inqudium.aspect.pipeline.integration.perlayer.Timed)")
    void anyLayerAnnotation() {
        // pointcut declaration — no body
    }

    @Around("anyLayerAnnotation()")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        return executeAround(pjp);
    }
}
