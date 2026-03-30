package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.bulkhead.BulkheadStateMachine;
import eu.inqudium.core.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.event.InqEventPublisher;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class ImperativeBulkheadStrategyTest {

  final LongSupplier nanoTimeSource = System::nanoTime;

  // A fake state machine acting as a test spy
  private static class FakeStateMachine implements BulkheadStateMachine {
    private final boolean grantPermit;

    boolean acquireBlockingCalled = false;
    String reportedCallId;
    Duration reportedRtt;
    Throwable reportedError;

    FakeStateMachine(boolean grantPermit) {
      this.grantPermit = grantPermit;
    }

    @Override
    public boolean tryAcquireBlocking(String callId, Duration timeout) {
      this.acquireBlockingCalled = true;
      return grantPermit;
    }

    @Override
    public void releaseAndReport(String callId, Duration rtt, Throwable error) {
      this.reportedCallId = callId;
      this.reportedRtt = rtt;
      this.reportedError = error;
    }

    // Unused methods for this specific test
    @Override
    public InqEventPublisher getEventPublisher() {
      return null;
    }

    @Override
    public boolean tryAcquireNonBlocking(String callId) {
      return grantPermit;
    }

    @Override
    public int getMaxConcurrentCalls() {
      return 0;
    }

    @Override
    public int getAvailablePermits() {
      return 0;
    }

    @Override
    public int getConcurrentCalls() {
      return grantPermit ? 1 : 10;
    }
  }

  @Nested
  class SuccessfulExecution {

    @Test
    void executes_the_business_logic_and_reports_the_metrics_on_success() throws Exception {
      // Given
      Duration timeout = Duration.ofMillis(100);
      ImperativeBulkheadStrategy<String> strategy = new ImperativeBulkheadStrategy<>("test-bulkhead", timeout, nanoTimeSource);

      // We use a fake state machine that grants permits and records reports
      FakeStateMachine stateMachine = new FakeStateMachine(true);
      InqCall<String> successfulCall = new InqCall<>("call-1", () -> {
        Thread.sleep(10); // Simulate minimal work to get a measurable RTT
        return "success";
      });

      // When
      String result = strategy.decorate(successfulCall, stateMachine).callable().call();

      // Then
      assertThat(result).isEqualTo("success");

      // The state machine must have been called with blocking semantics
      assertThat(stateMachine.acquireBlockingCalled).isTrue();
      assertThat(stateMachine.reportedCallId).isEqualTo("call-1");

      // The RTT must be greater than zero and no error reported
      assertThat(stateMachine.reportedRtt).isGreaterThan(Duration.ZERO);
      assertThat(stateMachine.reportedError).isNull();
    }
  }

  @Nested
  class FailedExecution {

    @Test
    void executes_the_business_logic_and_reports_the_business_error_to_the_state_machine() {
      // Given
      Duration timeout = Duration.ZERO;
      ImperativeBulkheadStrategy<String> strategy = new ImperativeBulkheadStrategy<>("test-bulkhead", timeout, nanoTimeSource);

      FakeStateMachine stateMachine = new FakeStateMachine(true);
      RuntimeException businessError = new RuntimeException("Database offline");
      InqCall<String> failingCall = new InqCall<>("call-2", () -> {
        throw businessError;
      });

      // When
      Throwable thrown = catchThrowable(() -> strategy.decorate(failingCall, stateMachine).callable().call());

      // Then
      // The original exception is thrown to the caller
      assertThat(thrown).isSameAs(businessError);

      // But it MUST also be reported to the state machine for telemetry and rollback
      assertThat(stateMachine.reportedCallId).isEqualTo("call-2");
      assertThat(stateMachine.reportedError).isSameAs(businessError);
    }
  }

  // ── Helper methods and fake classes ──

  @Nested
  class RejectedExecution {

    @Test
    void throws_a_bulkhead_full_exception_if_no_permit_can_be_acquired_and_does_not_execute_logic() {
      // Given
      Duration timeout = Duration.ofSeconds(1);
      ImperativeBulkheadStrategy<String> strategy = new ImperativeBulkheadStrategy<>("test-bulkhead", timeout, nanoTimeSource);

      // A fake state machine that denies all permits (simulating a full bulkhead)
      FakeStateMachine stateMachine = new FakeStateMachine(false);

      boolean[] logicExecuted = {false};
      InqCall<String> targetCall = new InqCall<>("call-3", () -> {
        logicExecuted[0] = true;
        return "should-not-reach";
      });

      // When
      Throwable thrown = catchThrowable(() -> strategy.decorate(targetCall, stateMachine).callable().call());

      // Then
      assertThat(thrown)
          .isInstanceOf(InqBulkheadFullException.class)
          .hasMessageContaining("Bulkhead 'test-bulkhead' is full");

      // The business logic must never be touched
      assertThat(logicExecuted[0]).isFalse();

      // And releaseAndReport must not be called, because nothing was acquired
      assertThat(stateMachine.reportedCallId).isNull();
    }
  }
}
