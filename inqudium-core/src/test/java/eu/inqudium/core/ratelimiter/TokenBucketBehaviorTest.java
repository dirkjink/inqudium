package eu.inqudium.core.ratelimiter;

import eu.inqudium.core.InqClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RateLimiterBehavior (token bucket)")
class TokenBucketBehaviorTest {

    private final RateLimiterBehavior behavior = RateLimiterBehavior.defaultBehavior();

    private RateLimiterConfig configWithClock(InqClock clock) {
        return RateLimiterConfig.builder()
                .limitForPeriod(5)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .bucketSize(5)
                .clock(clock)
                .build();
    }

    @Nested
    @DisplayName("Permit acquisition")
    class PermitAcquisition {

        @Test
        void should_permit_when_tokens_available() {
            // Given
            var time = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
            var config = configWithClock(time::get);
            var state = TokenBucketState.initial(config);

            // When
            var result = behavior.tryAcquire(state, config);

            // Then
            assertThat(result.permitted()).isTrue();
            assertThat(result.updatedState().availableTokens()).isEqualTo(4);
        }

        @Test
        void should_deny_when_bucket_is_empty() {
            // Given
            var time = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
            var config = configWithClock(time::get);
            var state = new TokenBucketState(0, time.get());

            // When
            var result = behavior.tryAcquire(state, config);

            // Then
            assertThat(result.permitted()).isFalse();
            assertThat(result.waitDuration()).isPositive();
        }

        @Test
        void should_drain_bucket_completely_after_consuming_all_tokens() {
            // Given
            var time = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
            var config = configWithClock(time::get);
            var state = TokenBucketState.initial(config); // 5 tokens

            // When — consume all 5
            for (int i = 0; i < 5; i++) {
                var result = behavior.tryAcquire(state, config);
                assertThat(result.permitted()).isTrue();
                state = result.updatedState();
            }

            // Then — 6th should be denied
            var denied = behavior.tryAcquire(state, config);
            assertThat(denied.permitted()).isFalse();
        }
    }

    @Nested
    @DisplayName("Token refill")
    class TokenRefill {

        @Test
        void should_refill_tokens_after_one_period_elapses() {
            // Given
            var time = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
            var config = configWithClock(time::get);
            var state = new TokenBucketState(0, time.get()); // empty bucket

            // When — advance time by 1 second (one period)
            time.set(Instant.parse("2026-01-01T00:00:01Z"));
            var result = behavior.tryAcquire(state, config);

            // Then — 5 tokens refilled, 1 consumed → 4 remaining
            assertThat(result.permitted()).isTrue();
            assertThat(result.updatedState().availableTokens()).isEqualTo(4);
        }

        @Test
        void should_cap_refilled_tokens_at_bucket_size() {
            // Given
            var time = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
            var config = configWithClock(time::get);
            var state = new TokenBucketState(3, time.get()); // 3 tokens in bucket of 5

            // When — advance by 2 periods → 3 + 10 = 13, capped at 5
            time.set(Instant.parse("2026-01-01T00:00:02Z"));
            var result = behavior.tryAcquire(state, config);

            // Then — capped at 5, 1 consumed → 4
            assertThat(result.permitted()).isTrue();
            assertThat(result.updatedState().availableTokens()).isEqualTo(4);
        }
    }
}
