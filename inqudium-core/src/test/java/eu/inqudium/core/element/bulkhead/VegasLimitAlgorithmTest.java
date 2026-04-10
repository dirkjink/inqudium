package eu.inqudium.core.element.bulkhead;

import eu.inqudium.core.element.bulkhead.algo.VegasLimitAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class VegasLimitAlgorithmTest {

    @Nested
    @DisplayName("Windowed Probing and Growth")
    class WindowedProbingAndGrowth {

        @Test
        @DisplayName("Limit should grow exactly by one after a full window of successful requests without congestion.")
        void limitShouldGrowExactlyByOneAfterFullWindowOfSuccessfulRequestsWithoutCongestion() {
            // Given
            int initialLimit = 10;
            AtomicLong virtualClock = new AtomicLong(0);

            // Error threshold set to 1.0 (disables practical impact for this test)
            VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
                    initialLimit,
                    1,
                    100,
                    Duration.ofNanos(1),
                    Duration.ZERO,
                    1.0,
                    virtualClock::get
            );

            // When
            for (int i = 0; i < initialLimit; i++) {
                virtualClock.addAndGet(Duration.ofMillis(50).toNanos());
                algorithm.update(Duration.ofMillis(50).toNanos(), true, initialLimit);
            }

            // Then
            assertThat(algorithm.getLimit()).isEqualTo(11);
        }

        @Test
        @DisplayName("Limit should not grow explosively when handling high throughput.")
        void limitShouldNotGrowExplosivelyWhenHandlingHighThroughput() {
            // Given
            int initialLimit = 500;
            AtomicLong virtualClock = new AtomicLong(0);
            VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
                    initialLimit, 1, 2000, Duration.ofNanos(1), Duration.ZERO, 1.0, virtualClock::get
            );

            // When
            for (int i = 0; i < 100; i++) {
                virtualClock.addAndGet(Duration.ofMillis(20).toNanos());
                algorithm.update(Duration.ofMillis(20).toNanos(), true, initialLimit);
            }

            // Then
            assertThat(algorithm.getLimit()).isEqualTo(500);
        }
    }

    @Nested
    @DisplayName("Congestion and Gradient Reaction")
    class CongestionAndGradientReaction {

        @Test
        @DisplayName("Limit should decrease proportionally when current RTT is twice the baseline.")
        void limitShouldDecreaseProportionallyWhenCurrentRttIsTwiceTheBaseline() {
            // Given
            AtomicLong virtualClock = new AtomicLong(0);
            VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
                    100, 1, 200, Duration.ofNanos(1), Duration.ZERO, 1.0, virtualClock::get
            );

            virtualClock.addAndGet(Duration.ofMillis(10).toNanos());
            algorithm.update(Duration.ofMillis(10).toNanos(), true, 100);

            // When
            virtualClock.addAndGet(Duration.ofMillis(20).toNanos());
            algorithm.update(Duration.ofMillis(20).toNanos(), true, 100);

            // Then
            assertThat(algorithm.getLimit()).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("Failure Fallback")
    class FailureFallback {

        @Test
        @DisplayName("Algorithm should ignore a single failure when the error rate is below the threshold.")
        void algorithmShouldIgnoreSingleFailureWhenErrorRateIsBelowThreshold() {
            // Given
            AtomicLong virtualClock = new AtomicLong(0);
            // We choose a time constant that is large enough (e.g., 1 second)
            Duration smoothing = Duration.ofSeconds(1);

            VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
                    100, 1, 200, smoothing, Duration.ZERO, 0.10, virtualClock::get
            );

            // --- IMPORTANT: Warm-up ---
            // We simulate 10 successful requests to stabilize the error rate at 0.0
            for (int i = 0; i < 10; i++) {
                virtualClock.addAndGet(Duration.ofMillis(10).toNanos());
                algorithm.update(Duration.ofMillis(10).toNanos(), true, 100);
            }

            // Ensure that we start at 100 (or slightly above due to probing)
            int limitAfterWarmup = algorithm.getLimit();

            // When
            // Now a single failure occurs. Due to the smoothing and history,
            // the error rate stays below the 10% threshold.
            virtualClock.addAndGet(Duration.ofMillis(10).toNanos());
            algorithm.update(Duration.ofMillis(10).toNanos(), false, 100);

            // Then
            // The limit must not have dropped to 80
            assertThat(algorithm.getLimit()).isEqualTo(limitAfterWarmup);
        }

        @Test
        @DisplayName("Algorithm should reduce the limit when sustained failures occur.")
        void algorithmShouldReduceLimitWhenSustainedFailuresOccur() {
            // Given
            AtomicLong virtualClock = new AtomicLong(0);
            // Very low threshold to force a rapid reaction
            VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
                    100, 1, 200, Duration.ofSeconds(10), Duration.ZERO, 0.01, virtualClock::get
            );

            // When
            // We simulate a massive failure (error rate shoots up)
            for (int i = 0; i < 5; i++) {
                virtualClock.addAndGet(Duration.ofMillis(100).toNanos());
                algorithm.update(Duration.ofMillis(100).toNanos(), false, 100);
            }

            // Then
            // Now the multiplicative decrease (0.8) takes effect
            assertThat(algorithm.getLimit()).isLessThan(100);
        }
    }

    @Nested
    @DisplayName("Continuous-Time EWMA and Drift Properties")
    class ContinuousTimeEwmaProperties {

        @Test
        @DisplayName("Limit reaction should be smooth when using a moderate time constant.")
        void limitReactionShouldBeSmoothWhenUsingAModerateTimeConstant() {
            // Given
            AtomicLong virtualClock = new AtomicLong(0);
            VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
                    100, 1, 200, Duration.ofSeconds(1), Duration.ZERO, 1.0, virtualClock::get
            );

            virtualClock.addAndGet(Duration.ofMillis(10).toNanos());
            algorithm.update(Duration.ofMillis(10).toNanos(), true, 100);

            // When
            virtualClock.addAndGet(Duration.ofMillis(10).toNanos());
            algorithm.update(Duration.ofMillis(20).toNanos(), true, 100);

            // Then
            assertThat(algorithm.getLimit()).isGreaterThan(95);
        }

        @Test
        @DisplayName("Baseline should drift upward over time when drift is enabled.")
        void baselineShouldDriftUpwardOverTimeWhenDriftIsEnabled() {
            // Given
            AtomicLong virtualClock = new AtomicLong(0);
            VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
                    100, 1, 200, Duration.ofNanos(1), Duration.ofSeconds(2), 1.0, virtualClock::get
            );

            virtualClock.addAndGet(Duration.ofMillis(10).toNanos());
            algorithm.update(Duration.ofMillis(10).toNanos(), true, 100);

            // When
            virtualClock.addAndGet(Duration.ofSeconds(4).toNanos());
            algorithm.update(Duration.ofMillis(20).toNanos(), true, 100);

            // Then
            assertThat(algorithm.getLimit()).isGreaterThan(50);
        }
    }
}