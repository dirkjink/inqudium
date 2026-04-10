package eu.inqudium.core.element.trafficshaper.strategy;

import eu.inqudium.core.element.trafficshaper.ThrottlePermission;
import eu.inqudium.core.element.trafficshaper.TrafficShaperConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowStrategyTest {

    private FixedWindowStrategy strategy;
    private TrafficShaperConfig<FixedWindowState> config;
    private Instant now;

    @BeforeEach
    void setUp() {
        // Window size 1 second, max 3 requests per window
        strategy = new FixedWindowStrategy(Duration.ofSeconds(1), 3);
        config = TrafficShaperConfig.builder("fixed-window-test")
                .withStrategy(strategy)
                .build();
        now = Instant.parse("2026-04-02T10:00:00Z");
    }

    @Nested
    @DisplayName("Window Scheduling")
    class WindowSchedulingTests {

        @Test
        @DisplayName("Requests within the window limit should be admitted immediately")
        void requestsWithinLimitShouldBeImmediate() {
            // Given
            FixedWindowState state = strategy.initial(config, now);

            // When
            ThrottlePermission<FixedWindowState> permission = strategy.schedule(state, config, now);

            // Then
            assertThat(permission.admitted()).isTrue();
            assertThat(permission.requiresWait()).isFalse();
            assertThat(permission.state().requestsInWindow()).isEqualTo(1);
        }

        @Test
        @DisplayName("Requests exceeding the window limit should be delayed until the next window starts")
        void requestsExceedingLimitShouldBeDelayed() {
            // Given: Window is already full
            FixedWindowState state = new FixedWindowState(now, 3, now, 0, 3, 0, 0L);

            // When: A 4th request arrives in the middle of the window
            Instant midWindow = now.plusMillis(500);
            ThrottlePermission<FixedWindowState> permission = strategy.schedule(state, config, midWindow);

            // Then
            assertThat(permission.admitted()).isTrue();
            assertThat(permission.requiresWait()).isTrue();
            // Wait should be the remaining 500ms of the 1s window
            assertThat(permission.waitDuration()).isEqualTo(Duration.ofMillis(500));

            // The scheduled slot should be exactly at the start of the next window
            Instant nextWindowStart = now.plusSeconds(1);
            assertThat(permission.scheduledSlot()).isEqualTo(nextWindowStart);

            // The new state should reflect the new window and the delayed request
            assertThat(permission.state().windowStart()).isEqualTo(nextWindowStart);
            assertThat(permission.state().requestsInWindow()).isEqualTo(1);
            assertThat(permission.state().queueDepth()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Window Advancement")
    class WindowAdvancementTests {

        @Test
        @DisplayName("The strategy should correctly advance across multiple empty windows")
        void strategyShouldAdvanceAcrossEmptyWindows() {
            // Given
            FixedWindowState state = strategy.initial(config, now);

            // When: 2.5 seconds have passed (2 full windows skipped)
            Instant futureTime = now.plusMillis(2500);
            ThrottlePermission<FixedWindowState> permission = strategy.schedule(state, config, futureTime);

            // Then
            assertThat(permission.admitted()).isTrue();
            assertThat(permission.requiresWait()).isFalse();
            // The window start should align correctly to the 2nd second mark
            assertThat(permission.state().windowStart()).isEqualTo(now.plusSeconds(2));
        }
    }
}
