package eu.inqudium.spring.benchmark;

/**
 * Minimal service interface used as the proxy target by every benchmark variant.
 *
 * <p>An interface is required so Spring-AOP can use a JDK dynamic proxy
 * (instead of CGLIB subclassing), which keeps the proxy path identical across
 * the baseline and Inqudium benchmarks — the only variable then is what the
 * {@code @Around} advice does.</p>
 */
public interface BenchmarkService {

    /**
     * The method the aspects wrap. Intentionally trivial so the measurement
     * is dominated by aspect overhead rather than by method body cost.
     *
     * @param input any integer
     * @return {@code input + 1}
     */
    int execute(int input);
}
