package eu.inqudium.aspect.pipeline.integration;

import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import eu.inqudium.aspect.pipeline.AspectPipelineBuilder;
import eu.inqudium.aspect.pipeline.ResolvedPipeline;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.LayerAction;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.profile.MemPoolProfiler;
import org.openjdk.jmh.profile.PausesProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark for measuring the aspect pipeline overhead under multi-thread load.
 *
 * <p>Structured to produce results directly comparable to
 * {@code ProxyMultiThreadedBenchmark} from the proxy module. Both benchmarks
 * use the same JMH configuration (threads, warmup, measurement, forks),
 * the same {@code Blackhole.consumeCPU(50)} workload, and the same profilers.</p>
 *
 * <h3>Benchmark groups</h3>
 * <ul>
 *   <li><strong>Group 1 — Baseline vs. cached pipeline (1 layer):</strong>
 *       Measures the minimal overhead of the {@link ResolvedPipeline} compared
 *       to a bare {@link JoinPointExecutor#proceed()} call. Analogous to
 *       {@code baseline_2Args} vs. {@code framework_2Args} in the proxy benchmark.</li>
 *   <li><strong>Group 2 — Layer depth scaling (1 / 3 / 5 layers):</strong>
 *       Measures how the pre-composed chain factory scales with layer count.
 *       Analogous to the arg-count variation (2 / 5 / 8) in the proxy benchmark,
 *       which tests the MethodHandle dispatch path scaling.</li>
 *   <li><strong>Group 3 — Cached vs. uncached (3 layers):</strong>
 *       Compares {@link ResolvedPipeline} (pre-composed, zero per-call allocation)
 *       against {@link AspectPipelineBuilder} (fresh {@code JoinPointWrapper}
 *       chain per invocation). Demonstrates the caching optimization.</li>
 * </ul>
 *
 * <h3>Interpreting results</h3>
 * <p>The {@code baseline_directCall} benchmark establishes the floor — the cost
 * of the simulated method body ({@code consumeCPU(50)}) plus a lambda invocation.
 * All framework benchmarks add overhead on top of this floor. The delta between
 * baseline and framework is the aspect pipeline's cost per invocation.</p>
 *
 * <p>GCProfiler reveals per-call allocation: {@code ResolvedPipeline} should show
 * near-zero allocation (only the terminal lambda), while the uncached builder
 * path allocates N wrapper objects per call.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 5)
@Fork(2)
@Threads(4)
public class AspectPipelineMultiThreadedBenchmark {

    // ======================== Shared state ========================

    /**
     * No-op layer action — identical to the one used in the proxy benchmark.
     * Forwards to the next step without any processing, measuring pure
     * chain traversal overhead.
     */
    private static final LayerAction<Void, Object> NO_OP_ACTION =
            (chainId, callId, input, next) -> next.execute(chainId, callId, null);

    /**
     * The target method used as cache key for ResolvedPipeline lookups.
     * Resolved once in setup to avoid reflection overhead in the benchmark loop.
     */
    private Method targetMethod;

    /**
     * Simulated service method — the equivalent of pjp::proceed in an @Around advice.
     * Uses consumeCPU(50) to match the proxy benchmark's workload exactly.
     */
    private JoinPointExecutor<Object> coreExecutor;

    // --- Pre-resolved pipelines (setup once, reused across all iterations) ---
    private ResolvedPipeline pipeline1Layer;
    private ResolvedPipeline pipeline3Layers;
    private ResolvedPipeline pipeline5Layers;

    // --- Uncached builder path (for comparison) ---
    private List<AspectLayerProvider<Object>> providers3Layers;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .addProfiler(GCProfiler.class)
                .addProfiler(PausesProfiler.class)
                .addProfiler(MemPoolProfiler.class)
                .include(AspectPipelineMultiThreadedBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }

    @Setup
    public void setup() throws NoSuchMethodException {
        // Resolve the target method for canHandle filtering
        targetMethod = ServiceStub.class.getMethod("execute");

        // Core executor: simulates the real method body — same consumeCPU(50)
        // as the proxy benchmark's TestServiceImpl
        coreExecutor = () -> {
            Blackhole.consumeCPU(50);
            return "result";
        };

        // Build provider lists with 1, 3, and 5 no-op layers
        List<AspectLayerProvider<Object>> providers1 = buildProviders(1);
        providers3Layers = buildProviders(3);
        List<AspectLayerProvider<Object>> providers5 = buildProviders(5);

        // Pre-resolve pipelines — this is what happens once per Method in production
        pipeline1Layer = ResolvedPipeline.resolve(providers1, targetMethod);
        pipeline3Layers = ResolvedPipeline.resolve(providers3Layers, targetMethod);
        pipeline5Layers = ResolvedPipeline.resolve(providers5, targetMethod);
    }

    /**
     * Builds a list of N no-op layer providers with ascending order values.
     * Each provider accepts all methods (default canHandle).
     */
    private static List<AspectLayerProvider<Object>> buildProviders(int count) {
        List<AspectLayerProvider<Object>> providers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int order = (i + 1) * 10;
            String name = "LAYER_" + (i + 1);
            providers.add(new AspectLayerProvider<>() {
                @Override
                public String layerName() {
                    return name;
                }

                @Override
                public int order() {
                    return order;
                }

                @Override
                public LayerAction<Void, Object> layerAction() {
                    return NO_OP_ACTION;
                }
            });
        }
        return providers;
    }

    // ======================== Group 1: Baseline vs. 1-layer pipeline ========================

    /**
     * Baseline: direct JoinPointExecutor.proceed() — no pipeline, no layers.
     * Establishes the performance floor (consumeCPU + lambda call overhead).
     *
     * <p>Analogous to {@code baseline_2Args} in the proxy benchmark.</p>
     */
    @Benchmark
    public void baseline_directCall(Blackhole bh) throws Throwable {
        bh.consume(coreExecutor.proceed());
    }

    /**
     * Framework: 1 no-op layer through the cached ResolvedPipeline.
     * Measures the minimal overhead of the aspect pipeline infrastructure.
     *
     * <p>Analogous to {@code framework_2Args} in the proxy benchmark —
     * both measure the cost of a single no-op LayerAction dispatch.</p>
     */
    @Benchmark
    public void cached_1Layer(Blackhole bh) throws Throwable {
        bh.consume(pipeline1Layer.execute(coreExecutor));
    }

    // ======================== Group 2: Layer depth scaling ========================

    /**
     * 3 no-op layers through the cached ResolvedPipeline.
     *
     * <p>Analogous to {@code framework_5Args} in the proxy benchmark —
     * both add moderate complexity to the dispatch path.</p>
     */
    @Benchmark
    public void cached_3Layers(Blackhole bh) throws Throwable {
        bh.consume(pipeline3Layers.execute(coreExecutor));
    }

    /**
     * 5 no-op layers through the cached ResolvedPipeline.
     *
     * <p>Analogous to {@code framework_8Args} in the proxy benchmark —
     * both stress the upper end of realistic dispatch complexity.</p>
     */
    @Benchmark
    public void cached_5Layers(Blackhole bh) throws Throwable {
        bh.consume(pipeline5Layers.execute(coreExecutor));
    }

    // ======================== Group 3: Cached vs. uncached (3 layers) ========================

    /**
     * Uncached path: builds a fresh JoinPointWrapper chain from
     * AspectPipelineBuilder on every invocation. This is what the
     * framework did before ResolvedPipeline was introduced.
     *
     * <p>Expected to show significantly higher allocation rates in GCProfiler
     * (N wrapper objects per call) and higher average time compared to
     * {@link #cached_3Layers}.</p>
     */
    @Benchmark
    public void uncached_3Layers(Blackhole bh) throws Throwable {
        bh.consume(
                new AspectPipelineBuilder<Object>()
                        .addProviders(providers3Layers, targetMethod)
                        .buildChain(coreExecutor)
                        .proceed()
        );
    }

    // ======================== Test infrastructure ========================

    /**
     * Minimal stub interface — provides a Method reference for canHandle filtering.
     * The actual execution goes through the JoinPointExecutor, not through
     * reflection on this interface.
     */
    public interface ServiceStub {
        Object execute();
    }
}
