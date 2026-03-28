package eu.inqudium.core.timelimiter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InqTimeoutProfile")
class InqTimeoutProfileTest {

    @Nested
    @DisplayName("RSS calculation")
    class RssCalculation {

        @Test
        void should_produce_tighter_timeout_than_worst_case() {
            // Given
            var rss = InqTimeoutProfile.builder()
                    .connectTimeout(Duration.ofMillis(250))
                    .responseTimeout(Duration.ofSeconds(3))
                    .method(TimeoutCalculation.RSS)
                    .safetyMarginFactor(1.0) // no margin for clean comparison
                    .build();

            var worstCase = InqTimeoutProfile.builder()
                    .connectTimeout(Duration.ofMillis(250))
                    .responseTimeout(Duration.ofSeconds(3))
                    .method(TimeoutCalculation.WORST_CASE)
                    .safetyMarginFactor(1.0)
                    .build();

            // When
            var rssTimeout = rss.timeLimiterTimeout();
            var wcTimeout = worstCase.timeLimiterTimeout();

            // Then — RSS should be strictly less than worst-case
            assertThat(rssTimeout).isLessThan(wcTimeout);
        }

        @Test
        void should_produce_a_positive_timeout_for_typical_values() {
            // Given
            var profile = InqTimeoutProfile.builder()
                    .connectTimeout(Duration.ofMillis(250))
                    .responseTimeout(Duration.ofSeconds(3))
                    .build(); // default: RSS, 1.2 margin

            // When
            var timeout = profile.timeLimiterTimeout();

            // Then
            assertThat(timeout).isPositive();
            assertThat(timeout.toMillis()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("Safety margin")
    class SafetyMargin {

        @Test
        void should_increase_timeout_by_the_margin_factor() {
            // Given
            var noMargin = InqTimeoutProfile.builder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .responseTimeout(Duration.ofSeconds(2))
                    .method(TimeoutCalculation.WORST_CASE)
                    .safetyMarginFactor(1.0)
                    .build();

            var withMargin = InqTimeoutProfile.builder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .responseTimeout(Duration.ofSeconds(2))
                    .method(TimeoutCalculation.WORST_CASE)
                    .safetyMarginFactor(1.5)
                    .build();

            // When
            var baseTimeout = noMargin.timeLimiterTimeout().toMillis();
            var marginTimeout = withMargin.timeLimiterTimeout().toMillis();

            // Then — 1.5x margin
            assertThat(marginTimeout).isCloseTo((long) (baseTimeout * 1.5), org.assertj.core.data.Offset.offset(1L));
        }

        @Test
        void should_reject_safety_margin_below_one() {
            assertThatThrownBy(() ->
                    InqTimeoutProfile.builder().safetyMarginFactor(0.8)
            ).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Slow call threshold alignment")
    class SlowCallThreshold {

        @Test
        void should_align_slow_call_threshold_with_timelimiter_timeout() {
            // Given
            var profile = InqTimeoutProfile.builder()
                    .connectTimeout(Duration.ofMillis(500))
                    .responseTimeout(Duration.ofSeconds(5))
                    .build();

            // When / Then — they should be equal (ADR-012)
            assertThat(profile.slowCallDurationThreshold())
                    .isEqualTo(profile.timeLimiterTimeout());
        }
    }

    @Nested
    @DisplayName("Accessor methods")
    class Accessors {

        @Test
        void should_return_configured_components() {
            // Given
            var profile = InqTimeoutProfile.builder()
                    .connectTimeout(Duration.ofMillis(250))
                    .responseTimeout(Duration.ofSeconds(3))
                    .method(TimeoutCalculation.RSS)
                    .safetyMarginFactor(1.3)
                    .build();

            // Then
            assertThat(profile.connectTimeout()).isEqualTo(Duration.ofMillis(250));
            assertThat(profile.responseTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(profile.getMethod()).isEqualTo(TimeoutCalculation.RSS);
            assertThat(profile.getSafetyMarginFactor()).isEqualTo(1.3);
        }
    }
}
