package eu.inqudium.spring.benchmark;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Minimal Spring-AOP baseline aspect.
 *
 * <p>The {@code @Around} advice does exactly three things: increment a
 * {@code volatile long} counter, feed the counter's value into a
 * {@link Blackhole} (so the JIT cannot eliminate the read), and call
 * {@code pjp.proceed()}. Nothing else.</p>
 *
 * <p>This is the measurement floor for "what does a Spring-AOP aspect with
 * trivial work per call cost?" — every Inqudium pipeline measured against
 * this already pays the same proxy + advice-dispatch price. The delta between
 * this baseline and an Inqudium variant is the pipeline overhead itself.</p>
 *
 * <h3>Why {@code volatile} and not {@link java.util.concurrent.atomic.AtomicLong}</h3>
 * <p>The user-requested shape is a plain {@code volatile long} counter.
 * A {@code ++} on a volatile is not atomic under contention, but JMH benchmarks
 * here run single-threaded by default and the value is still monotonic in that
 * regime. The volatile semantics prevent the compiler from reordering or
 * eliding the write.</p>
 */
@Aspect
public class BaselineSpringAspect {

    /**
     * Monotonically incremented per advice invocation.
     */
    @SuppressWarnings("FieldMayBeFinal")
    private volatile long counter = 0L;

    /**
     * Blackhole captured at setup time — aspects cannot take JMH-injected
     * blackholes through their advice signature, so we hold the reference here.
     */
    private final Blackhole blackhole;

    public BaselineSpringAspect(Blackhole blackhole) {
        this.blackhole = blackhole;
    }

    @Around("@annotation(eu.inqudium.spring.benchmark.Benchmarked)")
    public Object count(ProceedingJoinPoint pjp) throws Throwable {
        // Read-modify-write on a volatile long — not atomic, but single-threaded
        // benchmarks don't race, and the write is a guaranteed side effect.
        long c = ++counter;
        blackhole.consume(c);
        return pjp.proceed();
    }

    /**
     * Exposed for post-run inspection — not used by the benchmark hot path.
     */
    public long getCounter() {
        return counter;
    }
}
