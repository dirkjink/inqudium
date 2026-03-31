package eu.inqudium.core.callid;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.UUID;
import java.util.concurrent.TimeUnit;


/**
 * Microbenchmark to compare the throughput of different ID generation strategies.
 * <p>
 * Measures operations per millisecond to clearly show the performance differences
 * between standard implementations and our optimized zero-allocation generators.
 *
 * <p>
 * <h1>JMH Benchmark Results and Analysis.</h1>
 * <p>
 * The introduction of the Base64URL-encoded 96-bit ID generator ({@code Fast96BitIdBase64})
 * has set a new performance ceiling. It not only outperforms all other implementations in
 * raw speed but also sets a new low for memory allocation.
 *
 * <h3>1. Throughput Comparison (Operations per Millisecond)</h3>
 * <p>
 * This metric ({@code thrpt}) shows the number of IDs generated per millisecond.
 * A higher score means a faster generator.
 * <table border="1">
 * <caption>Throughput Results Comparison</caption>
 * <tr>
 * <th>Implementation</th>
 * <th>Score (ops/ms)</th>
 * <th>Relative Performance</th>
 * </tr>
 * <tr>
 * <td>Fast96BitIdBase64</td>
 * <td>~ 83,788</td>
 * <td><b>23.1x faster</b></td>
 * </tr>
 * <tr>
 * <td>Fast96BitIdHex</td>
 * <td>~ 69,228</td>
 * <td><b>19.1x faster</b></td>
 * </tr>
 * <tr>
 * <td>FastUUID</td>
 * <td>~ 52,754</td>
 * <td><b>14.5x faster</b></td>
 * </tr>
 * <tr>
 * <td>FastNanoId</td>
 * <td>~ 43,727</td>
 * <td><b>12.1x faster</b></td>
 * </tr>
 * <tr>
 * <td>StandardJavaUUID</td>
 * <td>~ 3,627</td>
 * <td>1.0x (Baseline)</td>
 * </tr>
 * </table>
 *
 * <h3>2. Memory Allocation (Bytes per Operation)</h3>
 * <p>
 * The {@code gc.alloc.rate.norm} metric shows the exact footprint of objects created
 * per method call. Lower is better, as it drastically reduces Garbage Collector overhead.
 * <table border="1">
 * <caption>Memory Allocation Comparison</caption>
 * <tr>
 * <th>Implementation</th>
 * <th>Allocation (B/op)</th>
 * <th>Memory Footprint vs Baseline</th>
 * </tr>
 * <tr>
 * <td>Fast96BitIdBase64</td>
 * <td>104 Bytes</td>
 * <td><b>- 67.5%</b></td>
 * </tr>
 * <tr>
 * <td>Fast96BitIdHex</td>
 * <td>128 Bytes</td>
 * <td><b>- 60.0%</b></td>
 * </tr>
 * <tr>
 * <td>FastNanoId</td>
 * <td>128 Bytes</td>
 * <td><b>- 60.0%</b></td>
 * </tr>
 * <tr>
 * <td>FastUUID</td>
 * <td>168 Bytes</td>
 * <td><b>- 47.5%</b></td>
 * </tr>
 * <tr>
 * <td>StandardJavaUUID</td>
 * <td>320 Bytes</td>
 * <td>0% (Baseline)</td>
 * </tr>
 * </table>
 *
 * <h3>Key Takeaways &amp; Technical Insights</h3>
 * <ul>
 * <li><b>The Base64 Advantage:</b> {@code Fast96BitIdBase64} is the undisputed winner. Generating a
 * 16-character string is significantly faster than generating a 24-character hex string, even though
 * both encode the exact same 96 bits of randomness.</li>
 * <li><b>Record Low Memory Footprint:</b> The Base64 implementation only allocates <b>104 bytes</b>
 * per operation. Because the final string is only 16 characters long, the underlying character array
 * (or byte array in modern Java versions via Compact Strings) is smaller than the 24-character array
 * required by the Hex implementation (128 bytes).</li>
 * <li><b>Fewer Writes, Higher Speed:</b> The Base64 method only requires 16 array writes (one for each
 * character) and utilizes slightly fewer, heavily optimized bitwise operations to extract 6 bits at a
 * time. The Hex method requires 24 array writes. At the scale of tens of thousands of operations per
 * millisecond, these saved CPU cycles create a massive 20% throughput increase over the Hex variant.</li>
 * <li><b>The Baseline Remains Far Behind:</b> The standard {@link java.util.UUID} remains stuck at
 * roughly ~3,600 ops/ms and 320 bytes per operation, highlighting once again how expensive
 * {@code SecureRandom} and object creation are when cryptographically secure uniqueness is not strictly
 * required.</li>
 * </ul>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class IdGenerationBenchmark {

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder().addProfiler("gc").include(IdGenerationBenchmark.class.getSimpleName()).build();
    new Runner(opt).run();
  }

  @Benchmark
  public void benchmarkFast96BitIdBase64(Blackhole blackhole) {
    // Consume the result using Blackhole to prevent the JIT compiler
    // from optimizing away the method call entirely (dead code elimination)
    blackhole.consume(Fast96BitId.randomIdBase64());
  }

  @Benchmark
  public void benchmarkFast96BitIdHex(Blackhole blackhole) {
    // Consume the result using Blackhole to prevent the JIT compiler
    // from optimizing away the method call entirely (dead code elimination)
    blackhole.consume(Fast96BitId.randomIdHex());
  }

  @Benchmark
  public void benchmarkFastNanoId(Blackhole blackhole) {
    // Consume the generated NanoID
    blackhole.consume(FastNanoId.randomNanoId());
  }

  @Benchmark
  public void benchmarkFastUUID(Blackhole blackhole) {
    // Consume the generated FastUUID string
    blackhole.consume(FastUUID.randomUUIDString());
  }

  @Benchmark
  public void benchmarkStandardJavaUUID(Blackhole blackhole) {
    // Baseline measurement using the standard java.util.UUID for comparison
    blackhole.consume(UUID.randomUUID().toString());
  }
}