package eu.inqudium.core.element.bulkhead;

import eu.inqudium.core.element.bulkhead.InqBulkheadInterruptedException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InqBulkheadInterruptedExceptionTest {

  @Nested
  class ExceptionFormatting {

    @Test
    void the_exception_message_is_formatted_correctly_for_an_interrupted_thread() {
      // Given
      // Specific values for the exception state
      String callId = "call-2";
      String elementName = "database-bulkhead";
      int currentCalls = 5;
      int maxCalls = 20;

      // When
      // We instantiate the exception
      InqBulkheadInterruptedException exception = new InqBulkheadInterruptedException(
          callId, elementName, currentCalls, maxCalls);

      // Then
      // The properties and the formatted message must match the expectations
      assertThat(exception.getConcurrentCalls()).isEqualTo(currentCalls);
      assertThat(exception.getMaxConcurrentCalls()).isEqualTo(maxCalls);

      assertThat(exception.getMessage())
          .isEqualTo("[call-2] INQ-BH-002: Thread interrupted while waiting for " +
              "Bulkhead 'database-bulkhead' permit (5/20 concurrent calls)");
    }
  }
}