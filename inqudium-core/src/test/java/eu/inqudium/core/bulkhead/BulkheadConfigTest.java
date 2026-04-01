package eu.inqudium.core.bulkhead;

import eu.inqudium.core.InqCallIdGenerator;
import eu.inqudium.core.InqClock;
import eu.inqudium.core.bulkhead.algo.AimdLimitAlgorithm;
import eu.inqudium.core.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.compatibility.InqCompatibility;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BulkheadConfigTest {
  @Nested
  class BuilderNullSafety {

    @Test
    void passing_null_values_to_the_builder_throws_a_null_pointer_exception_for_required_fields() {
      // Given
      BulkheadConfig.Builder builder = BulkheadConfig.builder();

      // When / Then
      assertThatCode(() -> builder.maxWaitDuration(null))
          .isInstanceOf(NullPointerException.class);

      assertThatCode(() -> builder.compatibility(null))
          .isInstanceOf(NullPointerException.class);

      assertThatCode(() -> builder.clock(null))
          .isInstanceOf(NullPointerException.class);

      assertThatCode(() -> builder.logger(null))
          .isInstanceOf(NullPointerException.class);

      assertThatCode(() -> builder.callIdGenerator(null))
          .isInstanceOf(NullPointerException.class);

      // Note: limitAlgorithm explicitly allows null to signify static limits,
      // so it is not tested for NullPointerException here.
    }
  }

  @Nested
  class DefaultValues {

    @Test
    void a_default_configuration_has_the_expected_standard_values() {
      // Given
      BulkheadConfig config = BulkheadConfig.ofDefaults();

      // When / Then
      assertThat(config.getMaxConcurrentCalls()).isEqualTo(25);
      assertThat(config.getMaxWaitDuration()).isEqualTo(Duration.ZERO);
      assertThat(config.getCompatibility()).isEqualTo(InqCompatibility.ofDefaults());
      assertThat(config.getClock()).isNotNull();
      assertThat(config.getLogger()).isNotNull();
      assertThat(config.getCallIdGenerator()).isNotNull();

      // By default, no adaptive algorithm is set (static bulkhead)
      assertThat(config.getLimitAlgorithm()).isNull();
    }
  }

  @Nested
  class BuilderConfiguration {

    @Test
    void a_custom_configuration_can_be_built_with_specific_values_including_an_adaptive_algorithm() {
      // Given
      int customMaxCalls = 50;
      Duration customDuration = Duration.ofSeconds(5);
      InqClock customClock = () -> Instant.EPOCH;
      Logger customLogger = LoggerFactory.getLogger("CustomLogger");
      InqCallIdGenerator customGenerator = () -> "custom-id";
      InqCompatibility customCompatibility = InqCompatibility.ofDefaults();

      // We use a real algorithm instance for the test
      InqLimitAlgorithm customAlgorithm = new AimdLimitAlgorithm(10, 5, 20, 0.5);

      // When
      BulkheadConfig config = BulkheadConfig.builder()
          .maxConcurrentCalls(customMaxCalls)
          .maxWaitDuration(customDuration)
          .clock(customClock)
          .logger(customLogger)
          .callIdGenerator(customGenerator)
          .compatibility(customCompatibility)
          .limitAlgorithm(customAlgorithm)
          .build();

      // Then
      assertThat(config.getMaxConcurrentCalls()).isEqualTo(customMaxCalls);
      assertThat(config.getMaxWaitDuration()).isEqualTo(customDuration);
      assertThat(config.getClock()).isSameAs(customClock);
      assertThat(config.getLogger()).isSameAs(customLogger);
      assertThat(config.getCallIdGenerator()).isSameAs(customGenerator);
      assertThat(config.getCompatibility()).isSameAs(customCompatibility);
      assertThat(config.getLimitAlgorithm()).isSameAs(customAlgorithm);
    }
  }

  @Nested
  class EqualityAndHashCode {

    @Test
    void two_configurations_with_identical_values_are_considered_equal() {
      // Given
      InqLimitAlgorithm algorithm = new AimdLimitAlgorithm(10, 1, 20, 0.5);

      BulkheadConfig config1 = BulkheadConfig.builder()
          .maxConcurrentCalls(10)
          .maxWaitDuration(Duration.ofSeconds(1))
          .limitAlgorithm(algorithm)
          .build();

      BulkheadConfig config2 = BulkheadConfig.builder()
          .maxConcurrentCalls(10)
          .maxWaitDuration(Duration.ofSeconds(1))
          .limitAlgorithm(algorithm)
          .build();

      // When / Then
      assertThat(config1).isEqualTo(config2);
      assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
    }

    @Test
    void two_configurations_with_different_algorithms_are_not_considered_equal() {
      // Given
      InqLimitAlgorithm algorithm1 = new AimdLimitAlgorithm(10, 1, 20, 0.5);
      InqLimitAlgorithm algorithm2 = new AimdLimitAlgorithm(15, 5, 30, 0.8);

      BulkheadConfig config1 = BulkheadConfig.builder().limitAlgorithm(algorithm1).build();
      BulkheadConfig config2 = BulkheadConfig.builder().limitAlgorithm(algorithm2).build();

      // When / Then
      assertThat(config1).isNotEqualTo(config2);
    }
  }

  // WaitDurationEdgeCases tests remain untouched and valid
}