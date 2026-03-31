package eu.inqudium.core.bulkhead;

import eu.inqudium.core.algo.ContinuousTimeEwma;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

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
 * <b>time-based decay</b> that slowly drifts it upward toward the smoothed RTT.
 * Without decay, a single artificially low measurement (e.g., a cached response, a GC
 * pause that delayed the timestamp before the call but not after) would permanently poison
 * the baseline, causing the gradient to chronically overestimate congestion.
 *
 * <h3>2. Continuous-Time Smoothed Current RTT (EWMA)</h3>
 * <p>The algorithm calculates an Exponentially Weighted Moving Average of recent response
 * times using a continuous-time approach (via {@link ContinuousTimeEwma}). This filters out
 * random latency spikes (GC pauses, network jitter) and provides a stable "current conditions"
 * signal that is completely independent of the request rate (RPS).
 *
 * <h3>3. The Gradient</h3>
 * <p>The ratio {@code noLoadRtt / smoothedRtt} is the gradient — the key signal that drives
 * all limit adjustments:
 * <ul>
 * <li>{@code gradient ≈ 1.0}: The current RTT matches the baseline. No queuing detected,
 * the downstream system is operating at its physical minimum. The limit can safely
 * increase.</li>
 * <li>{@code gradient = 0.5}: The current RTT is twice the baseline. The downstream system
 * is spending roughly half its time processing and half queuing. The limit should
 * decrease.</li>
 * <li>{@code gradient > 1.0}: The current RTT is actually faster than the baseline. This
 * can happen when the baseline was initially set during a warm-up period with higher
 * latency. Capped at 1.2 to prevent runaway growth.</li>
 * </ul>
 *
 * <p>The new limit is then: {@code newLimit = currentLimit × gradient + probingFactor}
 *
 * <h2>Thread Safety</h2>
 * <p>All mutable state is bundled into a single immutable {@link VegasState} record and managed via
 * {@link AtomicReference#compareAndSet(Object, Object)}. This guarantees that every
 * {@link #update(Duration, boolean)} call reads and writes a consistent snapshot without
 * any locking or blocking.
 *
 * @see AimdLimitAlgorithm
 * @see ContinuousTimeEwma
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
   */
  private final int minLimit;

  /**
   * The absolute maximum concurrency limit. The algorithm will never increase the limit
   * above this ceiling, even if the gradient indicates zero congestion.
   */
  private final int maxLimit;

  /**
   * The stateless calculator used to compute the continuous-time EWMA of the current RTT.
   * Replaces the manual request-based smoothing factor to ensure RPS-independent decay.
   */
  private final ContinuousTimeEwma rttSmoothingEwma;

  /**
   * The stateless calculator used to compute the continuous-time drift of the baseline
   * towards the smoothed RTT. Can be null if decay is disabled.
   */
  private final ContinuousTimeEwma baselineDriftEwma;

  /**
   * The injectable nano-time source for all timing measurements related to the EWMA decay.
   */
  private final LongSupplier nanoTimeSource;

  // ──────────────────────────────────────────────────────────────────────────
  // Mutable State (managed via CAS on an immutable snapshot)
  // ──────────────────────────────────────────────────────────────────────────

  private final AtomicReference<VegasState> state;

  // ──────────────────────────────────────────────────────────────────────────
  // Constructors
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Creates a new Vegas limit algorithm with the default baseline drift.
   *
   * @param initialLimit          The starting concurrency limit before any RTT feedback is
   *                              received. Clamped to [{@code minLimit}, {@code maxLimit}].
   * @param minLimit              The absolute minimum concurrency. Clamped to at least 1.
   * @param maxLimit              The absolute maximum concurrency. Clamped to at least
   *                              {@code minLimit}.
   * @param smoothingTimeConstant The time constant (Tau) for RTT smoothing. A larger
   *                              duration means the average reacts more slowly and ignores short spikes.
   * @param nanoTimeSource        The time source used for calculating elapsed time.
   */
  public VegasLimitAlgorithm(int initialLimit,
                             int minLimit,
                             int maxLimit,
                             Duration smoothingTimeConstant,
                             LongSupplier nanoTimeSource) {
    // Uses a default slow drift (e.g., 10 seconds time constant) for the baseline
    this(initialLimit,
        minLimit,
        maxLimit,
        smoothingTimeConstant,
        Duration.ofSeconds(10),
        nanoTimeSource);
  }

  /**
   * Creates a new Vegas limit algorithm with full control over baseline decay behavior.
   *
   * <p><b>Recommended production configuration:</b>
   * <pre>{@code
   * new VegasLimitAlgorithm(
   *   50,    // initialLimit: conservative starting point
   *   5,     // minLimit: always allow probe requests
   *   200,   // maxLimit: prevent resource exhaustion
   *   Duration.ofMillis(500), // smoothingTimeConstant: filters jitter well
   *   Duration.ofSeconds(10), // baselineDriftTimeConstant: slow but safe recovery
   *   System::nanoTime        // nanoTimeSource
   * );
   * }</pre>
   *
   * @param initialLimit              The starting concurrency limit. Clamped to
   *                                  [{@code minLimit}, {@code maxLimit}].
   * @param minLimit                  The absolute minimum concurrency. Clamped to at least 1.
   * @param maxLimit                  The absolute maximum concurrency. Clamped to at least
   *                                  {@code minLimit}.
   * @param smoothingTimeConstant     The time constant (Tau) for continuous-time RTT smoothing.
   * @param baselineDriftTimeConstant The time constant (Tau) at which the no-load baseline
   *                                  drifts toward the smoothed RTT. A null or zero duration
   *                                  disables decay entirely.
   * @param nanoTimeSource            The time source used for calculating elapsed time.
   */
  public VegasLimitAlgorithm(int initialLimit,
                             int minLimit,
                             int maxLimit,
                             Duration smoothingTimeConstant,
                             Duration baselineDriftTimeConstant,
                             LongSupplier nanoTimeSource) {

    this.minLimit = Math.max(1, minLimit);
    this.maxLimit = Math.max(this.minLimit, maxLimit);

    this.rttSmoothingEwma = new ContinuousTimeEwma(smoothingTimeConstant);

    if (baselineDriftTimeConstant != null && baselineDriftTimeConstant.toNanos() > 0) {
      this.baselineDriftEwma = new ContinuousTimeEwma(baselineDriftTimeConstant);
    } else {
      this.baselineDriftEwma = null;
    }

    this.nanoTimeSource = nanoTimeSource != null ? nanoTimeSource : System::nanoTime;

    double bounded = Math.max(this.minLimit, Math.min(initialLimit, this.maxLimit));
    this.state = new AtomicReference<>(new VegasState(Long.MAX_VALUE, 0, bounded, this.nanoTimeSource.getAsLong()));
  }

  // ──────────────────────────────────────────────────────────────────────────
  // InqLimitAlgorithm Interface
  // ──────────────────────────────────────────────────────────────────────────

  @Override
  public int getLimit() {
    // Add a tiny epsilon (1e-9) to counteract IEEE 754 floating-point precision loss.
    return (int) (state.get().currentLimit() + 1e-9);
  }

  @Override
  public void update(Duration rtt, boolean isSuccess) {
    long rttNanos = rtt.toNanos();

    // Guard: ignore degenerate RTT values.
    if (rttNanos <= 0) return;

    long now = nanoTimeSource.getAsLong();

    VegasState current;
    VegasState next;
    do {
      current = state.get();

      final long newSmoothed;
      if (isSuccess) {
        // ── Step 1: Update the Smoothed Current RTT (Continuous-Time EWMA) ──
        //
        // The smoothed RTT filters out random latency noise. The calculation is delegated
        // to the stateless ContinuousTimeEwma calculator, ensuring the decay is applied
        // perfectly relative to the elapsed time (independent of RPS).
        //
        // This MUST be calculated before the baseline drift (Step 2), so the baseline
        // can drift towards the most accurate, current representation of the average RTT
        // rather than a stale value from before a potential time gap.
        if (current.smoothedRttNanos() == 0) {
          newSmoothed = rttNanos;
        } else {
          newSmoothed = (long) rttSmoothingEwma.calculate(
              (double) current.smoothedRttNanos(),
              current.lastUpdateNanos(),
              now,
              (double) rttNanos
          );
        }
      } else {
        newSmoothed = current.smoothedRttNanos();
      }

      final long newNoLoad;
      if (isSuccess) {
        // ── Step 2: Maintain the No-Load Baseline with Time-Based Drift ──
        //
        // First, take the minimum of the current baseline and the new sample.
        long candidateNoLoad = Math.min(current.noLoadRttNanos(), rttNanos);

        // If drift is enabled and we have a valid smoothed RTT, we drift the baseline
        // slowly towards the NEW smoothed RTT using the continuous-time EWMA.
        // This prevents a single artificially low outlier from permanently poisoning
        // the baseline and causing permanent throttling.
        if (baselineDriftEwma != null && newSmoothed > 0
            && candidateNoLoad < newSmoothed) {

          long decayed = (long) baselineDriftEwma.calculate(
              (double) candidateNoLoad,
              current.lastUpdateNanos(),
              now,
              (double) newSmoothed
          );

          newNoLoad = Math.min(decayed, newSmoothed);
        } else {
          newNoLoad = candidateNoLoad;
        }
      } else {
        newNoLoad = current.noLoadRttNanos();
      }

      double newLimit;

      if (isSuccess) {
        // ── Step 3: Compute the Gradient ──
        //
        // The gradient quantifies how much the downstream system is slowing down
        // relative to its best-case performance (baseline / smoothed).
        double gradient = (newSmoothed > 0) ? (double) newNoLoad / (double) newSmoothed : 1.0;
        gradient = Math.max(0.5, Math.min(1.2, gradient));

        // ── Step 4: Calculate the New Concurrency Limit ──
        // ── Proactive Gradient-Based Adjustment ──
        int visibleLimit = (int) (current.currentLimit() + 1e-9);
        double probingFactor = 1.0 / visibleLimit;
        newLimit = current.currentLimit() * gradient + probingFactor;
      } else {
        // ── Reactive Failure Fallback ──
        //
        // When a call fails (timeout, exception, 5xx), the RTT value is often
        // unreliable. We apply a fixed multiplicative decrease of 20% (×0.8).
        newLimit = current.currentLimit() * 0.8;
      }

      // ── Step 5: Clamp to Configured Bounds ──
      newLimit = Math.max(minLimit, Math.min(maxLimit, newLimit));

      // Bundle the updated state and the 'now' timestamp into an immutable snapshot.
      next = new VegasState(newNoLoad, newSmoothed, newLimit, now);

    } while (!state.compareAndSet(current, next));
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Internal State Record
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Immutable snapshot of the algorithm's mutable state.
   *
   * @param noLoadRttNanos   The no-load baseline in nanoseconds.
   * @param smoothedRttNanos The continuous-time EWMA-smoothed current RTT in nanoseconds.
   *                         A value of 0 indicates that no samples have been received yet.
   * @param currentLimit     The current concurrency limit as a double to support the
   *                         fractional results of gradient-based scaling.
   * @param lastUpdateNanos  The timestamp of the last state update, used by the
   *                         {@link ContinuousTimeEwma} calculators.
   */
  private record VegasState(long noLoadRttNanos, long smoothedRttNanos, double currentLimit, long lastUpdateNanos) {
  }
}