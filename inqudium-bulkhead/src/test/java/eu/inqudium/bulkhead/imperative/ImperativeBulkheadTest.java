package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqClock;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.BlockingBulkheadStateMachine;
import eu.inqudium.core.event.InqEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ImperativeBulkheadTest {

  private ImperativeBulkhead bulkhead;
  private FakeBlockingStateMachine stateMachine;
  private BulkheadConfig config;

  @BeforeEach
  void setUp() {
    // Given: Basis-Konfiguration für den Bulkhead
    config = BulkheadConfig.builder()
        .maxConcurrentCalls(2)
        .maxWaitDuration(Duration.ofMillis(100))
        .nanoTimeSource(System::nanoTime)
        .clock(new StubClock())
        .build();

    stateMachine = new FakeBlockingStateMachine(2);
    bulkhead = new ImperativeBulkhead("test-bulkhead", config, stateMachine);
  }

  @Nested
  class BasicProperties {

    @Test
    void should_return_configured_name() {
      // When & Then
      assertThat(bulkhead.getName()).isEqualTo("test-bulkhead");
    }

    @Test
    void should_delegate_concurrent_calls_count_to_state_machine() {
      // Given: Simulation von 3 aktiven Aufrufen in der State Machine
      stateMachine.setConcurrentCalls(3);

      // When & Then
      assertThat(bulkhead.getConcurrentCalls()).isEqualTo(3);
    }
  }

  @Nested
  class CallDecoration {

    @Test
    void should_decorate_call_with_imperative_strategy() throws Exception {
      // Given: Wir nutzen den echten InqCall Record
      AtomicInteger executionCount = new AtomicInteger(0);
      Callable<String> businessLogic = () -> {
        executionCount.incrementAndGet();
        return "execution-result";
      };

      // Direkte Instanziierung des Records (angenommene Standard-Felder: callId, callable)
      InqCall<String> originalCall = new InqCall<>("test-id", businessLogic);

      // When: Dekoration und Ausführung
      InqCall<String> decoratedCall = bulkhead.decorate(originalCall);
      String result = decoratedCall.callable().call();

      // Then: Verifizierung der Ergebnisse und der State Machine Interaktion
      assertThat(result).isEqualTo("execution-result");
      assertThat(executionCount.get()).isEqualTo(1);
      assertThat(stateMachine.getAcquireCount()).isEqualTo(1);
      assertThat(stateMachine.getReleaseCount()).isEqualTo(1);
    }
  }

  // --- Manuelle Test Doubles (Fakes & Stubs) ---

  private static class FakeBlockingStateMachine implements BlockingBulkheadStateMachine {
    private final int maxCalls;
    private int concurrentCalls = 0;
    private int acquireCount = 0;
    private int releaseCount = 0;

    FakeBlockingStateMachine(int maxCalls) { this.maxCalls = maxCalls; }

    public void setConcurrentCalls(int count) { this.concurrentCalls = count; }
    public int getAcquireCount() { return acquireCount; }
    public int getReleaseCount() { return releaseCount; }

    @Override
    public boolean tryAcquire(String callId, Duration timeout) {
      acquireCount++;
      return true;
    }

    @Override public int getMaxConcurrentCalls() { return maxCalls; }
    @Override public int getConcurrentCalls() { return concurrentCalls; }
    @Override public int getAvailablePermits() { return maxCalls - concurrentCalls; }
    @Override public void releaseAndReport(String callId, Duration rtt, Throwable error) { releaseCount++; }
    @Override public InqEventPublisher getEventPublisher() { return null; }
  }

  private static class StubClock implements InqClock {
    @Override public Instant instant() { return Instant.now(); }
  }
}