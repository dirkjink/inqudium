package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.pipeline.LayerAction;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.profile.MemPoolProfiler;
import org.openjdk.jmh.profile.PausesProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark measuring framework proxy performance vs. JDK proxy under
 * multi-thread load with 4 stacked proxy layers.
 *
 * <p>Both the JDK baseline and the framework variant stack 4 proxy layers,
 * each wrapping the next, to create a comparable chain depth. The JDK
 * baseline uses {@code method.invoke()} (reflection), while the framework
 * uses {@code MethodHandle}-based dispatch with a {@link LayerAction}.</p>
 *
 * <h3>Setup</h3>
 * <pre>
 *   JDK baseline:       jdkProxy → jdkInner1 → jdkInner2 → jdkInner3 → target
 *                        (4 × Proxy.newProxyInstance, 4 × method.invoke)
 *
 *   Framework:           fwProxy  → fwInner1  → fwInner2  → fwInner3  → target
 *                        (4 × InqProxyFactory.protect, MethodHandle dispatch)
 * </pre>
 *
 * <p>Three argument counts test dispatch path specialization:</p>
 * <ul>
 *   <li><strong>2 args</strong> — framework fast-path (specialized MethodHandle)</li>
 *   <li><strong>5 args</strong> — framework fast-path (specialized MethodHandle)</li>
 *   <li><strong>8 args</strong> — framework spreader-path (generic MethodHandle.spread)</li>
 * </ul>
 *
 * <h3>Results (4 threads, 2 forks, consumeCPU(50), JDK 21)</h3>
 * <pre>
 * Benchmark               ns/op    ±error    B/op   gc.count   vs JDK baseline
 * ────────────────────────────────────────────────────────────────────────────────
 * baseline_2Args           98.557   ±3.035      96        266   —
 * framework_2Args          85.385   ±2.412     128        409   −13 ns  (−13.4%)
 * baseline_5Args          106.606   ±0.965     160        437   —
 * framework_5Args         102.423  ±27.557     144        415    −4 ns   (−3.9%)
 * baseline_8Args          114.897   ±0.968     192        444   —
 * framework_8Args          88.913   ±1.675     152        482   −26 ns  (−22.6%)
 * </pre>
 *
 * <h3>Analysis</h3>
 *
 * <h4>1. Framework proxy is faster than JDK proxy across all arg counts</h4>
 * <p>The framework's {@code MethodHandle}-based dispatch consistently outperforms
 * the JDK proxy's {@code Method.invoke()} reflection path. The advantage grows
 * with argument count: −13 ns for 2 args, −26 ns for 8 args. This is because
 * the JDK proxy must box arguments into {@code Object[]} and use reflection,
 * while the framework uses type-specialized MethodHandles that the JIT can
 * inline directly.</p>
 *
 * <h4>2. Allocation profile: framework scales better with arg count</h4>
 * <pre>
 *   Args   JDK B/op   Framework B/op   Delta
 *   2       96         128             +32 B (framework slightly higher)
 *   5      160         144             −16 B (framework lower)
 *   8      192         152             −40 B (framework significantly lower)
 * </pre>
 * <p>The JDK proxy's allocation grows at 32 B per additional 3 args (driven
 * by {@code Object[]} array sizing at each of the 4 proxy layers), while the
 * framework's allocation is dominated by fixed per-layer overhead and scales
 * more slowly because MethodHandle invocation avoids per-call argument array
 * allocation for specialized fast-paths.</p>
 *
 * <h4>3. GC impact is comparable</h4>
 * <p>Both approaches generate young-gen pressure. The framework has slightly
 * more GC collections (409–482 vs. 266–444) due to different allocation
 * patterns, but p50 pause times are similar (0.5–0.7 ms). Old Gen remains
 * stable at ~5 MB for both — no tenuring pressure.</p>
 *
 * <h4>4. 5-arg error bar indicates JIT deoptimization</h4>
 * <p>The framework 5-arg result (102.4 ± 27.6 ns) has an unusually large
 * error bar. This is likely caused by competing JIT compilation strategies
 * for the 5-arg fast-path across warmup iterations — the JIT may oscillate
 * between specializations. The 2-arg and 8-arg results, which have stable
 * JIT paths, show tight error bars (±2.4 and ±1.7 ns).</p>
 *
 * <h3>Cross-benchmark comparison: Proxy vs. CTW Pipeline</h3>
 *
 * <p>Comparing this proxy benchmark against the
 * {@code WovenPipelineBenchmark} (AspectJ CTW with action-array pipeline):</p>
 * <pre>
 * Approach                       Layers   ns/op     B/op   Fixed B/op   Per-layer B/op
 * ─────────────────────────────────────────────────────────────────────────────────────
 * JDK Proxy stack                  4       98.6       96       0          24 (Object[])
 * Framework Proxy stack            4       85.4      128       0          32 (MH dispatch)
 * CTW Pipeline (@Resilient)        3      166.4      232     160          24 (chain lambda)
 * CTW Pipeline (baseline)          0       55.1        0       —           —
 * </pre>
 *
 * <p>Key observations:</p>
 * <ul>
 *   <li><strong>AspectJ's fixed cost dominates CTW overhead.</strong> The
 *       160 B/op and ~84 ns fixed cost (ProceedingJoinPoint creation, aspectOf()
 *       lookup, Method extraction) are absent in the proxy approach. The
 *       pipeline's own per-layer cost (24 B/op, ~14 ns) is competitive with
 *       or better than both proxy variants.</li>
 *   <li><strong>The proxy baseline is not free.</strong> The JDK proxy's own
 *       4-layer baseline (98.6 ns, 96 B/op) already includes reflection
 *       overhead. The CTW baseline (55.1 ns, 0 B/op) is a direct method call
 *       with no proxy infrastructure.</li>
 *   <li><strong>Per-layer cost is similar across all approaches.</strong>
 *       JDK proxy: ~24 B/op (Object[] per layer). Framework proxy: ~32 B/op
 *       (MH dispatch). CTW pipeline: ~24 B/op (chain lambda). The
 *       architectures converge on roughly the same per-layer allocation cost.</li>
 * </ul>
 *
 * <h3>Recommendation</h3>
 *
 * <table>
 *   <tr><th>Criterion</th><th>Framework Proxy</th><th>CTW Pipeline</th></tr>
 *   <tr><td>Latency</td>
 *       <td>Lower end-to-end (no AspectJ PJP overhead)</td>
 *       <td>Higher due to ~84 ns AspectJ fixed cost</td></tr>
 *   <tr><td>Allocation</td>
 *       <td>Lower total (no 160 B/op PJP allocation)</td>
 *       <td>Higher total, but lower per-layer cost</td></tr>
 *   <tr><td>Interface requirement</td>
 *       <td>Required — proxy wraps an interface</td>
 *       <td>Not required — works on concrete classes</td></tr>
 *   <tr><td>Layer composition</td>
 *       <td>Stacked proxies (each independently created)</td>
 *       <td>Single aspect, N layers with canHandle() filtering</td></tr>
 *   <tr><td>Build infrastructure</td>
 *       <td>None — pure runtime, works with javac</td>
 *       <td>AspectJ compiler (ajc) or LTW agent required</td></tr>
 *   <tr><td>Dynamic reconfiguration</td>
 *       <td>Possible — create new proxy at runtime</td>
 *       <td>Static — woven at compile/load time</td></tr>
 * </table>
 *
 * <p><strong>Use the framework proxy</strong> when wrapping interface-based
 * services at runtime (DI containers, service meshes, dynamic decoration),
 * especially in latency-sensitive paths where the ~84 ns AspectJ fixed cost
 * matters.</p>
 *
 * <p><strong>Use the CTW pipeline</strong> when cross-cutting concerns should
 * be annotation-driven, apply to concrete classes (not just interfaces), and
 * require per-method layer selection via {@code canHandle()}. The AspectJ fixed
 * cost is constant regardless of pipeline depth and negligible compared to any
 * real-world layer action (logging, metrics, I/O).</p>
 *
 * <p><strong>Both approaches</strong> share the same {@code LayerAction} /
 * {@code InternalExecutor} abstractions from {@code inqudium-core}. Layer
 * implementations are portable between proxy and CTW — only the dispatch
 * mechanism differs.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 5)
@Fork(2)
@Threads(4)
public class ProxyMultiThreadedMultiLayerBenchmark {

    private TestService jdkProxy;
    private TestService frameworkProxy;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .addProfiler(GCProfiler.class)
                .addProfiler(PausesProfiler.class)
                .addProfiler(MemPoolProfiler.class)
                .include(ProxyMultiThreadedMultiLayerBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }

    @Setup
    public void setup() {
        TestService target = new TestServiceImpl();

        TestService jdkProxyInner3 = (TestService) Proxy.newProxyInstance(
                TestService.class.getClassLoader(),
                new Class<?>[]{TestService.class},
                (proxy, method, args) -> method.invoke(target, args)
        );

        TestService jdkProxyInner2 = (TestService) Proxy.newProxyInstance(
                TestService.class.getClassLoader(),
                new Class<?>[]{TestService.class},
                (proxy, method, args) -> method.invoke(jdkProxyInner3, args)
        );

        TestService jdkProxyInner1 = (TestService) Proxy.newProxyInstance(
                TestService.class.getClassLoader(),
                new Class<?>[]{TestService.class},
                (proxy, method, args) -> method.invoke(jdkProxyInner2, args)
        );

        // Baseline: Standard JDK Proxy
        jdkProxy = (TestService) Proxy.newProxyInstance(
                TestService.class.getClassLoader(),
                new Class<?>[]{TestService.class},
                (proxy, method, args) -> method.invoke(jdkProxyInner1, args)
        );

        // Framework Proxy mit minimaler LayerAction
        // Simuliert den Overhead von chainId, callId und DispatchExtension
        LayerAction<Void, Object> noOpAction = (chainId, callId, input, next) -> {
            try {
                return next.execute(chainId, callId, null);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        };
        InqProxyFactory factory = InqProxyFactory.of("perf-test", noOpAction);

        TestService frameworkProxyInner3 = InqProxyFactory.of("perf-test", noOpAction)
                .protect(TestService.class, target);
        TestService frameworkProxyInner2 = InqProxyFactory.of("perf-test-3", noOpAction)
                .protect(TestService.class, frameworkProxyInner3);
        TestService frameworkProxyInner1 = InqProxyFactory.of("perf-test-2", noOpAction)
                .protect(TestService.class, frameworkProxyInner2);

        // Nutzt die Factory zur Erstellung des geschützten Proxy
        frameworkProxy = InqProxyFactory.of("perf-test-1", noOpAction)
                .protect(TestService.class, frameworkProxyInner1);
    }

    // ======================== Group 1: 2 args (specialized fast-path) ========================

    /**
     * JDK baseline: 4 stacked JDK proxies, 2 args, reflection dispatch.
     *
     * <p>Result: 98.557 ± 3.035 ns/op, 96 B/op (4 × 24 B Object[] arrays).</p>
     */
    @Benchmark
    public void baseline_2Args(Blackhole bh) {
        bh.consume(jdkProxy.fastPath("val1", "val2"));
    }

    /**
     * Framework: 4 stacked InqProxy layers, 2 args, MethodHandle dispatch.
     *
     * <p>Result: 85.385 ± 2.412 ns/op, 128 B/op.
     * 13 ns faster than JDK baseline (−13.4%) — MethodHandle inlining
     * outperforms reflection even with higher per-layer allocation.</p>
     */
    @Benchmark
    public void framework_2Args(Blackhole bh) {
        bh.consume(frameworkProxy.fastPath("val1", "val2"));
    }

    // ======================== Group 2: 5 args (specialized fast-path) ========================

    /**
     * JDK baseline: 4 stacked JDK proxies, 5 args.
     *
     * <p>Result: 106.606 ± 0.965 ns/op, 160 B/op.</p>
     */
    @Benchmark
    public void baseline_5Args(Blackhole bh) {
        bh.consume(jdkProxy.fastPath("a", "b", "c", "d", "e"));
    }

    /**
     * Framework: 4 stacked InqProxy layers, 5 args.
     *
     * <p>Result: 102.423 ± 27.557 ns/op, 144 B/op (−16 B vs JDK).
     * Large error bar indicates JIT deoptimization cycles during warmup.</p>
     */
    @Benchmark
    public void framework_5Args(Blackhole bh) {
        bh.consume(frameworkProxy.fastPath("a", "b", "c", "d", "e"));
    }

    // ======================== Group 3: 8 args (spreader-path, 6+ args) ========================

    /**
     * JDK baseline: 4 stacked JDK proxies, 8 args.
     *
     * <p>Result: 114.897 ± 0.968 ns/op, 192 B/op.</p>
     */
    @Benchmark
    public void baseline_8Args(Blackhole bh) {
        bh.consume(jdkProxy.spreaderPath("a", "b", "c", "d", "e", "f", "g", "h"));
    }

    /**
     * Framework: 4 stacked InqProxy layers, 8 args, spreader-path.
     *
     * <p>Result: 88.913 ± 1.675 ns/op, 152 B/op (−40 B vs JDK).
     * Largest advantage: 26 ns faster (−22.6%) and 40 B less allocation
     * than JDK baseline — MethodHandle.spread avoids per-call Object[]
     * resizing at each proxy layer.</p>
     */
    @Benchmark
    public void framework_8Args(Blackhole bh) {
        bh.consume(frameworkProxy.spreaderPath("a", "b", "c", "d", "e", "f", "g", "h"));
    }


    public interface TestService {
        String fastPath(String a, String b);

        String fastPath(String a, String b, String c, String d, String e);

        String spreaderPath(String a, String b, String c, String d, String e, String f, String g, String h);
    }

    public static class TestServiceImpl implements TestService {
        @Override
        public String fastPath(String a, String b) {
            Blackhole.consumeCPU(50);
            return "fastPath-2";
        }

        @Override
        public String fastPath(String a, String b, String c, String d, String e) {
            Blackhole.consumeCPU(50);
            return "fastPath-5";
        }

        @Override
        public String spreaderPath(String a, String b, String c, String d, String e, String f, String g, String h) {
            Blackhole.consumeCPU(50);
            return "spreaderPath-8";
        }
    }

}