package eu.inqudium.ratelimiter;

import eu.inqudium.core.InqClock;
import eu.inqudium.core.event.InqEvent;
import eu.inqudium.core.ratelimiter.InqRequestNotPermittedException;
import eu.inqudium.core.ratelimiter.RateLimiterConfig;
import eu.inqudium.ratelimiter.event.RateLimiterOnPermitEvent;
import eu.inqudium.ratelimiter.event.RateLimiterOnRejectEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RateLimiter")
class RateLimiterTest {

    private RateLimiterConfig configWithClock(InqClock clock, int permits) {
        return RateLimiterConfig.builder()
                .limitForPeriod(permits)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .bucketSize(permits)
                .timeoutDuration(Duration.ZERO)
                .clock(clock)
                .build();
    }

    @Nested
    @DisplayName("Permit acquisition")
    class PermitAcquisition {

        @Test
        void should_permit_calls_when_tokens_available() {
            // Given
            var time = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
            var config = configWithClock(time::get, 5);
            var rl = RateLimiter.of("test", config);

            // When / Then — should not throw
            var result = rl.executeSupplier(() -> "ok");
            assertThat(result).isEqualTo("ok");
        }

        @Test
        void should_reject_when_all_tokens_consumed() {
            // Given
            var time = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
            var config = configWithClock(time::get, 3);
            var rl = RateLimiter.of("test", config);

            // When — consume all 3 tokens
            rl.executeSupplier(() -> "1");
            rl.executeSupplier(() -> "2");
            rl.executeSupplier(() -> "3");

            // Then — 4th should be rejected
            assertThatThrownBy(() -> rl.executeSupplier(() -> "4"))
                    .isInstanceOf(InqRequestNotPermittedException.class)
                    .satisfies(ex -> {
                        var ire = (InqRequestNotPermittedException) ex;
                        assertThat(ire.getCode()).isEqualTo("INQ-RL-001");
                        assertThat(ire.getElementName()).isEqualTo("test");
                    });
        }
    }

    @Nested
    @DisplayName("Token refill")
    class TokenRefill {

        @Test
        void should_permit_again_after_tokens_refill() {
            // Given
            var time = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
            var config = configWithClock(time::get, 2);
            var rl = RateLimiter.of("test", config);

            // When — consume all tokens
            rl.executeSupplier(() -> "1");
            rl.executeSupplier(() -> "2");

            // Advance time by 1 period → tokens refill
            time.set(Instant.parse("2026-01-01T00:00:01Z"));

            // Then — should be permitted again
            var result = rl.executeSupplier(() -> "refreshed");
            assertThat(result).isEqualTo("refreshed");
        }
    }

    @Nested
    @DisplayName("Event publishing")
    class EventPublishing {

        @Test
        void should_emit_permit_event_on_successful_acquisition() {
            // Given
            var time = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
            var config = configWithClock(time::get, 5);
            var rl = RateLimiter.of("test", config);
            var events = Collections.synchronizedList(new ArrayList<InqEvent>());
            rl.getEventPublisher().onEvent(events::add);

            // When
            rl.executeSupplier(() -> "ok");

            // Then
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOf(RateLimiterOnPermitEvent.class);
        }

        @Test
        void should_emit_reject_event_when_denied() {
            // Given
            var time = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
            var config = configWithClock(time::get, 1);
            var rl = RateLimiter.of("test", config);
            var events = Collections.synchronizedList(new ArrayList<InqEvent>());
            rl.getEventPublisher().onEvent(events::add);

            // When
            rl.executeSupplier(() -> "ok"); // consumes the one token
            try { rl.executeSupplier(() -> "denied"); } catch (InqRequestNotPermittedException ignored) {}

            // Then
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).isInstanceOf(RateLimiterOnPermitEvent.class);
            assertThat(events.get(1)).isInstanceOf(RateLimiterOnRejectEvent.class);
        }
    }

    @Nested
    @DisplayName("Registry")
    class RegistryTests {

        @Test
        void should_return_same_instance_for_same_name() {
            // Given
            var registry = new RateLimiterRegistry();

            // When / Then
            assertThat(registry.get("api")).isSameAs(registry.get("api"));
        }
    }
}
