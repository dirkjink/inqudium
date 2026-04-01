package eu.inqudium.core.time;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CachedInqClockTest {

  @Nested
  class TimeRetrieval {

    @Test
    void should_return_exactly_the_same_instance_on_consecutive_fast_reads() {
      // Given
      try (CachedInqClock clock = new CachedInqClock(100)) {
        // When
        Instant firstRead = clock.instant();
        Instant secondRead = clock.instant();

        // Then
        assertThat(firstRead).isSameAs(secondRead);
      }
    }

    @Test
    void should_update_its_internal_time_after_the_configured_interval_passes() throws InterruptedException {
      // Given
      try (CachedInqClock clock = new CachedInqClock(10)) {
        // When
        Instant initialTime = clock.instant();
        Thread.sleep(30);
        Instant updatedTime = clock.instant();

        // Then
        assertThat(updatedTime).isAfter(initialTime);
        assertThat(updatedTime).isNotSameAs(initialTime);
      }
    }
  }

  @Nested
  class FallbackMechanism {

    @Test
    void should_fallback_to_live_time_calculation_when_the_clock_is_closed() throws InterruptedException {
      // Given
      CachedInqClock clock = new CachedInqClock(5);

      // When
      clock.close();
      // Wait briefly to ensure the virtual thread's finally block has executed
      Thread.sleep(20);

      Instant firstFallbackRead = clock.instant();
      Thread.sleep(5);
      Instant secondFallbackRead = clock.instant();

      // Then
      assertThat(firstFallbackRead).isNotSameAs(secondFallbackRead);
      assertThat(secondFallbackRead).isAfterOrEqualTo(firstFallbackRead);
    }

    @Test
    void should_fallback_to_live_time_calculation_if_the_background_thread_dies_unexpectedly() throws Exception {
      // Given
      CachedInqClock clock = new CachedInqClock(50);

      // When
      // Simulate an unexpected crash by interrupting the thread directly,
      // without calling close() or setting running to false.
      java.lang.reflect.Field threadField = CachedInqClock.class.getDeclaredField("updaterThread");
      threadField.setAccessible(true);
      Thread backgroundThread = (Thread) threadField.get(clock);

      backgroundThread.interrupt();

      // Wait for the thread to process the interrupt and execute the finally block
      backgroundThread.join(1000);

      Instant firstFallbackRead = clock.instant();
      Thread.sleep(5);
      Instant secondFallbackRead = clock.instant();

      // Then
      assertThat(firstFallbackRead).isNotSameAs(secondFallbackRead);
      assertThat(secondFallbackRead).isAfterOrEqualTo(firstFallbackRead);

      // Cleanup
      clock.close();
    }
  }

  @Nested
  class LifecycleManagement {

    @Test
    void should_permanently_stop_the_background_updater_thread_when_closed() throws Exception {
      // Given
      CachedInqClock clock = new CachedInqClock(5);

      // When
      clock.close();
      Thread.sleep(20);

      // Then
      // Using reflection to verify the thread is actually dead
      java.lang.reflect.Field threadField = CachedInqClock.class.getDeclaredField("updaterThread");
      threadField.setAccessible(true);
      Thread backgroundThread = (Thread) threadField.get(clock);

      assertThat(backgroundThread.isAlive()).isFalse();
    }
  }
}

