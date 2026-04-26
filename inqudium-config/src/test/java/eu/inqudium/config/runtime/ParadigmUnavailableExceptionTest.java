package eu.inqudium.config.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ParadigmUnavailableException")
class ParadigmUnavailableExceptionTest {

    @Test
    void should_carry_the_message_to_the_runtime_exception_super() {
        // Given / When
        ParadigmUnavailableException ex = new ParadigmUnavailableException(
                "The 'reactive' paradigm requires module 'inqudium-reactive' on the classpath.");

        // Then
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).contains("inqudium-reactive");
    }
}
