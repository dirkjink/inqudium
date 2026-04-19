package eu.inqudium.spring;

import eu.inqudium.annotation.support.InqAnnotationScanner;
import eu.inqudium.annotation.support.PipelineFactory;
import eu.inqudium.core.element.InqElementRegistry;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.SyncPipelineTerminal;
import eu.inqudium.imperative.core.pipeline.AsyncPipelineTerminal;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Spring AOP aspect that intercepts methods annotated with Inqudium
 * element annotations and routes them through a resilience pipeline.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>The pointcut matches any method or class annotated with
 *       {@code @InqCircuitBreaker}, {@code @InqRetry}, {@code @InqBulkhead},
 *       {@code @InqRateLimiter}, {@code @InqTimeLimiter}, or
 *       {@code @InqTrafficShaper}.</li>
 *   <li>On first invocation of a method, the aspect scans the annotations
 *       (METHOD + TYPE merge), builds an {@link InqPipeline} via
 *       {@link PipelineFactory}, and caches the result.</li>
 *   <li>Subsequent invocations reuse the cached pipeline — only the
 *       terminal lambda ({@code pjp::proceed}) is created per call.</li>
 *   <li>Methods returning {@link CompletionStage} are dispatched through
 *       the async chain (correct permit lifecycle). All others go through
 *       the sync chain.</li>
 * </ol>
 *
 * <h3>Per-Method caching</h3>
 * <pre>
 *   First call:
 *     1. MethodSignature.getMethod()               ← per call
 *     2. InqAnnotationScanner.scan(method)          ← once
 *     3. PipelineFactory.create(scan, registry)     ← once
 *     4. Build chain factory                         ← once
 *     5. factory.apply(pjp::proceed).proceed()      ← per call
 *
 *   Subsequent calls:
 *     1. MethodSignature.getMethod()               ← per call
 *     2. cache.get(method) → CachedPipeline         ← O(1)
 *     3. factory.apply(pjp::proceed).proceed()      ← per call
 * </pre>
 *
 * <h3>Not an AspectJ CTW aspect</h3>
 * <p>This aspect uses <strong>Spring AOP</strong> (proxy-based), not AspectJ
 * compile-time weaving. It requires {@code spring-boot-starter-aop} on the
 * classpath. For AspectJ CTW, use {@code inqudium-aspect} module instead.</p>
 *
 * @since 0.8.0
 * @see PipelineFactory
 */
@Aspect
public class InqShieldAspect {

    private static final Logger log = LoggerFactory.getLogger(InqShieldAspect.class);

    private final InqElementRegistry registry;

    /**
     * Pre-composed chain factories, cached per Method.
     */
    private final ConcurrentHashMap<Method, CachedPipeline> cache =
            new ConcurrentHashMap<>();

    public InqShieldAspect(InqElementRegistry registry) {
        this.registry = registry;
    }

    // ======================== Pointcuts ========================

    // --- Method-level: @annotation matches direct method annotations ---

    @Pointcut("@annotation(eu.inqudium.annotation.InqCircuitBreaker)")
    private void circuitBreakerMethod() {}

    @Pointcut("@annotation(eu.inqudium.annotation.InqRetry)")
    private void retryMethod() {}

    @Pointcut("@annotation(eu.inqudium.annotation.InqBulkhead)")
    private void bulkheadMethod() {}

    @Pointcut("@annotation(eu.inqudium.annotation.InqRateLimiter)")
    private void rateLimiterMethod() {}

    @Pointcut("@annotation(eu.inqudium.annotation.InqTimeLimiter)")
    private void timeLimiterMethod() {}

    @Pointcut("@annotation(eu.inqudium.annotation.InqTrafficShaper)")
    private void trafficShaperMethod() {}

    // --- Type-level: @within matches class-level annotations ---

    @Pointcut("@within(eu.inqudium.annotation.InqCircuitBreaker)")
    private void circuitBreakerType() {}

    @Pointcut("@within(eu.inqudium.annotation.InqRetry)")
    private void retryType() {}

    @Pointcut("@within(eu.inqudium.annotation.InqBulkhead)")
    private void bulkheadType() {}

    @Pointcut("@within(eu.inqudium.annotation.InqRateLimiter)")
    private void rateLimiterType() {}

    @Pointcut("@within(eu.inqudium.annotation.InqTimeLimiter)")
    private void timeLimiterType() {}

    @Pointcut("@within(eu.inqudium.annotation.InqTrafficShaper)")
    private void trafficShaperType() {}

    // --- Combined: any Inq annotation on method or class ---

    @Pointcut("circuitBreakerMethod() || retryMethod() || bulkheadMethod() || " +
              "rateLimiterMethod() || timeLimiterMethod() || trafficShaperMethod() || " +
              "circuitBreakerType() || retryType() || bulkheadType() || " +
              "rateLimiterType() || timeLimiterType() || trafficShaperType()")
    private void inqProtected() {}

    // ======================== Advice ========================

    /**
     * Around advice — intercepts every method matched by the
     * {@code inqProtected()} pointcut and routes it through the
     * cached pipeline.
     */
    @Around("inqProtected()")
    @SuppressWarnings("unchecked")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        CachedPipeline cached = resolvePipeline(method);

        if (cached == CachedPipeline.PASSTHROUGH) {
            return pjp.proceed();
        }

        if (cached.async) {
            try {
                JoinPointExecutor<CompletionStage<Object>> terminal =
                        () -> (CompletionStage<Object>) pjp.proceed();
                return cached.asyncFactory.apply(terminal).proceed();
            } catch (Throwable e) {
                return CompletableFuture.failedFuture(e);
            }
        } else {
            JoinPointExecutor<Object> terminal = pjp::proceed;
            return cached.syncFactory.apply(terminal).proceed();
        }
    }

    // ======================== Internal: caching ========================

    private CachedPipeline resolvePipeline(Method method) {
        CachedPipeline cached = cache.get(method);
        if (cached != null) {
            return cached;
        }
        return cache.computeIfAbsent(method, this::buildCachedPipeline);
    }

    private CachedPipeline buildCachedPipeline(Method method) {
        InqAnnotationScanner.ScanResult scan = InqAnnotationScanner.scan(method);

        if (scan.isEmpty()) {
            // Method matched via @within but has no effective annotations after merge
            return CachedPipeline.PASSTHROUGH;
        }

        InqPipeline pipeline = PipelineFactory.create(scan, registry);

        if (pipeline.isEmpty()) {
            return CachedPipeline.PASSTHROUGH;
        }

        log.debug("Built pipeline for {}.{}(): {} element(s), ordering={}",
                method.getDeclaringClass().getSimpleName(),
                method.getName(),
                pipeline.depth(),
                scan.ordering());

        boolean async = CompletionStage.class.isAssignableFrom(method.getReturnType());

        if (async) {
            return CachedPipeline.async(buildAsyncChainFactory(pipeline));
        } else {
            return CachedPipeline.sync(buildSyncChainFactory(pipeline));
        }
    }

    @SuppressWarnings("unchecked")
    private Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>>
    buildSyncChainFactory(InqPipeline pipeline) {
        return pipeline.chain(
                Function.<JoinPointExecutor<Object>>identity(),
                (accFn, element) -> executor ->
                        ((InqDecorator<Void, Object>) element)
                                .decorateJoinPoint(accFn.apply(executor)));
    }

    @SuppressWarnings("unchecked")
    private Function<JoinPointExecutor<CompletionStage<Object>>,
            JoinPointExecutor<CompletionStage<Object>>>
    buildAsyncChainFactory(InqPipeline pipeline) {
        return pipeline.chain(
                Function.<JoinPointExecutor<CompletionStage<Object>>>identity(),
                (accFn, element) -> executor ->
                        ((InqAsyncDecorator<Void, Object>) element)
                                .decorateAsyncJoinPoint(accFn.apply(executor)));
    }

    // ======================== Internal: cached pipeline holder ========================

    private static final class CachedPipeline {

        static final CachedPipeline PASSTHROUGH = new CachedPipeline(false, null, null);

        final boolean async;
        final Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>> syncFactory;
        final Function<JoinPointExecutor<CompletionStage<Object>>,
                JoinPointExecutor<CompletionStage<Object>>> asyncFactory;

        private CachedPipeline(
                boolean async,
                Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>> syncFactory,
                Function<JoinPointExecutor<CompletionStage<Object>>,
                        JoinPointExecutor<CompletionStage<Object>>> asyncFactory) {
            this.async = async;
            this.syncFactory = syncFactory;
            this.asyncFactory = asyncFactory;
        }

        static CachedPipeline sync(
                Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>> factory) {
            return new CachedPipeline(false, factory, null);
        }

        static CachedPipeline async(
                Function<JoinPointExecutor<CompletionStage<Object>>,
                        JoinPointExecutor<CompletionStage<Object>>> factory) {
            return new CachedPipeline(true, null, factory);
        }
    }
}
