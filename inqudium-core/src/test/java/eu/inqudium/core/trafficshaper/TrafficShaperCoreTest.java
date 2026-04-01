package eu.inqudium.core.trafficshaper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TrafficShaperCore — Functional Leaky Bucket Scheduler")
class TrafficShaperCoreTest {

  private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");

  private static TrafficShaperConfig defaultConfig() {
    return TrafficShaperConfig.builder("test")
        .ratePerSecond(10) // 10 req/s → 100ms interval
        .maxQueueDepth(5)
        .maxWaitDuration(Duration.ofSeconds(2))
        .build();
  }

  // ================================================================
  // Initial State
  // ================================================================

  @Nested
  @DisplayName("Initial State")
  class InitialState {

    @Test
    @DisplayName("a freshly created snapshot should have zero queue depth and zero counters and epoch zero")
    void a_freshly_created_snapshot_should_have_zero_queue_depth_and_zero_counters_and_epoch_zero() {
      // Given / When
      ThrottleSnapshot snapshot = ThrottleSnapshot.initial(NOW);

      // Then
      assertThat(snapshot.queueDepth()).isZero();
      assertThat(snapshot.totalAdmitted()).isZero();
      assertThat(snapshot.totalRejected()).isZero();
      assertThat(snapshot.nextFreeSlot()).isEqualTo(NOW);
      assertThat(snapshot.epoch()).isZero();
    }
  }

  // ================================================================
  // Immediate Admission
  // ================================================================

  @Nested
  @DisplayName("Immediate Admission")
  class ImmediateAdmission {

    @Test
    @DisplayName("should admit the first request immediately with zero wait")
    void should_admit_the_first_request_immediately_with_zero_wait() {
      // Given
      ThrottleSnapshot snapshot = ThrottleSnapshot.initial(NOW);
      TrafficShaperConfig config = defaultConfig();

      // When
      ThrottlePermission permission = TrafficShaperCore.schedule(snapshot, config, NOW);

      // Then
      assertThat(permission.admitted()).isTrue();
      assertThat(permission.waitDuration()).isEqualTo(Duration.ZERO);
      assertThat(permission.requiresWait()).isFalse();
      assertThat(permission.scheduledSlot()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("should advance the next free slot by the configured interval after admission")
    void should_advance_the_next_free_slot_by_the_configured_interval_after_admission() {
      // Given
      ThrottleSnapshot snapshot = ThrottleSnapshot.initial(NOW);
      TrafficShaperConfig config = defaultConfig(); // 100ms interval

      // When
      ThrottlePermission permission = TrafficShaperCore.schedule(snapshot, config, NOW);

      // Then
      assertThat(permission.snapshot().nextFreeSlot()).isEqualTo(NOW.plusMillis(100));
    }

    @Test
    @DisplayName("should admit immediately when a request arrives after the next free slot")
    void should_admit_immediately_when_a_request_arrives_after_the_next_free_slot() {
      // Given — next free slot is at NOW, request arrives 500ms later
      ThrottleSnapshot snapshot = ThrottleSnapshot.initial(NOW);
      TrafficShaperConfig config = defaultConfig();
      Instant later = NOW.plusMillis(500);

      // When
      ThrottlePermission permission = TrafficShaperCore.schedule(snapshot, config, later);

      // Then — immediate, no credit accumulation
      assertThat(permission.admitted()).isTrue();
      assertThat(permission.waitDuration()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("should increment the total admitted counter on admission")
    void should_increment_the_total_admitted_counter_on_admission() {
      // Given
      ThrottleSnapshot snapshot = ThrottleSnapshot.initial(NOW);
      TrafficShaperConfig config = defaultConfig();

      // When
      ThrottlePermission first = TrafficShaperCore.schedule(snapshot, config, NOW);

      // Then
      assertThat(first.snapshot().totalAdmitted()).isEqualTo(1);
    }
  }

  // ================================================================
  // Delayed Admission (Traffic Shaping)
  // ================================================================

  @Nested
  @DisplayName("Delayed Admission — Traffic Shaping")
  class DelayedAdmission {

    @Test
    @DisplayName("should delay the second request by the interval when arriving simultaneously")
    void should_delay_the_second_request_by_the_interval_when_arriving_simultaneously() {
      // Given — first request admitted at NOW
      ThrottleSnapshot snapshot = ThrottleSnapshot.initial(NOW);
      TrafficShaperConfig config = defaultConfig(); // 100ms interval
      ThrottlePermission first = TrafficShaperCore.schedule(snapshot, config, NOW);

      // When — second request arrives at the same instant
      ThrottlePermission second = TrafficShaperCore.schedule(first.snapshot(), config, NOW);

      // Then — must wait 100ms for its slot
      assertThat(second.admitted()).isTrue();
      assertThat(second.waitDuration()).isEqualTo(Duration.ofMillis(100));
      assertThat(second.requiresWait()).isTrue();
      assertThat(second.scheduledSlot()).isEqualTo(NOW.plusMillis(100));
    }

    @Test
    @DisplayName("should space a burst of requests evenly across time slots")
    void should_space_a_burst_of_requests_evenly_across_time_slots() {
      // Given — 5 requests arriving simultaneously
      ThrottleSnapshot snapshot = ThrottleSnapshot.initial(NOW);
      TrafficShaperConfig config = defaultConfig(); // 100ms interval

      // When
      ThrottlePermission current = null;
      ThrottleSnapshot currentSnapshot = snapshot;
      Duration[] waits = new Duration[5];
      for (int i = 0; i < 5; i++) {
        current = TrafficShaperCore.schedule(currentSnapshot, config, NOW);
        waits[i] = current.waitDuration();
        currentSnapshot = current.snapshot();
      }

      // Then — evenly spaced: 0ms, 100ms, 200ms, 300ms, 400ms
      assertThat(waits[0]).isEqualTo(Duration.ZERO);
      assertThat(waits[1]).isEqualTo(Duration.ofMillis(100));
      assertThat(waits[2]).isEqualTo(Duration.ofMillis(200));
      assertThat(waits[3]).isEqualTo(Duration.ofMillis(300));
      assertThat(waits[4]).isEqualTo(Duration.ofMillis(400));
    }

    @Test
    @DisplayName("should compute correct wait when the request arrives between scheduled slots")
    void should_compute_correct_wait_when_the_request_arrives_between_scheduled_slots() {
      // Given — first request at NOW, next free slot at NOW+100ms
      ThrottleSnapshot snapshot = ThrottleSnapshot.initial(NOW);
      TrafficShaperConfig config = defaultConfig();
      ThrottlePermission first = TrafficShaperCore.schedule(snapshot, config, NOW);

      // When — second request arrives at NOW+30ms (before the next slot)
      Instant between = NOW.plusMillis(30);
      ThrottlePermission second = TrafficShaperCore.schedule(first.snapshot(), config, between);

      // Then — wait = 100ms - 30ms = 70ms
      assertThat(second.admitted()).isTrue();
      assertThat(second.waitDuration()).isEqualTo(Duration.ofMillis(70));
    }
  }

  // ================================================================
  // Queue Depth Tracking
  // ================================================================

  @Nested
  @DisplayName("Queue Depth Tracking")
  class QueueDepthTracking {

    @Test
    @DisplayName("should increment queue depth only for delayed requests")
    void should_increment_queue_depth_only_for_delayed_requests() {
      // Given
      ThrottleSnapshot snapshot = ThrottleSnapshot.initial(NOW);
      TrafficShaperConfig config = defaultConfig();

      // When — schedule 3 requests at the same instant
      ThrottlePermission p1 = TrafficShaperCore.schedule(snapshot, config, NOW);
      ThrottlePermission p2 = TrafficShaperCore.schedule(p1.snapshot(), config, NOW);
      ThrottlePermission p3 = TrafficShaperCore.schedule(p2.snapshot(), config, NOW);

      // Then — first is immediate (no queue), second and third are delayed
      assertThat(p1.requiresWait()).isFalse();
      assertThat(p2.requiresWait()).isTrue();
      assertThat(p3.requiresWait()).isTrue();
      assertThat(p3.snapshot().queueDepth()).isEqualTo(2);
      assertThat(p3.snapshot().totalAdmitted()).isEqualTo(3);
    }

    @Test
    @DisplayName("should decrement queue depth when a request starts executing")
    void should_decrement_queue_depth_when_a_request_starts_executing() {
      // Given — 3 requests scheduled (1 immediate + 2 delayed = queueDepth 2)
      ThrottleSnapshot snapshot = ThrottleSnapshot.initial(NOW);
      TrafficShaperConfig config = defaultConfig();
      ThrottlePermission p1 = TrafficShaperCore.schedule(snapshot, config, NOW);
      ThrottlePermission p2 = TrafficShaperCore.schedule(p1.snapshot(), config, NOW);
      ThrottlePermission p3 = TrafficShaperCore.schedule(p2.snapshot(), config, NOW);
      assertThat(p3.snapshot().queueDepth()).isEqualTo(2);

      // When — one delayed request starts executing
      ThrottleSnapshot afterExec = TrafficShaperCore.recordExecution(p3.snapshot());

      // Then
      assertThat(afterExec.queueDepth()).isEqualTo(1);
    }

    @Test
    @DisplayName("should not let queue depth go below zero")
    void should_not_let_queue_depth_go_below_zero() {
      // Given
      ThrottleSnapshot snapshot = ThrottleSnapshot.initial(NOW);

      // When
      ThrottleSnapshot dequeued = TrafficShaperCore.recordExecution(snapshot);

      // Then
      assertThat(dequeued.queueDepth()).isZero();
    }
  }

  // ================================================================
  // Rejection — Queue Depth Overflow
  // ================================================================

  @Nested
  @DisplayName("Rejection — Queue Depth Overflow")
  class QueueDepthOverflow {

    @Test
    @DisplayName("should reject when the queue depth exceeds the configured maximum")
    void should_reject_when_the_queue_depth_exceeds_the_configured_maximum() {
      // Given — maxQueueDepth=5; first request is immediate (no queue), so 6 to fill
      TrafficShaperConfig config = defaultConfig();
      ThrottleSnapshot current = ThrottleSnapshot.initial(NOW);
      for (int i = 0; i < 6; i++) {
        ThrottlePermission perm = TrafficShaperCore.schedule(current, config, NOW);
        assertThat(perm.admitted()).isTrue();
        current = perm.snapshot();
      }
      assertThat(current.queueDepth()).isEqualTo(5); // 1 immediate + 5 delayed

      // When — 7th request exceeds maxQueueDepth
      ThrottlePermission overflow = TrafficShaperCore.schedule(current, config, NOW);

      // Then
      assertThat(overflow.admitted()).isFalse();
    }

    @Test
    @DisplayName("should increment the total rejected counter on rejection")
    void should_increment_the_total_rejected_counter_on_rejection() {
      // Given — fill the queue (first request is immediate, so we need 6 to reach maxQueueDepth=5)
      TrafficShaperConfig config = defaultConfig();
      ThrottleSnapshot current = ThrottleSnapshot.initial(NOW);
      for (int i = 0; i < 6; i++) {
        current = TrafficShaperCore.schedule(current, config, NOW).snapshot();
      }
      assertThat(current.queueDepth()).isEqualTo(5); // 1 immediate + 5 delayed

      // When — 7th request triggers rejection
      ThrottlePermission rejected = TrafficShaperCore.schedule(current, config, NOW);

      // Then
      assertThat(rejected.admitted()).isFalse();
      assertThat(rejected.snapshot().totalRejected()).isEqualTo(1);
    }

    @Test
    @DisplayName("should not advance the scheduling timeline on rejection")
    void should_not_advance_the_scheduling_timeline_on_rejection() {
      // Given — fill the queue (1 immediate + 5 delayed = queueDepth 5)
      TrafficShaperConfig config = defaultConfig();
      ThrottleSnapshot current = ThrottleSnapshot.initial(NOW);
      for (int i = 0; i < 6; i++) {
        current = TrafficShaperCore.schedule(current, config, NOW).snapshot();
      }
      assertThat(current.queueDepth()).isEqualTo(5);
      Instant slotBefore = current.nextFreeSlot();

      // When — 7th request is rejected
      ThrottlePermission rejected = TrafficShaperCore.schedule(current, config, NOW);

      // Then — slot unchanged, rejection does not advance the timeline
      assertThat(rejected.admitted()).isFalse();
      assertThat(rejected.snapshot().nextFreeSlot()).isEqualTo(slotBefore);
    }
  }

  // ================================================================
  // Rejection — Max Wait Duration
  // ================================================================

  @Nested
  @DisplayName("Rejection — Max Wait Duration Exceeded")
  class MaxWaitDurationExceeded {

    @Test
    @DisplayName("should reject when the computed wait exceeds the max wait duration")
    void should_reject_when_the_computed_wait_exceeds_the_max_wait_duration() {
      // Given — 1 req/s (1s interval), maxWait=2s, maxQueueDepth=100
      TrafficShaperConfig config = TrafficShaperConfig.builder("wait-limit")
          .ratePerSecond(1)
          .maxQueueDepth(100)
          .maxWaitDuration(Duration.ofSeconds(2))
          .build();
      ThrottleSnapshot current = ThrottleSnapshot.initial(NOW);

      // Schedule 3 requests: waits are 0s, 1s, 2s (all admitted)
      for (int i = 0; i < 3; i++) {
        ThrottlePermission perm = TrafficShaperCore.schedule(current, config, NOW);
        assertThat(perm.admitted()).isTrue();
        current = perm.snapshot();
      }

      // When — 4th request would wait 3s (> maxWait of 2s)
      ThrottlePermission overflow = TrafficShaperCore.schedule(current, config, NOW);

      // Then
      assertThat(overflow.admitted()).isFalse();
    }

    @Test
    @DisplayName("should not enforce max wait duration limit when set to zero")
    void should_not_enforce_max_wait_duration_limit_when_set_to_zero() {
      // Given — maxWaitDuration=ZERO means "no limit", maxQueueDepth=-1 (unlimited)
      TrafficShaperConfig config = TrafficShaperConfig.builder("no-wait-limit")
          .ratePerSecond(1) // 1 req/s → 1s interval
          .maxQueueDepth(-1)
          .maxWaitDuration(Duration.ZERO) // no limit on wait time
          .build();
      ThrottleSnapshot current = ThrottleSnapshot.initial(NOW);

      // When — schedule 10 requests; waits up to 9s (would exceed any non-zero limit)
      for (int i = 0; i < 10; i++) {
        ThrottlePermission perm = TrafficShaperCore.schedule(current, config, NOW);
        assertThat(perm.admitted()).isTrue();
        current = perm.snapshot();
      }

      // Then — all admitted because ZERO means no wait limit
      assertThat(current.totalAdmitted()).isEqualTo(10);
      assertThat(current.totalRejected()).isZero();
      assertThat(config.hasMaxWaitDurationLimit()).isFalse();
    }
  }

  // ================================================================
  // Unbounded Mode
  // ================================================================

  @Nested
  @DisplayName("Unbounded Mode (SHAPE_UNBOUNDED)")
  class UnboundedMode {

    @Test
    @DisplayName("should never reject in unbounded mode regardless of queue depth")
    void should_never_reject_in_unbounded_mode_regardless_of_queue_depth() {
      // Given — maxQueueDepth=2 (would normally reject at 3)
      TrafficShaperConfig config = TrafficShaperConfig.builder("unbounded")
          .ratePerSecond(10)
          .maxQueueDepth(2)
          .throttleMode(ThrottleMode.SHAPE_UNBOUNDED)
          .build();
      ThrottleSnapshot current = ThrottleSnapshot.initial(NOW);

      // When — schedule 10 requests (far exceeding maxQueueDepth)
      for (int i = 0; i < 10; i++) {
        ThrottlePermission perm = TrafficShaperCore.schedule(current, config, NOW);
        assertThat(perm.admitted()).isTrue();
        current = perm.snapshot();
      }

      // Then — all admitted; first request was immediate (no queue), 9 delayed
      assertThat(current.queueDepth()).isEqualTo(9);
      assertThat(current.totalAdmitted()).isEqualTo(10);
      assertThat(current.totalRejected()).isZero();
    }
  }

  // ================================================================
  // Slot Reclamation (Anti-Burst-Credit)
  // ================================================================

  @Nested
  @DisplayName("Slot Reclamation — Anti-Burst-Credit")
  class SlotReclamation {

    @Test
    @DisplayName("should not accumulate burst credit after an idle period")
    void should_not_accumulate_burst_credit_after_an_idle_period() {
      // Given — shaper idle for 10 seconds
      ThrottleSnapshot snapshot = ThrottleSnapshot.initial(NOW);
      TrafficShaperConfig config = defaultConfig(); // 10 req/s, 100ms interval
      Instant afterIdle = NOW.plusSeconds(10);

      // When — 3 requests arrive simultaneously after the idle period
      ThrottlePermission p1 = TrafficShaperCore.schedule(snapshot, config, afterIdle);
      ThrottlePermission p2 = TrafficShaperCore.schedule(p1.snapshot(), config, afterIdle);
      ThrottlePermission p3 = TrafficShaperCore.schedule(p2.snapshot(), config, afterIdle);

      // Then — still evenly spaced, no credit burst
      assertThat(p1.waitDuration()).isEqualTo(Duration.ZERO);
      assertThat(p2.waitDuration()).isEqualTo(Duration.ofMillis(100));
      assertThat(p3.waitDuration()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("should clamp stale slot to preserve queue obligations when requests are still queued")
    void should_clamp_stale_slot_to_preserve_queue_obligations_when_requests_are_still_queued() {
      // Given — schedule two requests: first is immediate, second is delayed (enters queue)
      ThrottleSnapshot snapshot = ThrottleSnapshot.initial(NOW);
      TrafficShaperConfig config = defaultConfig(); // 100ms interval
      ThrottlePermission p1 = TrafficShaperCore.schedule(snapshot, config, NOW);
      ThrottlePermission p2 = TrafficShaperCore.schedule(p1.snapshot(), config, NOW);

      assertThat(p2.snapshot().queueDepth()).isEqualTo(1);
      // nextFreeSlot is NOW + 200ms (two intervals advanced)

      // When — 5 seconds pass but the queued request hasn't dequeued yet
      Instant fiveSecondsLater = NOW.plusSeconds(5);
      ThrottleSnapshot reclaimed = TrafficShaperCore.reclaimSlot(
          p2.snapshot(), config, fiveSecondsLater);

      // Then — slot is clamped to now - (queueDepth * interval) to prevent burst credit
      // while still honouring the one queued request's timing
      Instant expectedSlot = fiveSecondsLater.minus(config.interval().multipliedBy(1));
      assertThat(reclaimed.nextFreeSlot()).isEqualTo(expectedSlot);
      // Queue depth is unchanged — the queued request is still waiting
      assertThat(reclaimed.queueDepth()).isEqualTo(1);
    }

    @Test
    @DisplayName("should not clamp when the slot has not drifted far enough behind")
    void should_not_clamp_when_the_slot_has_not_drifted_far_enough_behind() {
      // Given — two requests: immediate + delayed, nextFreeSlot = NOW + 200ms
      ThrottleSnapshot snapshot = ThrottleSnapshot.initial(NOW);
      TrafficShaperConfig config = defaultConfig(); // 100ms interval
      ThrottlePermission p1 = TrafficShaperCore.schedule(snapshot, config, NOW);
      ThrottlePermission p2 = TrafficShaperCore.schedule(p1.snapshot(), config, NOW);

      // When — only 150ms pass (nextFreeSlot NOW+200ms is still in the future from NOW+150ms perspective)
      Instant shortlyAfter = NOW.plusMillis(150);
      ThrottleSnapshot reclaimed = TrafficShaperCore.reclaimSlot(
          p2.snapshot(), config, shortlyAfter);

      // Then — slot is still in the future, no reclamation needed
      assertThat(reclaimed.nextFreeSlot()).isEqualTo(p2.snapshot().nextFreeSlot());
    }
  }

  // ================================================================
  // Steady-State Throughput
  // ================================================================

  @Nested
  @DisplayName("Steady-State Throughput")
  class SteadyStateThroughput {

    @Test
    @DisplayName("should process exactly the configured rate over a sustained period")
    void should_process_exactly_the_configured_rate_over_a_sustained_period() {
      // Given — 5 req/s (200ms interval), simulate 2 seconds
      TrafficShaperConfig config = TrafficShaperConfig.builder("steady")
          .ratePerSecond(5)
          .maxQueueDepth(100)
          .maxWaitDuration(Duration.ofSeconds(30))
          .build();
      ThrottleSnapshot current = ThrottleSnapshot.initial(NOW);

      // When — one request every 200ms for 2 seconds (10 requests)
      int admitted = 0;
      for (int i = 0; i < 10; i++) {
        Instant arrivalTime = NOW.plusMillis(i * 200L);
        ThrottlePermission perm = TrafficShaperCore.schedule(current, config, arrivalTime);
        if (perm.admitted()) {
          admitted++;
          // Simulate execution: dequeue immediately
          current = TrafficShaperCore.recordExecution(perm.snapshot());
        } else {
          current = perm.snapshot();
        }
      }

      // Then — all 10 requests should be admitted (arrival rate == service rate)
      assertThat(admitted).isEqualTo(10);
    }
  }

  // ================================================================
  // Estimate Wait
  // ================================================================

  @Nested
  @DisplayName("Wait Time Estimation")
  class WaitTimeEstimation {

    @Test
    @DisplayName("should estimate zero wait when the slot is in the past")
    void should_estimate_zero_wait_when_the_slot_is_in_the_past() {
      // Given
      ThrottleSnapshot snapshot = ThrottleSnapshot.initial(NOW);
      TrafficShaperConfig config = defaultConfig();

      // When
      Duration wait = TrafficShaperCore.estimateWait(snapshot, config, NOW.plusSeconds(5));

      // Then
      assertThat(wait).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("should estimate the correct wait when requests are queued")
    void should_estimate_the_correct_wait_when_requests_are_queued() {
      // Given — two requests scheduled
      TrafficShaperConfig config = defaultConfig(); // 100ms interval
      ThrottleSnapshot current = ThrottleSnapshot.initial(NOW);
      ThrottlePermission p1 = TrafficShaperCore.schedule(current, config, NOW);
      ThrottlePermission p2 = TrafficShaperCore.schedule(p1.snapshot(), config, NOW);

      // When — estimate at NOW (next free slot is at NOW+200ms)
      Duration wait = TrafficShaperCore.estimateWait(p2.snapshot(), config, NOW);

      // Then
      assertThat(wait).isEqualTo(Duration.ofMillis(200));
    }
  }

  // ================================================================
  // Snapshot Immutability
  // ================================================================

  @Nested
  @DisplayName("Snapshot Immutability")
  class SnapshotImmutability {

    @Test
    @DisplayName("should not modify the original snapshot when scheduling a request")
    void should_not_modify_the_original_snapshot_when_scheduling_a_request() {
      // Given
      ThrottleSnapshot original = ThrottleSnapshot.initial(NOW);
      TrafficShaperConfig config = defaultConfig();

      // When
      TrafficShaperCore.schedule(original, config, NOW);

      // Then
      assertThat(original.queueDepth()).isZero();
      assertThat(original.totalAdmitted()).isZero();
      assertThat(original.nextFreeSlot()).isEqualTo(NOW);
      assertThat(original.epoch()).isZero();
    }
  }

  // ================================================================
  // Configuration Validation
  // ================================================================

  @Nested
  @DisplayName("Configuration Validation")
  class ConfigurationValidation {

    @Test
    @DisplayName("should reject a rate of zero")
    void should_reject_a_rate_of_zero() {
      assertThatThrownBy(() -> TrafficShaperConfig.builder("bad")
          .ratePerSecond(0)
          .build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject a negative rate")
    void should_reject_a_negative_rate() {
      assertThatThrownBy(() -> TrafficShaperConfig.builder("bad")
          .ratePerSecond(-5)
          .build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject a max queue depth below minus one")
    void should_reject_a_max_queue_depth_below_minus_one() {
      assertThatThrownBy(() -> TrafficShaperConfig.builder("bad")
          .maxQueueDepth(-2)
          .build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should accept minus one as unlimited queue depth")
    void should_accept_minus_one_as_unlimited_queue_depth() {
      // Given / When
      TrafficShaperConfig config = TrafficShaperConfig.builder("unlimited")
          .maxQueueDepth(-1)
          .build();

      // Then
      assertThat(config.maxQueueDepth()).isEqualTo(-1);
      assertThat(config.isQueuingAllowed()).isTrue();
      assertThat(config.hasQueueDepthLimit()).isFalse();
    }

    @Test
    @DisplayName("should treat zero queue depth as no queuing allowed")
    void should_treat_zero_queue_depth_as_no_queuing_allowed() {
      // Given / When
      TrafficShaperConfig config = TrafficShaperConfig.builder("no-queue")
          .maxQueueDepth(0)
          .build();

      // Then
      assertThat(config.maxQueueDepth()).isZero();
      assertThat(config.isQueuingAllowed()).isFalse();
      assertThat(config.hasQueueDepthLimit()).isFalse();
    }

    @Test
    @DisplayName("should compute the correct interval from a rate per second")
    void should_compute_the_correct_interval_from_a_rate_per_second() {
      // Given
      TrafficShaperConfig config = TrafficShaperConfig.builder("interval-check")
          .ratePerSecond(4) // 4 req/s → 250ms interval
          .build();

      // Then
      assertThat(config.interval()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    @DisplayName("should compute the correct rate from a count and period")
    void should_compute_the_correct_rate_from_a_count_and_period() {
      // Given
      TrafficShaperConfig config = TrafficShaperConfig.builder("period-check")
          .rateForPeriod(60, Duration.ofMinutes(1)) // 60 per min = 1/s
          .build();

      // Then
      assertThat(config.ratePerSecond()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
      assertThat(config.interval()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("should reject a null name")
    void should_reject_a_null_name() {
      assertThatThrownBy(() -> TrafficShaperConfig.builder(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should clamp extremely high rates to the minimum interval of one microsecond")
    void should_clamp_extremely_high_rates_to_the_minimum_interval_of_one_microsecond() {
      // Given — 10 billion req/s would produce a sub-nanosecond interval
      TrafficShaperConfig config = TrafficShaperConfig.builder("extreme")
          .ratePerSecond(10_000_000_000.0)
          .build();

      // Then — clamped to 1µs minimum (practical max ~1 million req/s)
      assertThat(config.interval().toNanos()).isEqualTo(1_000);
    }
  }

  // ================================================================
  // Reset
  // ================================================================

  @Nested
  @DisplayName("Reset")
  class ResetTests {

    @Test
    @DisplayName("should reset to a fresh state with incremented epoch and preserved counters")
    void should_reset_to_a_fresh_state_with_incremented_epoch_and_preserved_counters() {
      // Given — some requests have been processed
      TrafficShaperConfig config = defaultConfig();
      ThrottleSnapshot current = ThrottleSnapshot.initial(NOW);
      for (int i = 0; i < 3; i++) {
        current = TrafficShaperCore.schedule(current, config, NOW).snapshot();
      }
      assertThat(current.totalAdmitted()).isEqualTo(3);
      assertThat(current.epoch()).isZero();

      // When
      Instant later = NOW.plusSeconds(100);
      ThrottleSnapshot reset = TrafficShaperCore.reset(current, later);

      // Then — queue cleared, epoch incremented, counters preserved for monitoring
      assertThat(reset.queueDepth()).isZero();
      assertThat(reset.nextFreeSlot()).isEqualTo(later);
      assertThat(reset.epoch()).isEqualTo(1L);
      assertThat(reset.totalAdmitted()).isEqualTo(3); // preserved
    }

    @Test
    @DisplayName("should increment epoch on each successive reset")
    void should_increment_epoch_on_each_successive_reset() {
      // Given
      ThrottleSnapshot current = ThrottleSnapshot.initial(NOW);

      // When
      current = TrafficShaperCore.reset(current, NOW.plusSeconds(1));
      current = TrafficShaperCore.reset(current, NOW.plusSeconds(2));
      current = TrafficShaperCore.reset(current, NOW.plusSeconds(3));

      // Then
      assertThat(current.epoch()).isEqualTo(3L);
    }
  }
}
