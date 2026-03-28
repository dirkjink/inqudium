package eu.inqudium.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InqClock")
class InqClockTest {

  @Nested
  @DisplayName("System clock")
  class SystemClock {

    @Test
    void should_return_a_non_null_instant() {
      // Given
      var clock = InqClock.system();

      // When
      var instant = clock.instant();

      // Then
      assertThat(instant).isNotNull();
    }

    @Test
    void should_return_an_instant_close_to_now() {
      // Given
      var clock = InqClock.system();

      // When
      var before = Instant.now();
      var instant = clock.instant();
      var after = Instant.now();

      // Then
      assertThat(instant).isBetween(before, after);
    }

    @Test
    void should_return_monotonically_increasing_instants() {
      // Given
      var clock = InqClock.system();

      // When
      var first = clock.instant();
      var second = clock.instant();

      // Then
      assertThat(second).isAfterOrEqualTo(first);
    }
  }

  @Nested
  @DisplayName("Test clock (lambda)")
  class TestClock {

    @Test
    void should_return_the_fixed_instant_provided_by_a_lambda() {
      // Given
      var fixedInstant = Instant.parse("2026-01-15T10:30:00Z");
      InqClock clock = () -> fixedInstant;

      // When
      var result = clock.instant();

      // Then
      assertThat(result).isEqualTo(fixedInstant);
    }

    @Test
    void should_advance_time_when_the_backing_reference_changes() {
      // Given
      var timeRef = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
      InqClock clock = timeRef::get;

      // When
      var t0 = clock.instant();
      timeRef.set(timeRef.get().plusSeconds(5));
      var t1 = clock.instant();
      timeRef.set(timeRef.get().plus(Duration.ofMinutes(10)));
      var t2 = clock.instant();

      // Then
      assertThat(t1).isEqualTo(t0.plusSeconds(5));
      assertThat(t2).isEqualTo(t1.plus(Duration.ofMinutes(10)));
    }

    @Test
    void should_allow_two_independent_clocks_in_the_same_jvm() {
      // Given
      var ref1 = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
      var ref2 = new AtomicReference<>(Instant.parse("2099-12-31T23:59:59Z"));
      InqClock clock1 = ref1::get;
      InqClock clock2 = ref2::get;

      // When / Then
      assertThat(clock1.instant()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
      assertThat(clock2.instant()).isEqualTo(Instant.parse("2099-12-31T23:59:59Z"));
    }
  }

  @Nested
  @DisplayName("Functional interface contract")
  class FunctionalInterfaceContract {

    @Test
    void should_be_assignable_from_a_method_reference() {
      // Given
      var ref = new AtomicReference<>(Instant.EPOCH);

      // When — method reference assignment must compile
      InqClock clock = ref::get;

      // Then
      assertThat(clock.instant()).isEqualTo(Instant.EPOCH);
    }
  }
}
