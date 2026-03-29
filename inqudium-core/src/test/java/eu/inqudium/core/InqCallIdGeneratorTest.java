package eu.inqudium.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InqCallIdGenerator")
class InqCallIdGeneratorTest {

    @Nested
    @DisplayName("UUID generator")
    class UuidGenerator {

        @Test
        void should_return_a_non_null_non_empty_string() {
            // Given
            var generator = InqCallIdGenerator.uuid();

            // When
            var callId = generator.generate();

            // Then
            assertThat(callId).isNotNull().isNotBlank();
        }

        @Test
        void should_produce_unique_ids_across_multiple_calls() {
            // Given
            var generator = InqCallIdGenerator.uuid();
            var ids = new HashSet<String>();

            // When
            for (int i = 0; i < 100; i++) {
                ids.add(generator.generate());
            }

            // Then
            assertThat(ids).hasSize(100);
        }
    }

    @Nested
    @DisplayName("Custom generators")
    class CustomGenerators {

        @Test
        void should_support_sequential_counter_generator() {
            // Given
            var counter = new AtomicInteger(0);
            InqCallIdGenerator sequential = () -> "call-" + counter.incrementAndGet();

            // When
            var id1 = sequential.generate();
            var id2 = sequential.generate();
            var id3 = sequential.generate();

            // Then
            assertThat(id1).isEqualTo("call-1");
            assertThat(id2).isEqualTo("call-2");
            assertThat(id3).isEqualTo("call-3");
        }

        @Test
        void should_support_fixed_id_for_testing() {
            // Given
            InqCallIdGenerator fixed = () -> "test-fixed-id";

            // When
            var id1 = fixed.generate();
            var id2 = fixed.generate();

            // Then
            assertThat(id1).isEqualTo("test-fixed-id");
            assertThat(id2).isEqualTo("test-fixed-id");
        }
    }

    @Nested
    @DisplayName("Functional interface contract")
    class FunctionalInterfaceContract {

        @Test
        void should_be_assignable_from_a_method_reference() {
            // Given
            var counter = new AtomicInteger(0);

            // When — method reference assignment must compile
            InqCallIdGenerator gen = () -> String.valueOf(counter.incrementAndGet());

            // Then
            assertThat(gen.generate()).isEqualTo("1");
        }
    }
}
