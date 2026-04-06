package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.pipeline.LayerAction;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
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

import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark zur Prüfung der Proxy-Performance unter Multi-Thread-Last.
 * Fokus: Fast-Path (2 Args) vs. Spreader-Path (5 Args).
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

  @Benchmark
  public void baseline_2Args(Blackhole bh) {
    bh.consume(jdkProxy.fastPath("val1", "val2"));
  }

  @Benchmark
  public void framework_2Args(Blackhole bh) {
    bh.consume(frameworkProxy.fastPath("val1", "val2"));
  }

  // --- Gruppe 1: 2 Argumente (Spezialisierter Fast-Path) ---

  @Benchmark
  public void baseline_5Args(Blackhole bh) {
    bh.consume(jdkProxy.fastPath("a", "b", "c", "d", "e"));
  }

  @Benchmark
  public void framework_5Args(Blackhole bh) {
    bh.consume(frameworkProxy.fastPath("a", "b", "c", "d", "e"));
  }

  // --- Gruppe 2: 5 Argumente (Spezialisierter Fast-Path) ---

  @Benchmark
  public void baseline_8Args(Blackhole bh) {
    bh.consume(jdkProxy.spreaderPath("a", "b", "c", "d", "e", "f", "g", "h"));
  }

  @Benchmark
  public void framework_8Args(Blackhole bh) {
    bh.consume(frameworkProxy.spreaderPath("a", "b", "c", "d", "e", "f", "g", "h"));
  }

  // --- Gruppe 3: 8 Argumente (Spreader-Path mit 6+ Args) ---


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