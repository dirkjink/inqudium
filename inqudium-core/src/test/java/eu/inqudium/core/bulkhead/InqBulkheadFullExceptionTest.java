package eu.inqudium.core.bulkhead;

import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InqBulkheadFullExceptionTest {

  @Nested
  class ExceptionFormatting {

    @Test
    void the_exception_message_is_formatted_correctly_and_exposes_its_properties() {
      // Given
      // Specific values for the exception state
      String callId = "call-1";
      String elementName = "backend-service";
      int currentCalls = 10;
      int maxCalls = 10;

      // When
      // We instantiate the exception
      InqBulkheadFullException exception = new InqBulkheadFullException(
          callId, elementName, currentCalls, maxCalls);

      // Then
      // The properties and the formatted message must match the expectations
      assertThat(exception.getCallId()).isEqualTo(callId);
      assertThat(exception.getElementName()).isEqualTo(elementName);
      assertThat(exception.getConcurrentCalls()).isEqualTo(currentCalls);
      assertThat(exception.getMaxConcurrentCalls()).isEqualTo(maxCalls);

      assertThat(exception.getMessage())
          .isEqualTo("[call-1] INQ-BH-001: Bulkhead 'backend-service' is full (10/10 concurrent calls)");
    }
  }
}
