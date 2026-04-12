package eu.inqudium.aspect.pipeline.integration;

import eu.inqudium.aspect.pipeline.AbstractPipelineAspect;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.util.List;

/**
 * Resilience aspect — intercepts all {@code @Resilient} methods and routes
 * them through a three-layer pipeline: AUTHORIZATION → LOGGING → TIMING.
 *
 * <p>Demonstrates a real-world aspect configuration. The no-arg constructor
 * wires the production layer stack; {@code ajc} generates {@code aspectOf()}
 * and {@code hasAspect()} during compile-time weaving.</p>
 */
@Aspect
public class ResilienceAspect extends AbstractPipelineAspect {

    /**
     * Production constructor — wires the standard layer stack.
     * Called by AspectJ via the generated {@code aspectOf()}.
     */
    public ResilienceAspect() {
        super(List.of(
                new AuthorizationLayer(),
                new LoggingLayer(),
                new TimingLayer()
        ));
    }

    /**
     * Around-advice that intercepts all {@code @Resilient} methods.
     */
    @Around("@annotation(eu.inqudium.aspect.pipeline.integration.Resilient)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        return executeAround(pjp);
    }
}
