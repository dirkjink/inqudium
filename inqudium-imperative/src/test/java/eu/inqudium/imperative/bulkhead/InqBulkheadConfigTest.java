package eu.inqudium.imperative.bulkhead;

import eu.inqudium.core.callid.InqCallIdGenerator;
import eu.inqudium.core.config.InqConfig;
import eu.inqudium.core.config.compatibility.InqCompatibility;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.algo.VegasLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.config.AimdLimitAlgorithmConfig;
import eu.inqudium.core.element.bulkhead.config.VegasLimitAlgorithmConfig;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.log.LoggerFactory;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;
import eu.inqudium.imperative.bulkhead.config.CoDelBulkheadStrategyConfig;
import eu.inqudium.imperative.bulkhead.config.InqImperativeBulkheadConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static eu.inqudium.core.element.bulkhead.config.AimdLimitAlgorithmConfigBuilder.aimdLimitAlgorithm;
import static eu.inqudium.core.element.bulkhead.config.VegasLimitAlgorithmConfigBuilder.vegasLimitAlgorithm;
import static eu.inqudium.imperative.bulkhead.config.CoDelBulkheadStrategyConfigBuilder.coDelBulkheadStrategy;
import static eu.inqudium.imperative.bulkhead.config.InqImperativeBulkheadConfigBuilder.bulkhead;
import static org.assertj.core.api.Assertions.assertThat;

class InqBulkheadConfigTest {

  @Nested
  class GeneralConfigurationTests {

    @Test
    void configuration_builder_should_allow_full_customization_of_general_settings() {
      // Given custom dummy implementations for the general configuration
      InqClock customClock = InqClock.system();
      InqNanoTimeSource customNanoTimeSource = InqNanoTimeSource.system();
      InqCompatibility customCompatibility = InqCompatibility.ofDefaults();
      InqCallIdGenerator customCallIdGenerator = InqCallIdGenerator.uuid();
      LoggerFactory customLoggerFactory = LoggerFactory.NO_OP_LOGGER_FACTORY;

      // When building the configuration with all custom general settings
      InqConfig config = InqConfig.configure()
          .general(builder -> builder
              .clock(customClock)
              .nanoTimeSource(customNanoTimeSource)
              .compatibility(customCompatibility)
              .callIdGenerator(customCallIdGenerator)
              .loggerFactory(customLoggerFactory))
          .build();

      // Then the general configuration should contain the provided custom instances
      assertThat(config.general()).isNotNull();
      assertThat(config.general().clock()).isSameAs(customClock);
      assertThat(config.general().nanoTimesource()).isSameAs(customNanoTimeSource);
      assertThat(config.general().compatibility()).isSameAs(customCompatibility);
      assertThat(config.general().callIdGenerator()).isSameAs(customCallIdGenerator);
      assertThat(config.general().loggerFactory()).isSameAs(customLoggerFactory);
    }
  }

  @Nested
  class BulkheadConfigurationTests {

    @Test
    void configuration_builder_should_successfully_register_and_build_bulkhead_with_codel_defaults() {
      // Given the required parameters and custom dummy implementations for a bulkhead
      String expectedName = "test-bulkhead";
      int expectedMaxConcurrentCalls = 10;
      Duration expectedWaitDuration = Duration.ofMillis(500);

      // Assuming strategy could be an Enum, we pass null to test the setter without needing mockito.
      InqEventPublisher customEventPublisher = InqEventPublisher.create("name", InqElementType.BULKHEAD);

      // When appending a bulkhead extension to the configuration
      InqConfig config = InqConfig.configure()
          .general()
          .with(bulkhead(), c -> c
              .name(expectedName)
              .maxConcurrentCalls(expectedMaxConcurrentCalls)
              .maxWaitDuration(expectedWaitDuration)
              .eventPublisher(customEventPublisher))
          .with(coDelBulkheadStrategy().balanced())
          .build();

      // Then the configuration should contain the correctly configured bulkhead extension
      assertThat(config.of(InqImperativeBulkheadConfig.class)).isPresent().get().satisfies(bulkheadConfig -> {
        assertThat(bulkheadConfig.name()).isEqualTo(expectedName);
        assertThat(bulkheadConfig.elementType()).isEqualTo(InqElementType.BULKHEAD);
        assertThat(bulkheadConfig.maxConcurrentCalls()).isEqualTo(expectedMaxConcurrentCalls);
        assertThat(bulkheadConfig.maxWaitDuration()).isEqualTo(expectedWaitDuration);
        assertThat(bulkheadConfig.eventPublisher()).isSameAs(customEventPublisher);
        assertThat(bulkheadConfig.general()).isEqualTo(config.general());
      });

      // Then the configuration should contain the correctly configured VegasLimitAlgorithm extension
      assertThat(config.of(CoDelBulkheadStrategyConfig.class)).isPresent().get().satisfies(codel -> {
        assertThat(codel.interval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(codel.targetDelay()).isEqualTo(Duration.ofMillis(100));
      });
    }

    @Test
    void configuration_builder_should_successfully_register_and_build_bulkhead_with_vegas_defaults() {
      // Given the required parameters and custom dummy implementations for a bulkhead
      String expectedName = "test-bulkhead";
      int expectedMaxConcurrentCalls = 10;
      Duration expectedWaitDuration = Duration.ofMillis(500);

      int initialLimit = 50;
      int minLimit = 5;
      int maxLimit = 500;
      Duration smoothingTau = Duration.ofMillis(1000);
      Duration baselineDrift = Duration.ofSeconds(10);
      Duration errorRateSmoothing = Duration.ofSeconds(5);
      double errorThreshold = 0.1;
      double utilizationThreshold = 0.6;

      // Assuming strategy could be an Enum, we pass null to test the setter without needing mockito.
      InqEventPublisher customEventPublisher = InqEventPublisher.create("name", InqElementType.BULKHEAD);

      // When appending a bulkhead extension to the configuration
      InqConfig config = InqConfig.configure()
          .general()
          .with(bulkhead(), c -> c
              .name(expectedName)
              .maxConcurrentCalls(expectedMaxConcurrentCalls)
              .maxWaitDuration(expectedWaitDuration)
              .eventPublisher(customEventPublisher))
          .with(vegasLimitAlgorithm().balanced())
          .build();

      // Then the configuration should contain the correctly configured bulkhead extension
      assertThat(config.of(InqImperativeBulkheadConfig.class)).isPresent().get().satisfies(bulkheadConfig -> {
        assertThat(bulkheadConfig.name()).isEqualTo(expectedName);
        assertThat(bulkheadConfig.elementType()).isEqualTo(InqElementType.BULKHEAD);
        assertThat(bulkheadConfig.maxConcurrentCalls()).isEqualTo(expectedMaxConcurrentCalls);
        assertThat(bulkheadConfig.maxWaitDuration()).isEqualTo(expectedWaitDuration);
        assertThat(bulkheadConfig.eventPublisher()).isSameAs(customEventPublisher);
        assertThat(bulkheadConfig.general()).isEqualTo(config.general());
      });

      // Then the configuration should contain the correctly configured VegasLimitAlgorithm extension
      assertThat(config.of(VegasLimitAlgorithmConfig.class)).isPresent().get().satisfies(vegasConfig -> {
        assertThat(vegasConfig.initialLimit()).isEqualTo(initialLimit);
        assertThat(vegasConfig.minLimit()).isEqualTo(minLimit);
        assertThat(vegasConfig.maxLimit()).isEqualTo(maxLimit);
        assertThat(vegasConfig.smoothingTimeConstant()).isEqualTo(smoothingTau);
        assertThat(vegasConfig.baselineDriftTimeConstant()).isEqualTo(baselineDrift);
        assertThat(vegasConfig.errorRateSmoothingTimeConstant()).isEqualTo(errorRateSmoothing);
        assertThat(vegasConfig.errorRateThreshold()).isEqualTo(errorThreshold);
        assertThat(vegasConfig.minUtilizationThreshold()).isEqualTo(utilizationThreshold);
      });
    }

    @Test
    void configuration_builder_should_successfully_register_and_build_bulkhead_extension() {
      // Given the required parameters and custom dummy implementations for a bulkhead
      String expectedName = "test-bulkhead";
      int expectedMaxConcurrentCalls = 10;
      Duration expectedWaitDuration = Duration.ofMillis(500);

      // Assuming strategy could be an Enum, we pass null to test the setter without needing mockito.
      InqLimitAlgorithm customLimitAlgorithm = VegasLimitAlgorithm.balanced();
      InqEventPublisher customEventPublisher = InqEventPublisher.create("name", InqElementType.BULKHEAD);
      ;

      // When appending a bulkhead extension to the configuration
      InqConfig config = InqConfig.configure()
          .general()
          .with(bulkhead(), bulkhead -> bulkhead
              .name(expectedName)
              .maxConcurrentCalls(expectedMaxConcurrentCalls)
              .maxWaitDuration(expectedWaitDuration)
              .limitAlgorithm(customLimitAlgorithm)
              .eventPublisher(customEventPublisher))
          .build();

      // Then the configuration should contain the correctly configured bulkhead extension
      assertThat(config.of(InqImperativeBulkheadConfig.class)).isPresent().get().satisfies(bulkheadConfig -> {
        assertThat(bulkheadConfig.name()).isEqualTo(expectedName);
        assertThat(bulkheadConfig.elementType()).isEqualTo(InqElementType.BULKHEAD);
        assertThat(bulkheadConfig.maxConcurrentCalls()).isEqualTo(expectedMaxConcurrentCalls);
        assertThat(bulkheadConfig.maxWaitDuration()).isEqualTo(expectedWaitDuration);
        assertThat(bulkheadConfig.limitAlgorithm()).isSameAs(customLimitAlgorithm);
        assertThat(bulkheadConfig.eventPublisher()).isSameAs(customEventPublisher);
        assertThat(bulkheadConfig.general()).isEqualTo(config.general());
      });
    }

    @Test
    void bulkhead_builder_should_apply_default_values_when_optional_fields_are_omitted() {
      // Given a builder setup without setting optional fields like event publisher or max wait duration
      String expectedName = "minimal-bulkhead";

      // When building the minimal bulkhead configuration
      InqConfig config = InqConfig.configure()
          .general()
          .with(bulkhead(), bulkhead -> bulkhead
              .name(expectedName)
          ).build();

      // Then the default values should be correctly instantiated
      assertThat(config.of(InqImperativeBulkheadConfig.class)).isPresent().get().satisfies(bulkheadConfig -> {
        assertThat(bulkheadConfig.maxWaitDuration()).isEqualTo(Duration.ZERO);
        assertThat(bulkheadConfig.eventPublisher()).isNotNull();
      });
    }
  }

  @Nested
  class AimdLimitAlgorithmConfigurationTests {

    @Test
    void builder_should_allow_creating_custom_aimd_configuration() {
      // Given a set of custom parameters for the AIMD algorithm
      int initialLimit = 30;
      int minLimit = 2;
      int maxLimit = 300;
      double backoffRatio = 0.6;
      Duration smoothingTau = Duration.ofSeconds(4);
      double errorThreshold = 0.2;
      boolean windowedIncrease = false;
      double utilizationThreshold = 0.8;

      // When building a custom AIMD configuration
      InqConfig config = InqConfig.configure()
          .general()
          .with(aimdLimitAlgorithm(), aimd -> aimd
              .initialLimit(initialLimit)
              .minLimit(minLimit)
              .maxLimit(maxLimit)
              .backoffRatio(backoffRatio)
              .smoothingTimeConstant(smoothingTau)
              .errorRateThreshold(errorThreshold)
              .windowedIncrease(windowedIncrease)
              .minUtilizationThreshold(utilizationThreshold))
          .build();

      // Then all properties should match the provided custom values
      assertThat(config.of(AimdLimitAlgorithmConfig.class)).isPresent().get().satisfies(aimdConfig -> {
        assertThat(aimdConfig.initialLimit()).isEqualTo(initialLimit);
        assertThat(aimdConfig.minLimit()).isEqualTo(minLimit);
        assertThat(aimdConfig.maxLimit()).isEqualTo(maxLimit);
        assertThat(aimdConfig.backoffRatio()).isEqualTo(backoffRatio);
        assertThat(aimdConfig.smoothingTimeConstant()).isEqualTo(smoothingTau);
        assertThat(aimdConfig.errorRateThreshold()).isEqualTo(errorThreshold);
        assertThat(aimdConfig.windowedIncrease()).isFalse();
        assertThat(aimdConfig.minUtilizationThreshold()).isEqualTo(utilizationThreshold);
      });
    }
  }

  @Nested
  class VegasLimitAlgorithmConfigurationTests {

    @Test
    void builder_should_allow_creating_custom_vegas_configuration() {
      // Given a set of custom parameters for the Vegas algorithm
      int initialLimit = 40;
      int minLimit = 5;
      int maxLimit = 400;
      Duration smoothingTau = Duration.ofMillis(800);
      Duration baselineDrift = Duration.ofSeconds(15);
      Duration errorRateSmoothing = Duration.ofSeconds(8);
      double errorThreshold = 0.12;
      double utilizationThreshold = 0.65;

      // When building a custom Vegas configuration
      InqConfig config = InqConfig.configure()
          .general()
          .with(vegasLimitAlgorithm(), vegas -> vegas
              .initialLimit(initialLimit)
              .minLimit(minLimit)
              .maxLimit(maxLimit)
              .smoothingTimeConstant(smoothingTau)
              .baselineDriftTimeConstant(baselineDrift)
              .errorRateSmoothingTimeConstant(errorRateSmoothing)
              .errorRateThreshold(errorThreshold)
              .minUtilizationThreshold(utilizationThreshold))
          .build();

      // Then all properties should match the provided custom values
      assertThat(config.of(VegasLimitAlgorithmConfig.class)).isPresent().get().satisfies(vegasConfig -> {
        assertThat(vegasConfig.initialLimit()).isEqualTo(initialLimit);
        assertThat(vegasConfig.minLimit()).isEqualTo(minLimit);
        assertThat(vegasConfig.maxLimit()).isEqualTo(maxLimit);
        assertThat(vegasConfig.smoothingTimeConstant()).isEqualTo(smoothingTau);
        assertThat(vegasConfig.baselineDriftTimeConstant()).isEqualTo(baselineDrift);
        assertThat(vegasConfig.errorRateSmoothingTimeConstant()).isEqualTo(errorRateSmoothing);
        assertThat(vegasConfig.errorRateThreshold()).isEqualTo(errorThreshold);
        assertThat(vegasConfig.minUtilizationThreshold()).isEqualTo(utilizationThreshold);
      });
    }
  }
}
