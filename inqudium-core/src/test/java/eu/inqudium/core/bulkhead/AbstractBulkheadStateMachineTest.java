package eu.inqudium.core.bulkhead;

import eu.inqudium.core.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.event.InqEvent;
import eu.inqudium.core.event.InqEventConsumer;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.event.InqSubscription;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class AbstractBulkheadStateMachineTest {

  @Nested
  class EventPublishingAndRollback {

    @Test
    void a_successful_acquire_publishes_an_acquire_event_and_returns_true() throws Exception {
      // Given
      BulkheadConfig config = BulkheadConfig.builder().build();
      TestStateMachine stateMachine = new TestStateMachine("test-machine", config);
      FakeEventPublisher fakePublisher = new FakeEventPublisher();
      injectFakePublisher(stateMachine, fakePublisher);

      // When
      boolean result = stateMachine.triggerAcquireSuccess("call-1");

      // Then
      assertThat(result).isTrue();
      assertThat(fakePublisher.lastPublishedEvent).isInstanceOf(BulkheadOnAcquireEvent.class);
      assertThat(stateMachine.rollbackCalled).isFalse();
    }

    @Test
    void a_failed_acquire_publishes_a_reject_event() throws Exception {
      // Given
      BulkheadConfig config = BulkheadConfig.builder().build();
      TestStateMachine stateMachine = new TestStateMachine("test-machine", config);
      FakeEventPublisher fakePublisher = new FakeEventPublisher();
      injectFakePublisher(stateMachine, fakePublisher);

      // When
      stateMachine.triggerAcquireFailure("call-2");

      // Then
      assertThat(fakePublisher.lastPublishedEvent).isInstanceOf(BulkheadOnRejectEvent.class);
    }

    @Test
    void an_exception_during_acquire_event_publishing_forces_a_permit_rollback_to_prevent_leaks() throws Exception {
      // Given
      BulkheadConfig config = BulkheadConfig.builder().build();
      TestStateMachine stateMachine = new TestStateMachine("test-machine", config);

      RuntimeException publisherCrash = new RuntimeException("Publisher down");
      injectFakePublisher(stateMachine, new CrashingPublisher(publisherCrash, BulkheadOnAcquireEvent.class));

      // When
      Throwable thrown = catchThrowable(() -> stateMachine.triggerAcquireSuccess("call-3"));

      // Then
      assertThat(thrown).isSameAs(publisherCrash);
      assertThat(stateMachine.rollbackCalled).isTrue();
    }
  }

  @Nested
  class ReleaseAndExceptionHandling {

    @Test
    void a_successful_release_triggers_the_internal_release_and_publishes_a_release_event() throws Exception {
      // Given
      BulkheadConfig config = BulkheadConfig.builder().build();
      TestStateMachine stateMachine = new TestStateMachine("test-machine", config);
      FakeEventPublisher fakePublisher = new FakeEventPublisher();
      injectFakePublisher(stateMachine, fakePublisher);

      // When
      stateMachine.releaseAndReport("call-4", Duration.ofMillis(150), null);

      // Then
      assertThat(stateMachine.releaseCalled).isTrue();
      assertThat(fakePublisher.lastPublishedEvent).isInstanceOf(BulkheadOnReleaseEvent.class);
    }

    @Test
    void a_crashing_publisher_during_release_attaches_its_exception_as_suppressed_to_the_original_business_error() throws Exception {
      // Given
      BulkheadConfig config = BulkheadConfig.builder().build();
      TestStateMachine stateMachine = new TestStateMachine("test-machine", config);

      RuntimeException publisherCrash = new RuntimeException("Release event failed");
      injectFakePublisher(stateMachine, new CrashingPublisher(publisherCrash, BulkheadOnReleaseEvent.class));

      RuntimeException businessError = new RuntimeException("Database timeout");

      // When
      Throwable thrown = catchThrowable(() ->
          stateMachine.releaseAndReport("call-5", Duration.ofMillis(100), businessError)
      );

      // Then
      assertThat(thrown).isNull(); // It does not throw directly; it suppresses
      assertThat(businessError.getSuppressed()).containsExactly(publisherCrash);
      assertThat(stateMachine.releaseCalled).isTrue();
    }

    @Test
    void a_crashing_publisher_during_release_throws_directly_if_no_business_error_exists() throws Exception {
      // Given
      BulkheadConfig config = BulkheadConfig.builder().build();
      TestStateMachine stateMachine = new TestStateMachine("test-machine", config);

      RuntimeException publisherCrash = new RuntimeException("Release event failed");
      injectFakePublisher(stateMachine, new CrashingPublisher(publisherCrash, BulkheadOnReleaseEvent.class));

      // When
      Throwable thrown = catchThrowable(() ->
          stateMachine.releaseAndReport("call-6", Duration.ofMillis(100), null)
      );

      // Then
      assertThat(thrown).isSameAs(publisherCrash);
      assertThat(stateMachine.releaseCalled).isTrue();
    }
  }

  // ── Helper methods and fake classes ──

  private void injectFakePublisher(AbstractBulkheadStateMachine stateMachine, InqEventPublisher publisher) throws Exception {
    Field publisherField = AbstractBulkheadStateMachine.class.getDeclaredField("eventPublisher");
    publisherField.setAccessible(true);
    publisherField.set(stateMachine, publisher);
  }

  // A minimal concrete implementation to test the abstract base class
  private static class TestStateMachine extends AbstractBulkheadStateMachine {
    boolean releaseCalled = false;
    boolean rollbackCalled = false;

    TestStateMachine(String name, BulkheadConfig config) {
      super(name, config);
    }

    // Expose the protected methods for testing
    public boolean triggerAcquireSuccess(String callId) { return handleAcquireSuccess(callId); }
    public void triggerAcquireFailure(String callId) { handleAcquireFailure(callId); }

    @Override public boolean tryAcquireNonBlocking(String callId) { return false; }
    @Override public boolean tryAcquireBlocking(String callId, Duration timeout) { return false; }
    @Override public int getAvailablePermits() { return 1; }
    @Override public int getConcurrentCalls() { return 0; }

    @Override protected void releasePermitInternal() { releaseCalled = true; }
    @Override protected void rollbackPermit() { rollbackCalled = true; }
  }

  // A fake publisher that just stores the last event
  private static class FakeEventPublisher implements InqEventPublisher {
    Object lastPublishedEvent;

    @Override
    public void publish(InqEvent event) {
      this.lastPublishedEvent = event;
    }

    @Override
    public InqSubscription onEvent(InqEventConsumer consumer) {
      return null;
    }

    @Override
    public <E extends InqEvent> InqSubscription onEvent(Class<E> eventType, Consumer<E> consumer) {
      return null;
    }
  }

  // A fake publisher that crashes on specific event types
  private record CrashingPublisher(RuntimeException exceptionToThrow, Class<?> failingEventType) implements InqEventPublisher {
    @Override
    public void publish(InqEvent event) {
      if (failingEventType.isInstance(event)) {
        throw exceptionToThrow;
      }
    }

    @Override
    public InqSubscription onEvent(InqEventConsumer consumer) {
      return null;
    }

    @Override
    public <E extends InqEvent> InqSubscription onEvent(Class<E> eventType, Consumer<E> consumer) {
      return null;
    }
  }
}