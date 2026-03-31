package eu.inqudium.core.bulkhead;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A latency-based adaptive limit algorithm inspired by TCP Vegas congestion control.
 *
 * <h2>Algorithm Overview</h2>
 * <p>Unlike {@link AimdLimitAlgorithm}, which is <b>reactive</b> and waits for explicit errors
 * (timeouts, 5xx responses) to reduce the limit, Vegas is <b>proactive</b>. It continuously
 * monitors request latency and detects queuing delay in the downstream service <em>before</em>
 * failures occur. The core insight is that rising latency is an early warning signal of
 * congestion — if the downstream service starts taking longer to respond, it is likely
 * building up an internal queue, and the caller should back off before that queue overflows
 * into errors.
 *
 * <h2>The Three Pillars of Vegas</h2>
 *
 * <h3>1. No-Load RTT (Baseline)</h3>
 * <p>The algorithm maintains an estimate of the <b>best-case response time</b> — the physical
 * minimum time required when the downstream system has zero queued requests. This represents
 * the inherent processing latency without any queuing overhead.
 *
 * <p>The baseline is initialized to {@link Long#MAX_VALUE} (unknown) and converges toward
 * reality as samples arrive. It tracks the minimum observed RTT, but with a configurable
 * <b>decay factor</b> that slowly drifts it upward toward the smoothed RTT.
 * Without decay, a single artificially low measurement (e.g., a cached response, a GC
 * pause that delayed the timestamp before the call but not after) would permanently poison
 * the baseline, causing the gradient to chronically overestimate congestion.
 *
 * <h3>2. Smoothed Current RTT (EWMA)</h3>
 * <p>The algorithm calculates an Exponentially Weighted Moving Average of recent response
 * times. This filters out random latency spikes (GC pauses, network jitter, database lock
 * contention) and provides a stable "current conditions" signal. The smoothing factor
 * controls sensitivity: lower values produce a smoother, slower-reacting average.
 *
 * <h3>3. The Gradient</h3>
 * <p>The ratio {@code noLoadRtt / smoothedRtt} is the gradient — the key signal that drives
 * all limit adjustments:
 * <ul>
 *   <li>{@code gradient ≈ 1.0}: The current RTT matches the baseline. No queuing detected,
 *       the downstream system is operating at its physical minimum. The limit can safely
 *       increase.</li>
 *   <li>{@code gradient = 0.5}: The current RTT is twice the baseline. The downstream system
 *       is spending roughly half its time processing and half queuing. The limit should
 *       decrease.</li>
 *   <li>{@code gradient > 1.0}: The current RTT is actually faster than the baseline. This
 *       can happen when the baseline was initially set during a warm-up period with higher
 *       latency. Capped at 1.2 to prevent runaway growth.</li>
 * </ul>
 *
 * <p>The new limit is then: {@code newLimit = currentLimit × gradient + probingFactor}
 *
 * <h2>Advantages over AIMD</h2>
 * <ul>
 *   <li><b>Proactive protection:</b> Reduces load before the downstream service fails,
 *       avoiding timeouts entirely in steady state.</li>
 *   <li><b>Smooth adjustments:</b> No violent sawtooth drops — the limit glides down
 *       proportionally to the observed congestion level.</li>
 *   <li><b>Ultra-low latency:</b> Keeps the downstream system operating near its
 *       queue-free baseline, maximizing throughput at minimal latency.</li>
 * </ul>
 *
 * <h2>Known Limitations</h2>
 * <ul>
 *   <li><b>The latecomer problem:</b> If the algorithm starts while the downstream system
 *       is already under heavy load, the initial "no-load" baseline will be too high.
 *       The gradient will appear close to 1.0 even under congestion, and the algorithm
 *       will fail to throttle. The baseline decay mitigates but does not fully
 *       solve this — it takes time for the baseline to converge downward.</li>
 *   <li><b>Jitter sensitivity:</b> Workloads with naturally high latency variance (e.g.,
 *       a mix of simple SELECTs at 2ms and complex JOINs at 200ms) confuse the gradient.
 *       The 200ms query looks like congestion even if the backend is healthy.</li>
 *   <li><b>Over-throttling:</b> In highly variable network environments, the algorithm
 *       tends to be pessimistic and may underutilize available capacity.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>All mutable state ({@code noLoadRttNanos}, {@code smoothedRttNanos}, {@code currentLimit})
 * is bundled into a single immutable {@link VegasState} record and managed via
 * {@link AtomicReference#compareAndSet(Object, Object)}. This is the same pattern used by
 * {@link AimdLimitAlgorithm} and guarantees that every {@link #update(Duration, boolean)}
 * call reads and writes a consistent snapshot without any locking or blocking.
 *
 * <p>The original implementation used three independent atomic variables. Concurrent threads
 * could interleave between individual reads, causing the gradient calculation to mix a
 * {@code noLoadRtt} from one thread's update with a {@code smoothedRtt} from another —
 * producing inconsistent and potentially erratic limit adjustments. The single-reference
 * CAS pattern eliminates this class of bugs entirely.
 *
 * <h2>Comparison with {@link AimdLimitAlgorithm}</h2>
 * <table>
 *   <tr><th>Aspect</th><th>Vegas</th><th>AIMD</th></tr>
 *   <tr><td>Signal</td><td>Latency (queuing delay)</td><td>Errors (timeouts, exceptions)</td></tr>
 *   <tr><td>Detection</td><td>Proactive — detects congestion early</td><td>Reactive — waits for failures</td></tr>
 *   <tr><td>Adjustment</td><td>Smooth gradient-based scaling</td><td>Sharp sawtooth (additive up, multiplicative down)</td></tr>
 *   <tr><td>State</td><td>3 values (baseline RTT, smoothed RTT, limit)</td><td>2 values (limit, error rate)</td></tr>
 *   <tr><td>Best for</td><td>Latency-sensitive systems, stable workloads</td><td>Error-driven systems, variable workloads</td></tr>
 *   <tr><td>Risk</td><td>Over-throttling on jittery workloads</td><td>Under-throttling until first error</td></tr>
 * </table>
 *
 * @see AimdLimitAlgorithm
 * @see InqLimitAlgorithm
 * @since 0.2.0
 */
public final class VegasLimitAlgorithm implements InqLimitAlgorithm {

  // ──────────────────────────────────────────────────────────────────────────
  // Configuration Fields (immutable after construction)
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * The absolute minimum concurrency limit. The algorithm will never reduce the limit
   * below this floor, regardless of how severe the detected congestion is.
   *
   * <p>This safety net ensures the system always retains enough capacity to send probe
   * requests. Without probes, the algorithm would have no new RTT samples and could
   * never detect that congestion has cleared — permanently locking itself at minLimit.
   *
   * <p>Clamped to at least 1 during construction. A limit of 0 would create a deadlock:
   * no requests can be sent, so no RTT samples arrive, and the algorithm can never
   * recover.
   */
  private final int minLimit;

  /**
   * The absolute maximum concurrency limit. The algorithm will never increase the limit
   * above this ceiling, even if the gradient indicates zero congestion.
   *
   * <p>This prevents unbounded resource consumption (threads, connections, file descriptors)
   * on the calling side, and protects the downstream service from sudden traffic spikes
   * that could occur if the algorithm overestimates available capacity.
   *
   * <p>Clamped to at least {@code minLimit} during construction to guarantee a valid range.
   */
  private final int maxLimit;

  /**
   * The EWMA smoothing factor for the current RTT average (alpha, range: 0.01–1.0).
   *
   * <p>Controls how much weight each new RTT sample carries in the smoothed average.
   * The EWMA formula is: {@code smoothed = oldSmoothed × (1 - α) + newSample × α}
   *
   * <p>The effective "memory" of the EWMA is approximately {@code 1/α} samples:
   * <ul>
   *   <li>{@code 0.05}: Very smooth — last ~20 samples dominate. Excellent at filtering
   *       GC pauses and random jitter, but slow to react to genuine latency shifts.
   *       Best for stable, predictable workloads.</li>
   *   <li>{@code 0.2}: Moderate smoothing (recommended default). Last ~5 samples dominate.
   *       Good balance between noise filtering and responsiveness. Handles typical
   *       microservice latency patterns well.</li>
   *   <li>{@code 0.5}: Responsive — reacts quickly to latency changes. Useful when the
   *       downstream service's performance shifts rapidly (e.g., autoscaling events,
   *       cache invalidations).</li>
   *   <li>{@code 1.0}: No smoothing — each sample fully overwrites the average. The
   *       algorithm reacts instantly to every RTT fluctuation, making it highly sensitive
   *       to noise. Generally not recommended for production.</li>
   * </ul>
   *
   * <p>Clamped to [0.01, 1.0] during construction.
   */
  private final double smoothingFactor;

  /**
   * The rate at which the no-load baseline slowly drifts toward the smoothed RTT
   * (range: 0.0–0.1).
   *
   * <h3>Baseline Decay — Why This Exists</h3>
   * <p>Without decay, the no-load baseline ({@code noLoadRttNanos}) is <b>monotonically
   * decreasing</b> — it only ever moves downward via {@code Math.min(current, newSample)}.
   * This creates a critical vulnerability: if a single outlier arrives with an artificially
   * low RTT (e.g., a response served from an in-process cache, a timing artifact during
   * a GC pause, or a measurement error from nanosecond clock granularity), the baseline
   * locks onto that value permanently.
   *
   * <p>The consequence is severe: with the baseline stuck at, say, 500ns while real
   * no-load latency is 5ms, the gradient {@code 500ns / 5ms = 0.0001} would indicate
   * extreme congestion at all times. The algorithm would chronically throttle the limit
   * down to {@code minLimit} and never recover, even if the downstream system is
   * completely healthy.
   *
   * <h3>How Decay Works</h3>
   * <p>On each update, the baseline is nudged toward the smoothed RTT by a small fraction:
   * {@code decayed = baseline × (1 - decay) + smoothedRtt × decay}
   *
   * <p>This pulls the baseline <em>upward</em> over time, counteracting the monotonic
   * downward pull of {@code Math.min}. The two forces reach an equilibrium where the
   * baseline settles slightly below the true no-load latency — close enough for an
   * accurate gradient, but not locked onto a one-time aberration.
   *
   * <p>The decay is capped so the baseline never exceeds the smoothed RTT. The baseline
   * should always represent the "best case" (no queuing), not the "average case".
   *
   * <p>Effect of different values:
   * <ul>
   *   <li>{@code 0.0}: No decay — original behavior. The baseline is the all-time minimum
   *       and can never recover from an artificially low outlier. Only appropriate if the
   *       RTT measurement is guaranteed to be free of artifacts.</li>
   *   <li>{@code 0.01} (default): 1% drift per update. Very gentle — at 100 RPS, the
   *       baseline moves by ~1% per second. Enough to recover from a bad outlier over
   *       minutes, but too slow to track rapid changes in true no-load latency.</li>
   *   <li>{@code 0.05}: 5% drift per update. More aggressive recovery. Suitable for
   *       environments where the downstream service's baseline latency changes frequently
   *       (e.g., cloud VMs with variable performance).</li>
   *   <li>{@code 0.1}: Maximum allowed decay. The baseline converges toward the smoothed
   *       RTT quite rapidly, which risks making the gradient always appear close to 1.0
   *       and under-throttling during real congestion.</li>
   * </ul>
   *
   * <p>Clamped to [0.0, 0.1] during construction. Values above 0.1 would cause the
   * baseline to track the smoothed RTT too closely, effectively disabling the
   * congestion detection mechanism.
   */
  private final double baselineDecayFactor;

  // ──────────────────────────────────────────────────────────────────────────
  // Mutable State (managed via CAS on an immutable snapshot)
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * The atomic reference holding the current algorithm state. All reads and writes go
   * through this single reference using compare-and-set, ensuring that all three state
   * fields ({@code noLoadRttNanos}, {@code smoothedRttNanos}, {@code currentLimit}) are
   * always read and written as a consistent triple.
   *
   * <p>The alternative — three separate {@code AtomicLong}/{@code AtomicReference} fields —
   * would allow interleaved reads: thread A reads {@code noLoadRttNanos} from state X,
   * then thread B updates all three fields to state Y, then thread A reads
   * {@code smoothedRttNanos} from state Y. The gradient calculation would then mix a
   * baseline from one point in time with a smoothed RTT from another, producing an
   * inconsistent and potentially erratic result.
   *
   * <p>Using {@code synchronized} was rejected because core algorithm implementations
   * must never block — they are called from the hot path of every completed request.
   * The CAS loop is wait-free in practice: the compute step is pure arithmetic (no I/O,
   * no memory allocation), and contention is bounded by the number of threads concurrently
   * completing calls through this bulkhead.
   *
   * @see VegasState
   */
  private final AtomicReference<VegasState> state;

  // ──────────────────────────────────────────────────────────────────────────
  // Constructors
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Creates a new Vegas limit algorithm with the default baseline decay of 1%.
   *
   * <p>This is the recommended constructor for most use cases. The 1% decay rate provides
   * a good balance: slow enough to maintain a stable baseline during normal operation,
   * but fast enough to recover from an artificially low outlier within minutes at typical
   * request rates.
   *
   * @param initialLimit    The starting concurrency limit before any RTT feedback is
   *                        received. Should reflect a conservative estimate of the
   *                        downstream service's capacity. Clamped to
   *                        [{@code minLimit}, {@code maxLimit}] if out of range.
   * @param minLimit        The absolute minimum concurrency. Clamped to at least 1.
   * @param maxLimit        The absolute maximum concurrency. Clamped to at least
   *                        {@code minLimit}.
   * @param smoothingFactor The EWMA alpha for RTT smoothing (0.01–1.0). Lower values
   *                        produce a smoother, more stable signal. Recommended: 0.2.
   */
  public VegasLimitAlgorithm(int initialLimit, int minLimit, int maxLimit, double smoothingFactor) {
    this(initialLimit, minLimit, maxLimit, smoothingFactor, 0.01);
  }

  /**
   * Creates a new Vegas limit algorithm with full control over baseline decay behavior.
   *
   * <p>Use this constructor when the default 1% decay is not appropriate — for example,
   * in environments with very stable latency (decay=0.0 to disable) or with highly
   * variable infrastructure latency (decay=0.05 for faster convergence).
   *
   * <p><b>Recommended production configuration:</b>
   * <pre>{@code
   * new VegasLimitAlgorithm(
   *     50,    // initialLimit: conservative starting point
   *     5,     // minLimit: always allow probe requests
   *     200,   // maxLimit: prevent resource exhaustion
   *     0.2,   // smoothingFactor: moderate EWMA, filters jitter well
   *     0.01   // baselineDecayFactor: 1% drift, slow but safe recovery
   * );
   * }</pre>
   *
   * @param initialLimit       The starting concurrency limit. Clamped to
   *                           [{@code minLimit}, {@code maxLimit}].
   * @param minLimit           The absolute minimum concurrency. Clamped to at least 1.
   * @param maxLimit           The absolute maximum concurrency. Clamped to at least
   *                           {@code minLimit}.
   * @param smoothingFactor    The EWMA alpha for RTT smoothing (0.01–1.0).
   * @param baselineDecayFactor Rate at which the no-load baseline drifts toward the
   *                            smoothed RTT per update (0.0–0.1). A value of 0.0 disables
   *                            decay entirely (original all-time-minimum behavior).
   */
  public VegasLimitAlgorithm(int initialLimit, int minLimit, int maxLimit,
                             double smoothingFactor, double baselineDecayFactor) {

    // Clamp all parameters to valid ranges. Same rationale as AimdLimitAlgorithm:
    // bulkhead configuration often comes from external config files, and slightly
    // out-of-range values should not crash the application at startup.
    this.minLimit = Math.max(1, minLimit);
    this.maxLimit = Math.max(this.minLimit, maxLimit);
    this.smoothingFactor = Math.max(0.01, Math.min(1.0, smoothingFactor));
    this.baselineDecayFactor = Math.max(0.0, Math.min(0.1, baselineDecayFactor));

    // Clamp the initial limit and start with an "unknown" baseline.
    // noLoadRttNanos = Long.MAX_VALUE means "no baseline established yet" — the first
    // RTT sample will immediately become the baseline via Math.min.
    // smoothedRttNanos = 0 means "no smoothed average yet" — the first sample will
    // be used as-is (see the initialization branch in update()).
    double bounded = Math.max(this.minLimit, Math.min(initialLimit, this.maxLimit));
    this.state = new AtomicReference<>(new VegasState(Long.MAX_VALUE, 0, bounded));
  }

  // ──────────────────────────────────────────────────────────────────────────
  // InqLimitAlgorithm Interface
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Returns the current concurrency limit as an integer.
   *
   * <p>Internally, the limit is stored as a {@code double} because the gradient-based
   * adjustment ({@code currentLimit × gradient + probingFactor}) produces fractional
   * results. The truncation to {@code int} means the visible limit changes only when
   * the fractional value crosses an integer boundary.
   *
   * <p>For example, at gradient=0.98 and limit=100.0, the new limit is 98.5 → visible
   * limit stays at 98. The next update with gradient=0.98 produces 97.03 → visible limit
   * drops to 97. This natural quantization smooths out micro-adjustments.
   *
   * @return the current limit, always in [{@code minLimit}, {@code maxLimit}]
   */
  @Override
  public int getLimit() {
    // Add a tiny epsilon (1e-9) to counteract IEEE 754 floating-point precision loss.
    // Even with a fixed integer denominator, summing fractions like 0.1 ten times in
    // binary floating-point arithmetic results in 10.999999999999998 rather than exactly 11.0.
    // The explicit cast to (int) performs strict truncation. Without this epsilon,
    // 10.999999999999998 would incorrectly truncate back down to 10, failing to cross
    // the integer boundary despite a mathematically complete window.
    return (int) (state.get().currentLimit() + 1e-9);
  }

  /**
   * Updates the algorithm state based on the outcome and latency of a completed call.
   *
   * <p>This is the core of the Vegas algorithm, executed on every completed request.
   * It performs four steps atomically via a CAS retry loop:
   * <ol>
   *   <li><b>Baseline maintenance:</b> Update the no-load RTT estimate with optional decay.</li>
   *   <li><b>Smoothed RTT update:</b> Feed the new sample into the EWMA.</li>
   *   <li><b>Gradient computation:</b> Calculate the congestion signal.</li>
   *   <li><b>Limit adjustment:</b> Scale the limit by the gradient.</li>
   * </ol>
   *
   * <p>Zero or negative RTT values are silently ignored — they indicate measurement errors
   * or degenerate calls that should not influence the concurrency limit.
   *
   * <h3>CAS Retry Loop</h3>
   * <p>The loop reads the current immutable state, computes a new state (pure arithmetic,
   * no side effects), and attempts an atomic swap. On contention (another thread updated
   * between read and CAS), it re-reads and recomputes with the latest state. No thread
   * ever blocks or parks — the loop is wait-free in practice since the compute step is
   * a handful of floating-point operations.
   *
   * @param rtt       the round-trip time measured by the paradigm strategy. Values
   *                  {@code <= 0} are silently ignored as they indicate measurement errors.
   * @param isSuccess {@code true} if the call completed without error. On failure, the
   *                  algorithm applies a fixed multiplicative decrease (×0.8) as a fallback,
   *                  since failure-mode RTT values may not be representative of real
   *                  downstream latency (e.g., a timeout fires at a fixed duration regardless
   *                  of actual backend state).
   */
  @Override
  public void update(Duration rtt, boolean isSuccess) {
    long rttNanos = rtt.toNanos();

    // Guard: ignore degenerate RTT values. A zero or negative RTT typically indicates
    // a measurement error (e.g., insufficient nanosecond clock resolution, a no-op call,
    // or a Duration.ZERO from a failed measurement). Feeding it into the algorithm would
    // corrupt both the baseline (pulling it to zero permanently) and the smoothed average.
    if (rttNanos <= 0) return;

    // Lock-free CAS retry loop: read → compute → swap. See CAS documentation in
    // AimdLimitAlgorithm.update() for a detailed explanation of the pattern.
    VegasState current;
    VegasState next;
    do {
      current = state.get();

      // Apply decay — slowly drift the baseline toward the smoothed RTT.
      //
      // The decay counteracts the monotonic downward pull of Math.min. Without it:
      //   1. An outlier arrives with rtt=500ns (e.g., cached response)
      //   2. Math.min locks the baseline at 500ns permanently
      //   3. If the true no-load latency is 5ms, gradient = 500ns/5ms = 0.0001
      //   4. The algorithm sees "extreme congestion" and throttles to minLimit forever
      //
      // With decay at 1%, the baseline slowly drifts back toward reality:
      //   decayed = 500ns × 0.99 + 5ms × 0.01 = 50,495ns ≈ 50μs
      //   After ~460 updates: baseline ≈ 5ms (fully recovered)
      //
      // The decay is applied only when:
      //   - baselineDecayFactor > 0.0 (decay is enabled)
      //   - smoothedRttNanos > 0 (a smoothed average has been established)
      //   - candidateNoLoad < smoothedRttNanos (baseline is below average — the normal
      //     case; if baseline equals or exceeds the average, there's nothing to decay toward)
      //
      // The result is capped at smoothedRttNanos to ensure the baseline never drifts
      // above the average. The baseline should always represent "best case, no queuing",
      // never "average case with some queuing".
      final long newNoLoad;
      if (isSuccess) {
        // ── Step 1: Maintain the No-Load Baseline ──
        //
        // The baseline represents the physical minimum response time when the downstream
        // system has zero queued requests. It is the anchor for the gradient calculation:
        // gradient = baseline / smoothedRtt.
        //
        // First, take the minimum of the current baseline and the new sample.
        // This allows the baseline to converge downward toward the true minimum.
        long candidateNoLoad = Math.min(current.noLoadRttNanos(), rttNanos);
        if (baselineDecayFactor > 0.0 && current.smoothedRttNanos() > 0
            && candidateNoLoad < current.smoothedRttNanos()) {
          long decayed = (long) (candidateNoLoad * (1.0 - baselineDecayFactor)
              + current.smoothedRttNanos() * baselineDecayFactor);
          newNoLoad = Math.min(decayed, current.smoothedRttNanos());
        } else {
          // No decay applied. Either decay is disabled, no smoothed average exists yet,
          // or the baseline is already at or above the smoothed average.
          newNoLoad = candidateNoLoad;
        }
      } else {
        // The problem: Failed requests often have extremely atypical latencies.
        // An immediate connection failure (Connection Refused) might have an RTT of 1ms,
        // while a timeout corresponds exactly to the configured maximum duration.
        // When a "Fast Fail" occurs, noLoadRttNanos is reduced to this tiny value (Math.min).
        // When the system recovers and successful requests again take a real 50ms,
        // the algorithm calculates the gradient as 1ms / 50ms = 0.02. Vegas interprets this
        // as a catastrophic overload and permanently throttles the system to the absolute
        // minimum (minLimit).
        newNoLoad = current.noLoadRttNanos();
      }

      final long newSmoothed;
      if (isSuccess) {
        // ── Step 2: Update the Smoothed Current RTT (EWMA) ──
        //
        // The smoothed RTT is an Exponentially Weighted Moving Average that filters out
        // random latency noise (GC pauses, network jitter, occasional slow queries) and
        // provides a stable "current conditions" signal for the gradient.
        //
        // Formula: smoothed = oldSmoothed × (1 - α) + newSample × α
        //
        // Special case: when smoothedRttNanos == 0 (first sample ever), we use the raw
        // sample as-is. This avoids a cold-start problem where the EWMA would be
        // artificially low (pulled toward 0) for the first few samples, causing the
        // gradient to overestimate congestion.
        newSmoothed = current.smoothedRttNanos() == 0
            ? rttNanos
            : (long) (current.smoothedRttNanos() * (1 - smoothingFactor) + rttNanos * smoothingFactor);
      } else {
        newSmoothed = current.smoothedRttNanos();
      }

      double newLimit;

      if (isSuccess) {
        // ── Step 3: Compute the Gradient ──
        //
        // The gradient is the heart of Vegas: it quantifies how much the downstream
        // system is slowing down relative to its best-case performance.
        //
        //   gradient = noLoadRtt / smoothedRtt
        //
        // Interpretation:
        //   1.0  → Current latency matches the baseline. No queuing detected.
        //          The downstream system is operating at peak efficiency.
        //   0.5  → Current latency is 2× the baseline. Roughly half the time is
        //          spent in the downstream system's internal queue.
        //   0.25 → Current latency is 4× the baseline. Severe queuing — 75% of
        //          response time is queue wait, only 25% is actual processing.
        //   >1.0 → Current latency is better than the recorded baseline. This can
        //          happen when the baseline was established during a warm-up period,
        //          or when the downstream service's performance has improved (e.g.,
        //          cache warming, JIT compilation completing).
        //
        // Clamping:
        //   - Minimum 0.5: Prevents the limit from dropping by more than 50% in a
        //     single update, even under extreme congestion. This avoids oscillations
        //     where a massive drop undershoots, triggering an equally massive rebound.
        //   - Maximum 1.2: Prevents runaway growth when the gradient exceeds 1.0.
        //     A 20% overshoot allowance lets the algorithm gently probe beyond its
        //     current baseline, but the cap prevents explosive growth from a
        //     temporarily low smoothed RTT.
        double gradient = (newSmoothed > 0) ? (double) newNoLoad / (double) newSmoothed : 1.0;
        gradient = Math.max(0.5, Math.min(1.2, gradient));

        // ── Step 4: Calculate the New Concurrency Limit ──
        // ── Proactive Gradient-Based Adjustment ──
        //
        // Scale the current limit by the gradient and add a small probing factor (+0.5).
        //
        // The gradient scaling is the key differentiator from AIMD:
        //   - gradient=1.0 (no congestion): newLimit ≈ currentLimit + 0.5
        //     → Gentle upward probing, similar to AIMD's additive increase.
        //   - gradient=0.7 (mild congestion): newLimit ≈ currentLimit × 0.7 + 0.5
        //     → Proportional reduction. At limit=100, this yields ~70.5.
        //   - gradient=0.5 (severe congestion): newLimit ≈ currentLimit × 0.5 + 0.5
        //     → Aggressive reduction, comparable to AIMD's multiplicative decrease.
        //
        // Windowed Probing Accumulation
        // Calculate the probing factor based on the integer portion of the limit (visibleLimit).
        // If we use the raw double (current.currentLimit()), the denominator grows with
        // each fractional increment (e.g., 10.0 -> 10.1 -> 10.2). This makes the fractions
        // progressively smaller, and the sum over a full window will mathematically fall
        // short of 1.0 (e.g., ~0.95). By fixing the denominator to the integer boundary,
        // we guarantee that a full window of requests sums up to exactly 1.0.
        int visibleLimit = (int) current.currentLimit();
        double probingFactor = 1.0 / visibleLimit;
        newLimit = current.currentLimit() * gradient + probingFactor;
      } else {
        // ── Reactive Failure Fallback ──
        //
        // When a call fails (timeout, exception, 5xx), the RTT value is often
        // unreliable — a timeout fires at a fixed duration regardless of actual
        // backend state, and exception handling may add overhead. Therefore, we
        // don't use the gradient for failed calls. Instead, we apply a fixed
        // multiplicative decrease of 20% (×0.8).
        //
        // This is less aggressive than AIMD's typical ×0.5 because Vegas already
        // provides proactive protection through the gradient. The failure-based
        // decrease is a safety net for cases where the latency signal fails to
        // detect congestion (e.g., the downstream service crashes instantly
        // without showing increasing latency).
        //
        // The 0.8 factor is hardcoded rather than configurable to keep the API
        // surface manageable. Vegas's primary tuning knobs are the smoothing factor
        // and baseline decay — the failure fallback is deliberately a blunt
        // instrument since it should rarely activate in a well-configured system.
        newLimit = current.currentLimit() * 0.8;
      }

      // ── Step 5: Clamp to Configured Bounds ──
      //
      // Ensure the limit stays within [minLimit, maxLimit]. The clamping is applied
      // after all arithmetic to keep the calculation logic clean and the boundary
      // enforcement in one place.
      newLimit = Math.max(minLimit, Math.min(maxLimit, newLimit));

      // Bundle the updated baseline, smoothed RTT, and limit into an immutable
      // snapshot for the atomic CAS swap.
      next = new VegasState(newNoLoad, newSmoothed, newLimit);

      // Attempt the atomic swap. If another thread modified the state between our
      // read (state.get()) and this CAS, the swap fails and we retry the entire
      // computation with the latest state — guaranteeing consistency.
    } while (!state.compareAndSet(current, next));
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Internal State Record
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Immutable snapshot of the algorithm's mutable state.
   *
   * <p>Bundling all three fields into a single record is the cornerstone of thread safety
   * in this implementation. The {@link AtomicReference} holding this record ensures that
   * every CAS transition atomically replaces all three values simultaneously. This prevents
   * the "torn read" problem that would occur with three separate atomic variables:
   *
   * <p>Without the bundled record (hypothetical broken implementation):
   * <ol>
   *   <li>Thread A reads {@code noLoadRtt = 1ms} from state X</li>
   *   <li>Thread B completes an update, writing state Y with {@code noLoadRtt = 2ms,
   *       smoothedRtt = 3ms, limit = 50}</li>
   *   <li>Thread A reads {@code smoothedRtt = 3ms} from state Y (stale noLoadRtt!)</li>
   *   <li>Thread A computes gradient = 1ms / 3ms = 0.33 — but the consistent gradient
   *       would have been 2ms / 3ms = 0.67. Thread A over-throttles.</li>
   * </ol>
   *
   * <p>With the bundled record, thread A either reads the entire state X or the entire
   * state Y — never a mix of both.
   *
   * @param noLoadRttNanos  The no-load baseline in nanoseconds. Initialized to
   *                        {@link Long#MAX_VALUE} ("unknown") and converges toward
   *                        the true minimum observed RTT. With baseline decay enabled,
   *                        it slowly drifts upward to prevent permanent lock-in from
   *                        artificially low outliers. Used as the numerator in the
   *                        gradient calculation.
   * @param smoothedRttNanos The EWMA-smoothed current RTT in nanoseconds. Initialized
   *                         to 0 ("no data"). The first sample sets it directly; subsequent
   *                         samples are blended via the smoothing factor. Used as the
   *                         denominator in the gradient calculation. A value of 0 indicates
   *                         that no samples have been received yet.
   * @param currentLimit     The current concurrency limit as a double to support the
   *                         fractional results of gradient-based scaling (e.g.,
   *                         {@code 100 × 0.98 + 0.5 = 98.5}). Truncated to {@code int}
   *                         only in {@link #getLimit()}.
   */
  private record VegasState(long noLoadRttNanos, long smoothedRttNanos, double currentLimit) {
  }
}
