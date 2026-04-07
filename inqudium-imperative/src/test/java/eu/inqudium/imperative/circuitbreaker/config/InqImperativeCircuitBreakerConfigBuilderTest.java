package eu.inqudium.imperative.circuitbreaker.config;

import eu.inqudium.core.config.InqConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.circuitbreaker.config.InqCircuitBreakerConfig;
import eu.inqudium.core.element.circuitbreaker.config.InqCircuitBreakerConfigBuilder;
import eu.inqudium.core.element.circuitbreaker.config.TimeBasedSlidingWindowConfig;
import eu.inqudium.core.element.circuitbreaker.config.TimeBasedSlidingWindowConfigBuilder;
import eu.inqudium.core.element.circuitbreaker.metrics.FailureMetrics;
import eu.inqudium.core.event.InqEventPublisher;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InqImperativeCircuitBreakerConfigBuilderTest {

  // -------------------------------------------------------------------------
  // Helper: build via the public InqConfig DSL (handles GeneralConfig injection)
  // -------------------------------------------------------------------------

  private InqImperativeCircuitBreakerConfig buildWith(
      Consumer<InqImperativeCircuitBreakerConfigBuilder> customizer) {
    InqConfig inqConfig = InqConfig.configure()
        .general()
        .with(InqImperativeCircuitBreakerConfigBuilder.circuitBreaker(), customizer)
        .build();
    return inqConfig.of(InqImperativeCircuitBreakerConfig.class).orElseThrow();
  }

  @Nested
  class FactoryMethods {

    @Test
    void circuitBreaker_returns_a_new_builder_instance() {
      // When
      var builder = InqImperativeCircuitBreakerConfigBuilder.circuitBreaker();

      // Then
      assertThat(builder).isNotNull()
          .isInstanceOf(InqImperativeCircuitBreakerConfigBuilder.class);
    }

    @Test
    void standard_returns_a_new_builder_instance() {
      // When
      var builder = InqImperativeCircuitBreakerConfigBuilder.standard();

      // Then
      assertThat(builder).isNotNull()
          .isInstanceOf(InqImperativeCircuitBreakerConfigBuilder.class);
    }

    @Test
    void circuitBreaker_and_standard_return_distinct_instances() {
      // When
      var first = InqImperativeCircuitBreakerConfigBuilder.circuitBreaker();
      var second = InqImperativeCircuitBreakerConfigBuilder.standard();

      // Then
      assertThat(first).isNotSameAs(second);
    }
  }

  @Nested
  class BuildWithDefaults {

    @Test
    void build_with_only_name_applies_balanced_defaults() {
      // Given / When
      var config = buildWith(b -> b.name("my-breaker"));

      // Then: balanced preset defaults
      InqCircuitBreakerConfig cb = config.circuitBreaker();
      assertThat(cb.waitDurationInOpenState()).isEqualTo(Duration.ofSeconds(30));
      assertThat(cb.waitDurationNanos()).isEqualTo(Duration.ofSeconds(30).toNanos());
      assertThat(cb.permittedCallsInHalfOpen()).isEqualTo(3);
      assertThat(cb.successThresholdInHalfOpen()).isEqualTo(2);
    }

    @Test
    void build_propagates_general_config_to_result() {
      // Given / When
      var config = buildWith(b -> b.name("general-test"));

      // Then
      assertThat(config.general()).isNotNull();
    }

    @Test
    void build_sets_circuit_breaker_config_in_result() {
      // Given / When
      var config = buildWith(b -> b.name("cb-test"));

      // Then
      assertThat(config.circuitBreaker()).isNotNull();
      assertThat(config.circuitBreaker().name()).isEqualTo("cb-test");
    }
  }

  @Nested
  class Presets {

    @Test
    void protective_preset_configures_short_wait_and_strict_probing() {
      // Given / When
      var config = buildWith(b -> b.name("protective-breaker").protective());

      // Then
      InqCircuitBreakerConfig cb = config.circuitBreaker();
      assertThat(cb.waitDurationInOpenState()).isEqualTo(Duration.ofSeconds(5));
      assertThat(cb.waitDurationNanos()).isEqualTo(Duration.ofSeconds(5).toNanos());
      assertThat(cb.permittedCallsInHalfOpen()).isEqualTo(1);
      assertThat(cb.successThresholdInHalfOpen()).isEqualTo(1);
    }

    @Test
    void balanced_preset_configures_moderate_wait_and_standard_probing() {
      // Given / When
      var config = buildWith(b -> b.name("balanced-breaker").balanced());

      // Then
      InqCircuitBreakerConfig cb = config.circuitBreaker();
      assertThat(cb.waitDurationInOpenState()).isEqualTo(Duration.ofSeconds(30));
      assertThat(cb.waitDurationNanos()).isEqualTo(Duration.ofSeconds(30).toNanos());
      assertThat(cb.permittedCallsInHalfOpen()).isEqualTo(3);
      assertThat(cb.successThresholdInHalfOpen()).isEqualTo(2);
    }

    @Test
    void permissive_preset_configures_long_wait_and_lenient_probing() {
      // Given / When
      var config = buildWith(b -> b.name("permissive-breaker").permissive());

      // Then
      InqCircuitBreakerConfig cb = config.circuitBreaker();
      assertThat(cb.waitDurationInOpenState()).isEqualTo(Duration.ofSeconds(120));
      assertThat(cb.waitDurationNanos()).isEqualTo(Duration.ofSeconds(120).toNanos());
      assertThat(cb.permittedCallsInHalfOpen()).isEqualTo(5);
      assertThat(cb.successThresholdInHalfOpen()).isEqualTo(3);
    }

    @Test
    void later_preset_overrides_earlier_preset() {
      // Given / When: first protective, then permissive
      var config = buildWith(b -> b.name("override-breaker").protective().permissive());

      // Then: permissive values win
      InqCircuitBreakerConfig cb = config.circuitBreaker();
      assertThat(cb.waitDurationInOpenState()).isEqualTo(Duration.ofSeconds(120));
      assertThat(cb.permittedCallsInHalfOpen()).isEqualTo(5);
      assertThat(cb.successThresholdInHalfOpen()).isEqualTo(3);
    }
  }

  @Nested
  class IndividualSetters {

    @Test
    void custom_wait_duration_overrides_preset() {
      // Given
      Duration customDuration = Duration.ofSeconds(45);

      // When
      var config = buildWith(b -> b.name("custom-wait").balanced()
          .waitDurationInOpenState(customDuration));

      // Then
      assertThat(config.circuitBreaker().waitDurationInOpenState()).isEqualTo(customDuration);
    }

    @Test
    void custom_wait_duration_nanos_is_propagated() {
      // Given
      long nanos = 999_000_000L;

      // When
      var config = buildWith(b -> b.name("custom-nanos").balanced()
          .waitDurationNanos(nanos));

      // Then
      assertThat(config.circuitBreaker().waitDurationNanos()).isEqualTo(nanos);
    }

    @Test
    void custom_success_threshold_in_half_open_is_propagated() {
      // When
      var config = buildWith(b -> b.name("custom-threshold").balanced()
          .successThresholdInHalfOpen(5));

      // Then
      assertThat(config.circuitBreaker().successThresholdInHalfOpen()).isEqualTo(5);
    }

    @Test
    void custom_permitted_calls_in_half_open_is_propagated() {
      // When
      var config = buildWith(b -> b.name("custom-permitted").balanced()
          .permittedCallsInHalfOpen(10));

      // Then
      assertThat(config.circuitBreaker().permittedCallsInHalfOpen()).isEqualTo(10);
    }

    @Test
    void custom_record_failure_predicate_is_propagated() {
      // Given
      Predicate<Throwable> predicate = t -> t instanceof IllegalArgumentException;

      // When
      var config = buildWith(b -> b.name("custom-predicate").balanced()
          .recordFailurePredicate(predicate));

      // Then
      assertThat(config.circuitBreaker().recordFailurePredicate()).isSameAs(predicate);
    }

    @Test
    void custom_metrics_factory_is_propagated() {
      // Given
      LongFunction<FailureMetrics> factory = ts -> null;

      // When
      var config = buildWith(b -> b.name("custom-metrics").balanced()
          .metricsFactory(factory));

      // Then
      assertThat(config.circuitBreaker().metricsFactory()).isSameAs(factory);
    }

    @Test
    void enable_exception_optimization_set_to_false_is_propagated() {
      // When
      var config = buildWith(b -> b.name("exc-opt").balanced()
          .enableExceptionOptimization(false));

      // Then
      assertThat(config.circuitBreaker().enableExceptionOptimization()).isFalse();
    }

    @Test
    void custom_event_publisher_is_propagated() {
      // Given
      InqEventPublisher publisher = InqEventPublisher.create("pub", InqElementType.BULKHEAD);

      // When
      var config = buildWith(b -> b.name("custom-pub").balanced()
          .eventPublisher(publisher));

      // Then
      assertThat(config.circuitBreaker().eventPublisher()).isSameAs(publisher);
    }
  }

  @Nested
  class FailurePredicateSubBuilder {

    @Test
    void withRecordFailurePredicates_records_matching_exceptions() {
      // Given / When
      var config = buildWith(b -> b.name("fp-test").balanced()
          .withRecordFailurePredicates(fp ->
              fp.recordExceptions(IllegalArgumentException.class)));

      // Then
      Predicate<Throwable> predicate = config.circuitBreaker().recordFailurePredicate();
      assertThat(predicate).isNotNull();
      assertThat(predicate.test(new IllegalArgumentException())).isTrue();
      assertThat(predicate.test(new NullPointerException())).isFalse();
    }

    @Test
    void withRecordFailurePredicates_ignores_excluded_exceptions() {
      // Given / When: record all by default, but ignore IllegalStateException
      var config = buildWith(b -> b.name("fp-ignore").balanced()
          .withRecordFailurePredicates(fp ->
              fp.ignoreExceptions(IllegalStateException.class)));

      // Then
      Predicate<Throwable> predicate = config.circuitBreaker().recordFailurePredicate();
      assertThat(predicate.test(new RuntimeException())).isTrue();
      assertThat(predicate.test(new IllegalStateException())).isFalse();
    }

    @Test
    void withRecordFailurePredicates_always_ignore_vetoes_everything() {
      // Given / When: record RuntimeException, but always ignore IllegalArgumentException
      var config = buildWith(b -> b.name("fp-veto").balanced()
          .withRecordFailurePredicates(fp -> fp
              .recordExceptions(RuntimeException.class)
              .alwaysIgnoreWhen(t -> t instanceof IllegalArgumentException)));

      // Then
      Predicate<Throwable> predicate = config.circuitBreaker().recordFailurePredicate();
      assertThat(predicate.test(new RuntimeException())).isTrue();
      assertThat(predicate.test(new IllegalArgumentException())).isFalse();
    }

    @Test
    void withRecordFailurePredicates_always_record_overrides_ignore() {
      // Given / When: ignore IllegalStateException, but force-record it back
      var config = buildWith(b -> b.name("fp-force").balanced()
          .withRecordFailurePredicates(fp -> fp
              .ignoreExceptions(IllegalStateException.class)
              .alwaysRecordWhen(t -> t instanceof IllegalStateException)));

      // Then
      Predicate<Throwable> predicate = config.circuitBreaker().recordFailurePredicate();
      assertThat(predicate.test(new IllegalStateException())).isTrue();
    }
  }

  @Nested
  class Delegation {

    @Test
    void name_is_delegated_from_circuit_breaker_config() {
      // Given
      var config = buildWith(b -> b.name("delegation-test"));

      // When / Then
      assertThat(config.name()).isEqualTo("delegation-test");
      assertThat(config.name()).isEqualTo(config.circuitBreaker().name());
    }

    @Test
    void element_type_is_delegated_from_circuit_breaker_config() {
      // Given
      var config = buildWith(b -> b.name("type-test"));

      // When / Then
      assertThat(config.elementType()).isEqualTo(config.circuitBreaker().elementType());
    }

    @Test
    void event_publisher_is_delegated_from_circuit_breaker_config() {
      // Given
      var config = buildWith(b -> b.name("pub-test"));

      // When / Then
      assertThat(config.eventPublisher()).isSameAs(config.circuitBreaker().eventPublisher());
    }

    @Test
    void enable_exception_optimization_is_delegated_from_circuit_breaker_config() {
      // Given
      var config = buildWith(b -> b.name("opt-test"));

      // When / Then
      assertThat(config.enableExceptionOptimization())
          .isEqualTo(config.circuitBreaker().enableExceptionOptimization());
    }

    @Test
    void self_returns_the_same_instance() {
      // Given
      var config = buildWith(b -> b.name("self-test"));

      // When / Then
      assertThat(config.self()).isSameAs(config);
    }
  }

  @Nested
  class Validation {

    @Test
    void build_without_name_throws_illegal_state_exception() {
      // Given / When / Then
      assertThatThrownBy(() -> buildWith(InqCircuitBreakerConfigBuilder::balanced))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("name must be set");
    }

    @Test
    void name_with_null_throws_null_pointer_exception() {
      // Given / When / Then
      assertThatThrownBy(() -> buildWith(b -> b.name(null)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void name_with_blank_string_throws_illegal_argument_exception() {
      // Given / When / Then
      assertThatThrownBy(() -> buildWith(b -> b.name("   ")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be blank");
    }

    @Test
    void enable_exception_optimization_with_null_throws_null_pointer_exception() {
      // Given / When / Then
      assertThatThrownBy(() -> buildWith(b -> b.name("test").enableExceptionOptimization(null)))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class FluentChaining {

    @Test
    void full_fluent_chain_builds_successfully() {
      // Given / When
      var config = buildWith(b -> b
          .name("full-chain")
          .protective()
          .waitDurationInOpenState(Duration.ofSeconds(10))
          .waitDurationNanos(Duration.ofSeconds(10).toNanos())
          .successThresholdInHalfOpen(4)
          .permittedCallsInHalfOpen(6)
          .recordFailurePredicate(t -> true)
          .enableExceptionOptimization(true));

      // Then
      assertThat(config).isNotNull();
      assertThat(config.circuitBreaker().name()).isEqualTo("full-chain");
      assertThat(config.circuitBreaker().waitDurationInOpenState()).isEqualTo(Duration.ofSeconds(10));
      assertThat(config.circuitBreaker().successThresholdInHalfOpen()).isEqualTo(4);
      assertThat(config.circuitBreaker().permittedCallsInHalfOpen()).isEqualTo(6);
    }
  }

  @Nested
  class ExtensionRegistration {

    @Test
    void config_is_retrievable_from_inq_config_by_class() {
      // Given
      InqConfig inqConfig = InqConfig.configure()
          .general()
          .with(InqImperativeCircuitBreakerConfigBuilder.circuitBreaker(),
              b -> b.name("registry-test"))
          .build();

      // When
      var result = inqConfig.of(InqImperativeCircuitBreakerConfig.class);

      // Then
      assertThat(result).isPresent();
      assertThat(result.get().name()).isEqualTo("registry-test");
    }

    @Test
    void config_is_absent_when_not_registered() {
      // Given
      InqConfig inqConfig = InqConfig.configure().general().build();

      // When
      var result = inqConfig.of(InqImperativeCircuitBreakerConfig.class);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  class WithTimeBasedSlidingWindowConfig {

    private InqImperativeCircuitBreakerConfig buildWithSlidingWindow(
        Consumer<InqImperativeCircuitBreakerConfigBuilder> cbCustomizer,
        Consumer<TimeBasedSlidingWindowConfigBuilder> swCustomizer) {
      InqConfig inqConfig = InqConfig.configure()
          .general()
          .with(new TimeBasedSlidingWindowConfigBuilder(), swCustomizer)
          .with(InqImperativeCircuitBreakerConfigBuilder.circuitBreaker(), cbCustomizer)
          .build();
      return inqConfig.of(InqImperativeCircuitBreakerConfig.class).orElseThrow();
    }

    @Test
    void sliding_window_config_is_registered_alongside_circuit_breaker() {
      // Given / When
      InqConfig inqConfig = InqConfig.configure()
          .general()
          .with(new TimeBasedSlidingWindowConfigBuilder(), TimeBasedSlidingWindowConfigBuilder::balanced)
          .with(InqImperativeCircuitBreakerConfigBuilder.circuitBreaker(), b -> b.name("combined"))
          .build();

      // Then
      assertThat(inqConfig.of(InqImperativeCircuitBreakerConfig.class)).isPresent();
      assertThat(inqConfig.of(TimeBasedSlidingWindowConfig.class)).isPresent();
    }

    @Test
    void sliding_window_balanced_preset_has_expected_defaults() {
      // Given / When
      InqConfig inqConfig = InqConfig.configure()
          .general()
          .with(new TimeBasedSlidingWindowConfigBuilder(), TimeBasedSlidingWindowConfigBuilder::balanced)
          .build();
      var swConfig = inqConfig.of(TimeBasedSlidingWindowConfig.class).orElseThrow();

      // Then
      assertThat(swConfig.windowSizeInSeconds()).isEqualTo(60);
      assertThat(swConfig.maxFailuresInWindow()).isEqualTo(10);
    }

    @Test
    void sliding_window_protective_preset_has_expected_values() {
      // Given / When
      InqConfig inqConfig = InqConfig.configure()
          .general()
          .with(new TimeBasedSlidingWindowConfigBuilder(), TimeBasedSlidingWindowConfigBuilder::protective)
          .build();
      var swConfig = inqConfig.of(TimeBasedSlidingWindowConfig.class).orElseThrow();

      // Then
      assertThat(swConfig.windowSizeInSeconds()).isEqualTo(5);
      assertThat(swConfig.maxFailuresInWindow()).isEqualTo(3);
    }

    @Test
    void sliding_window_permissive_preset_has_expected_values() {
      // Given / When
      InqConfig inqConfig = InqConfig.configure()
          .general()
          .with(new TimeBasedSlidingWindowConfigBuilder(), TimeBasedSlidingWindowConfigBuilder::permissive)
          .build();
      var swConfig = inqConfig.of(TimeBasedSlidingWindowConfig.class).orElseThrow();

      // Then
      assertThat(swConfig.windowSizeInSeconds()).isEqualTo(300);
      assertThat(swConfig.maxFailuresInWindow()).isEqualTo(30);
    }

    @Test
    void sliding_window_without_configuration_falls_back_to_balanced() {
      // Given / When: no preset, no manual values
      InqConfig inqConfig = InqConfig.configure()
          .general()
          .with(new TimeBasedSlidingWindowConfigBuilder(), b -> {})
          .build();
      var swConfig = inqConfig.of(TimeBasedSlidingWindowConfig.class).orElseThrow();

      // Then
      assertThat(swConfig.windowSizeInSeconds()).isEqualTo(60);
      assertThat(swConfig.maxFailuresInWindow()).isEqualTo(10);
    }

    @Test
    void sliding_window_custom_values_override_preset() {
      // Given / When
      InqConfig inqConfig = InqConfig.configure()
          .general()
          .with(new TimeBasedSlidingWindowConfigBuilder(), b -> b
              .protective()
              .maxFailuresInWindow(7)
              .windowSizeInSeconds(20))
          .build();
      var swConfig = inqConfig.of(TimeBasedSlidingWindowConfig.class).orElseThrow();

      // Then
      assertThat(swConfig.maxFailuresInWindow()).isEqualTo(7);
      assertThat(swConfig.windowSizeInSeconds()).isEqualTo(20);
    }

    @Test
    void circuit_breaker_and_sliding_window_can_use_different_presets() {
      // Given / When: protective circuit breaker + permissive sliding window
      var cbConfig = buildWithSlidingWindow(
          cb -> cb.name("mixed-presets").protective(),
          TimeBasedSlidingWindowConfigBuilder::permissive);

      InqConfig inqConfig = InqConfig.configure()
          .general()
          .with(new TimeBasedSlidingWindowConfigBuilder(), TimeBasedSlidingWindowConfigBuilder::permissive)
          .with(InqImperativeCircuitBreakerConfigBuilder.circuitBreaker(),
              b -> b.name("mixed-presets").protective())
          .build();

      var swConfig = inqConfig.of(TimeBasedSlidingWindowConfig.class).orElseThrow();
      var imperativeConfig = inqConfig.of(InqImperativeCircuitBreakerConfig.class).orElseThrow();

      // Then: each has its own preset values
      assertThat(imperativeConfig.circuitBreaker().waitDurationInOpenState())
          .isEqualTo(Duration.ofSeconds(5));
      assertThat(swConfig.windowSizeInSeconds()).isEqualTo(300);
      assertThat(swConfig.maxFailuresInWindow()).isEqualTo(30);
    }

    @Test
    void sliding_window_self_returns_same_instance() {
      // Given
      var swConfig = new TimeBasedSlidingWindowConfig(5, 10);

      // When / Then
      assertThat(swConfig.self()).isSameAs(swConfig);
    }
  }
}
