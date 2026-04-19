package eu.inqudium.spring.benchmark;

import eu.inqudium.aspect.pipeline.AbstractPipelineAspect;
import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.util.List;

/**
 * Spring-AOP aspect that bridges into Inqudium's resolved-pipeline machinery.
 *
 * <p>Inherits the pipeline cache, provider resolution, and execution hot-path
 * from {@link AbstractPipelineAspect}. The only per-subclass responsibility
 * is wiring the providers at construction time and declaring the
 * {@code @Around} pointcut that matches the benchmark target method.</p>
 *
 * <p>The pointcut matches on {@link Benchmarked} — exactly the same trigger
 * as {@link BaselineSpringAspect}, so the comparison is apples-to-apples at
 * the proxy and advice-dispatch level.</p>
 */
@Aspect
public class InqudiumPipelineAspect extends AbstractPipelineAspect {

    public InqudiumPipelineAspect(List<AspectLayerProvider<Object>> providers) {
        super(providers);
    }

    @Around("@annotation(eu.inqudium.spring.benchmark.Benchmarked)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        return executeAround(pjp);
    }
}
