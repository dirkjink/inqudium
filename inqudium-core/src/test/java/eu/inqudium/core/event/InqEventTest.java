package eu.inqudium.core.event;

import eu.inqudium.core.InqElementType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InqEventTest {

  @Nested
  class NullValidation {

    @Test
    void should_throw_exception_if_element_name_is_null() {
      // Given
      String nullElementName = null;

      // When & Then
      assertThatThrownBy(() -> new InqEvent("call-1", nullElementName, InqElementType.NO_ELEMENT, Instant.now()) {
      })
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("elementName must not be null");
    }

    @Test
    void should_throw_exception_if_element_type_is_null() {
      // Given
      InqElementType nullElementType = null;

      // When & Then
      assertThatThrownBy(() -> new InqEvent("call-1", "element-name", nullElementType, Instant.now()) {
      })
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("elementType must not be null");
    }

    @Test
    void should_throw_exception_if_timestamp_is_null() {
      // Given
      Instant nullTimestamp = null;

      // When & Then
      assertThatThrownBy(() -> new InqEvent("call-1", "element-name", InqElementType.NO_ELEMENT, nullTimestamp) {
      })
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("timestamp must not be null");
    }
  }

  @Nested
  class ProviderErrorPhaseHandling {

    @Test
    void should_generate_correct_error_code_for_execution_phase() {
      // Given
      String phase = "execution";

      // When
      InqProviderErrorEvent event = new InqProviderErrorEvent(
          "com.example.Provider", "SPI", phase, "Error", Instant.now()
      );

      // Then
      // The code for execution phase should differ from construction phase (uses errorCode(2) instead of errorCode(1))
      assertThat(event.getCode()).isEqualTo(InqElementType.NO_ELEMENT.errorCode(2));
    }
  }

  @Nested
  class ConstructorValidation {

    @Test
    void should_retain_all_constructor_parameters() {
      // Given
      String expectedCallId = "call-123";
      String expectedElementName = "my-circuit-breaker";
      // Using a real constant instead of a mock
      InqElementType expectedType = InqElementType.NO_ELEMENT;
      Instant expectedTimestamp = Instant.now();

      // When
      InqEvent actualEvent = new InqEvent(expectedCallId, expectedElementName, expectedType, expectedTimestamp) {
        // Anonymous concrete implementation for testing the abstract base
      };

      // Then
      assertThat(actualEvent.getCallId()).isEqualTo(expectedCallId);
      assertThat(actualEvent.getElementName()).isEqualTo(expectedElementName);
      assertThat(actualEvent.getElementType()).isEqualTo(expectedType);
      assertThat(actualEvent.getTimestamp()).isEqualTo(expectedTimestamp);
    }

    @Test
    void should_throw_exception_if_call_id_is_null() {
      // Given
      String nullCallId = null;

      // When & Then
      assertThatThrownBy(() -> new InqEvent(nullCallId, "name", InqElementType.NO_ELEMENT, Instant.now()) {
      })
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("callId must not be null");
    }
  }
}

