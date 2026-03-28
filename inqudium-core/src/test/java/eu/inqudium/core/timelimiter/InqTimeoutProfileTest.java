package eu.inqudium.core.timelimiter;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InqTimeoutProfile")
class InqTimeoutProfileTest {

  // =========================================================================
  // AgnosticTimeoutType
  // =========================================================================

  @Nested
  @DisplayName("AgnosticTimeoutType enum")
  class AgnosticTimeoutTypeEnum {

    @Test
    void enum_declares_all_four_required_agnostic_timeout_types() {
      // Given / When
      var values = AgnosticTimeoutType.values();

      // Then
      assertThat(values).containsExactlyInAnyOrder(
          AgnosticTimeoutType.CONNECTION_ACQUIRE,
          AgnosticTimeoutType.CONNECTION_ESTABLISHMENT,
          AgnosticTimeoutType.READ_INACTIVITY,
          AgnosticTimeoutType.WRITE_OPERATION,
          AgnosticTimeoutType.SERVER_RESPONSE
      );
    }

    @Test
    void each_enum_constant_can_be_resolved_by_its_name() {
      // Given / When / Then
      assertThat(AgnosticTimeoutType.valueOf("CONNECTION_ACQUIRE"))
          .isEqualTo(AgnosticTimeoutType.CONNECTION_ACQUIRE);
      assertThat(AgnosticTimeoutType.valueOf("CONNECTION_ESTABLISHMENT"))
          .isEqualTo(AgnosticTimeoutType.CONNECTION_ESTABLISHMENT);
      assertThat(AgnosticTimeoutType.valueOf("READ_INACTIVITY"))
          .isEqualTo(AgnosticTimeoutType.READ_INACTIVITY);
      assertThat(AgnosticTimeoutType.valueOf("WRITE_OPERATION"))
          .isEqualTo(AgnosticTimeoutType.WRITE_OPERATION);
      assertThat(AgnosticTimeoutType.valueOf("SERVER_RESPONSE"))
          .isEqualTo(AgnosticTimeoutType.SERVER_RESPONSE);
    }
  }

  // =========================================================================
  // TimeoutCalculator
  // =========================================================================

  // =========================================================================
  // RssTimeoutCalculator
  // =========================================================================

  @Nested
  @DisplayName("RssTimeoutCalculator")
  class RssTimeoutCalculatorTests {

    // Default constructor uses TWO_SIGMA
    private final RssTimeoutCalculator calculator = new RssTimeoutCalculator();

    @Test
    void default_constructor_uses_two_sigma_level() {
      // Given / When / Then
      assertThat(calculator.getSigmaLevel()).isEqualTo(SigmaLevel.TWO_SIGMA);
    }

    @Test
    void rss_produces_a_strictly_smaller_result_than_worst_case_for_multiple_components() {
      // Given — ONE_SIGMA is required here: only at 1σ is RSS guaranteed to be ≤ WORST_CASE.
      //   At 2σ/3σ the multiplier widens the tolerance beyond the linear sum.
      var components = java.util.List.of(
          Duration.ofMillis(250),
          Duration.ofSeconds(3)
      );

      // When
      var rssResult = new RssTimeoutCalculator(SigmaLevel.ONE_SIGMA).calculate(components, 1.0);
      var wcResult = new WorstCaseTimeoutCalculator().calculate(components, 1.0);

      // Then
      assertThat(rssResult).isLessThan(wcResult);
    }

    @Test
    void rss_with_a_single_component_equals_worst_case_for_that_same_single_component() {
      // Given — ONE_SIGMA is required: with a single component RSS(1σ) = WORST_CASE by definition
      //   (√(t²) = t), but RSS(2σ) = 2t > t = WORST_CASE.
      var components = java.util.List.of(Duration.ofSeconds(2));

      // When
      var rssResult = new RssTimeoutCalculator(SigmaLevel.ONE_SIGMA).calculate(components, 1.0);
      var wcResult = new WorstCaseTimeoutCalculator().calculate(components, 1.0);

      // Then
      assertThat(rssResult).isEqualTo(wcResult);
    }

    @Test
    void rss_result_is_always_positive_for_positive_components() {
      // Given
      var components = java.util.List.of(
          Duration.ofMillis(100),
          Duration.ofMillis(500),
          Duration.ofSeconds(2)
      );

      // When
      var result = calculator.calculate(components, 1.2);

      // Then
      assertThat(result).isPositive();
    }

    @Nested
    @DisplayName("Sigma level")
    class SigmaLevelTests {

      @Test
      void higher_sigma_level_produces_a_strictly_larger_timeout_than_lower_sigma_level() {
        // Given
        var components = java.util.List.of(
            Duration.ofMillis(250),
            Duration.ofSeconds(3)
        );
        var oneSigma = new RssTimeoutCalculator(SigmaLevel.ONE_SIGMA);
        var twoSigma = new RssTimeoutCalculator(SigmaLevel.TWO_SIGMA);
        var threeSigma = new RssTimeoutCalculator(SigmaLevel.THREE_SIGMA);

        // When
        var result1 = oneSigma.calculate(components, 1.0);
        var result2 = twoSigma.calculate(components, 1.0);
        var result3 = threeSigma.calculate(components, 1.0);

        // Then — sigma levels must be strictly ordered
        assertThat(result1).isLessThan(result2);
        assertThat(result2).isLessThan(result3);
      }

      @Test
      void two_sigma_timeout_is_exactly_twice_the_tolerance_of_one_sigma_timeout() {
        // Given — isolate the tolerance portion by using a single component with margin=1.0
        //   nominal   = 1000 ms × 0.5 = 500 ms
        //   tolerance = 1000 ms × 0.5 = 500 ms
        //   1σ result = 500 + 1×500 = 1000 ms
        //   2σ result = 500 + 2×500 = 1500 ms
        var components = java.util.List.of(Duration.ofSeconds(1));
        var oneSigma = new RssTimeoutCalculator(SigmaLevel.ONE_SIGMA);
        var twoSigma = new RssTimeoutCalculator(SigmaLevel.TWO_SIGMA);

        // When
        var result1 = oneSigma.calculate(components, 1.0);
        var result2 = twoSigma.calculate(components, 1.0);

        // Then
        assertThat(result1).isEqualTo(Duration.ofMillis(1000));
        assertThat(result2).isEqualTo(Duration.ofMillis(1500));
      }

      @Test
      void three_sigma_timeout_is_exactly_three_times_the_tolerance_of_one_sigma_timeout() {
        // Given — same derivation as above; 3σ result = 500 + 3×500 = 2000 ms
        var components = java.util.List.of(Duration.ofSeconds(1));
        var oneSigma = new RssTimeoutCalculator(SigmaLevel.ONE_SIGMA);
        var threeSigma = new RssTimeoutCalculator(SigmaLevel.THREE_SIGMA);

        // When
        var result1 = oneSigma.calculate(components, 1.0);
        var result3 = threeSigma.calculate(components, 1.0);

        // Then
        assertThat(result1).isEqualTo(Duration.ofMillis(1000));
        assertThat(result3).isEqualTo(Duration.ofMillis(2000));
      }

      @Test
      void constructor_rejects_a_null_sigma_level() {
        // Given / When / Then
        assertThatThrownBy(() -> new RssTimeoutCalculator(null))
            .isInstanceOf(NullPointerException.class);
      }

      @Test
      void get_sigma_level_returns_the_level_passed_to_the_constructor() {
        // Given / When / Then
        assertThat(new RssTimeoutCalculator(SigmaLevel.ONE_SIGMA).getSigmaLevel())
            .isEqualTo(SigmaLevel.ONE_SIGMA);
        assertThat(new RssTimeoutCalculator(SigmaLevel.THREE_SIGMA).getSigmaLevel())
            .isEqualTo(SigmaLevel.THREE_SIGMA);
      }
    }

    @Nested
    @DisplayName("Fallback behaviour")
    class FallbackBehaviour {

      @Test
      void empty_component_list_returns_the_five_second_fallback_duration() {
        // Given
        var components = java.util.List.<Duration>of();

        // When
        var result = calculator.calculate(components, 1.0);

        // Then
        assertThat(result).isEqualTo(Duration.ofSeconds(5));
      }

      @Test
      void empty_component_list_ignores_the_safety_margin_factor_and_still_returns_fallback() {
        // Given
        var components = java.util.List.<Duration>of();

        // When
        var result = calculator.calculate(components, 2.0);

        // Then — fallback is returned unchanged regardless of margin
        assertThat(result).isEqualTo(Duration.ofSeconds(5));
      }
    }

    @Nested
    @DisplayName("Safety margin factor")
    class SafetyMarginFactor {

      @Test
      void applying_a_safety_margin_factor_scales_the_rss_result_proportionally() {
        // Given
        var components = java.util.List.of(Duration.ofSeconds(2));

        // When
        var base = calculator.calculate(components, 1.0);
        var withMargin = calculator.calculate(components, 1.5);

        // Then
        assertThat(withMargin.toMillis())
            .isCloseTo((long) (base.toMillis() * 1.5), Offset.offset(1L));
      }
    }
  }

  // =========================================================================
  // WorstCaseTimeoutCalculator
  // =========================================================================

  @Nested
  @DisplayName("WorstCaseTimeoutCalculator")
  class WorstCaseTimeoutCalculatorTests {

    private final TimeoutCalculator calculator = new WorstCaseTimeoutCalculator();

    @Test
    void worst_case_total_equals_the_sum_of_all_individual_component_timeouts_when_margin_is_one() {
      // Given — with safetyMarginFactor=1.0 the formula reduces to:
      //   result = Σ(ms_i × 0.5) + Σ(ms_i × 0.5) = Σ ms_i
      var c1 = Duration.ofSeconds(1); // 1000 ms
      var c2 = Duration.ofSeconds(2); // 2000 ms
      var components = java.util.List.of(c1, c2);

      // When
      var result = calculator.calculate(components, 1.0);

      // Then — expected: 1000 + 2000 = 3000 ms
      assertThat(result).isEqualTo(Duration.ofMillis(3000));
    }

    @Test
    void worst_case_produces_a_strictly_larger_result_than_rss_for_multiple_components() {
      // Given — ONE_SIGMA is required for the RSS side: only at 1σ is RSS ≤ WORST_CASE.
      var components = java.util.List.of(
          Duration.ofMillis(250),
          Duration.ofSeconds(3)
      );

      // When
      var wcResult = calculator.calculate(components, 1.0);
      var rssResult = new RssTimeoutCalculator(SigmaLevel.ONE_SIGMA).calculate(components, 1.0);

      // Then
      assertThat(wcResult).isGreaterThan(rssResult);
    }

    @Nested
    @DisplayName("Fallback behaviour")
    class FallbackBehaviour {

      @Test
      void empty_component_list_returns_the_five_second_fallback_duration() {
        // Given
        var components = java.util.List.<Duration>of();

        // When
        var result = calculator.calculate(components, 1.0);

        // Then
        assertThat(result).isEqualTo(Duration.ofSeconds(5));
      }

      @Test
      void empty_component_list_ignores_the_safety_margin_factor_and_still_returns_fallback() {
        // Given
        var components = java.util.List.<Duration>of();

        // When
        var result = calculator.calculate(components, 2.0);

        // Then — fallback is returned unchanged regardless of margin
        assertThat(result).isEqualTo(Duration.ofSeconds(5));
      }
    }

    @Nested
    @DisplayName("Safety margin factor")
    class SafetyMarginFactor {

      @Test
      void applying_a_safety_margin_factor_scales_the_worst_case_result_proportionally() {
        // Given
        var components = java.util.List.of(Duration.ofSeconds(2));

        // When
        var base = calculator.calculate(components, 1.0);
        var withMargin = calculator.calculate(components, 1.5);

        // Then
        assertThat(withMargin.toMillis())
            .isCloseTo((long) (base.toMillis() * 1.5), Offset.offset(1L));
      }
    }
  }

  // =========================================================================
  // MaxTimeoutCalculator
  // =========================================================================

  @Nested
  @DisplayName("MaxTimeoutCalculator")
  class MaxTimeoutCalculatorTests {

    private final TimeoutCalculator calculator = new MaxTimeoutCalculator();

    @Test
    void max_returns_the_largest_component_duration_when_margin_is_one() {
      // Given
      var components = java.util.List.of(
          Duration.ofMillis(250),
          Duration.ofSeconds(3),   // largest — 3000 ms
          Duration.ofMillis(750)
      );

      // When
      var result = calculator.calculate(components, 1.0);

      // Then
      assertThat(result).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void max_result_is_strictly_less_than_or_equal_to_worst_case_for_the_same_components() {
      // Given
      var components = java.util.List.of(
          Duration.ofMillis(250),
          Duration.ofSeconds(3)
      );

      // When
      var maxResult = calculator.calculate(components, 1.0);
      var wcResult = new WorstCaseTimeoutCalculator().calculate(components, 1.0);

      // Then — MAX can never exceed WORST_CASE (which sums all components)
      assertThat(maxResult).isLessThanOrEqualTo(wcResult);
    }

    @Test
    void max_with_a_single_component_returns_that_component_scaled_by_the_margin() {
      // Given
      var components = java.util.List.of(Duration.ofSeconds(2));

      // When
      var result = calculator.calculate(components, 1.5);

      // Then — 2000 ms × 1.5 = 3000 ms
      assertThat(result).isEqualTo(Duration.ofMillis(3000));
    }

    @Test
    void max_with_all_equal_components_returns_that_shared_value_scaled_by_the_margin() {
      // Given
      var components = java.util.List.of(
          Duration.ofSeconds(2),
          Duration.ofSeconds(2),
          Duration.ofSeconds(2)
      );

      // When
      var result = calculator.calculate(components, 1.0);

      // Then
      assertThat(result).isEqualTo(Duration.ofSeconds(2));
    }

    @Nested
    @DisplayName("Fallback behaviour")
    class FallbackBehaviour {

      @Test
      void empty_component_list_returns_the_five_second_fallback_duration() {
        // Given
        var components = java.util.List.<Duration>of();

        // When
        var result = calculator.calculate(components, 1.0);

        // Then
        assertThat(result).isEqualTo(Duration.ofSeconds(5));
      }

      @Test
      void empty_component_list_ignores_the_safety_margin_factor_and_still_returns_fallback() {
        // Given
        var components = java.util.List.<Duration>of();

        // When
        var result = calculator.calculate(components, 2.0);

        // Then — fallback is returned unchanged regardless of margin
        assertThat(result).isEqualTo(Duration.ofSeconds(5));
      }
    }

    @Nested
    @DisplayName("Safety margin factor")
    class SafetyMarginFactor {

      @Test
      void applying_a_safety_margin_factor_scales_the_maximum_component_proportionally() {
        // Given
        var components = java.util.List.of(
            Duration.ofMillis(500),
            Duration.ofSeconds(2)   // largest
        );

        // When
        var base = calculator.calculate(components, 1.0);
        var withMargin = calculator.calculate(components, 1.5);

        // Then
        assertThat(withMargin.toMillis())
            .isCloseTo((long) (base.toMillis() * 1.5), Offset.offset(1L));
      }
    }
  }

  // =========================================================================
  // InqTimeoutProfile — builder & profile
  // =========================================================================

  @Nested
  @DisplayName("RSS calculation via profile")
  class RssCalculation {

    @Test
    void rss_profile_produces_tighter_timeout_than_worst_case_for_the_same_components() {
      // Given — ONE_SIGMA is required: only at 1σ is RSS guaranteed to be ≤ WORST_CASE.
      //   The default TWO_SIGMA intentionally produces a larger result than WORST_CASE
      //   to cover 95.45 % of requests instead of the linear worst-case bound.
      var rss = InqTimeoutProfile.builder()
          .connectionEstablishmentTimeout(Duration.ofMillis(250))
          .readInactivityTimeout(Duration.ofSeconds(3))
          .method(TimeoutCalculation.RSS)
          .sigmaLevel(SigmaLevel.ONE_SIGMA)
          .safetyMarginFactor(1.0)
          .build();

      var worstCase = InqTimeoutProfile.builder()
          .connectionEstablishmentTimeout(Duration.ofMillis(250))
          .readInactivityTimeout(Duration.ofSeconds(3))
          .method(TimeoutCalculation.WORST_CASE)
          .safetyMarginFactor(1.0)
          .build();

      // When
      var rssTimeout = rss.timeLimiterTimeout();
      var wcTimeout = worstCase.timeLimiterTimeout();

      // Then
      assertThat(rssTimeout).isLessThan(wcTimeout);
    }

    @Test
    void profile_produces_a_positive_timelimiter_timeout_for_typical_http_values() {
      // Given
      var profile = InqTimeoutProfile.builder()
          .connectionEstablishmentTimeout(Duration.ofMillis(250))
          .readInactivityTimeout(Duration.ofSeconds(3))
          .build(); // defaults: RSS, TWO_SIGMA, 1.2 margin

      // When
      var timeout = profile.timeLimiterTimeout();

      // Then
      assertThat(timeout).isPositive();
    }

    @Test
    void three_sigma_profile_produces_a_strictly_larger_timeout_than_two_sigma_profile() {
      // Given
      var twoSigma = InqTimeoutProfile.builder()
          .connectionEstablishmentTimeout(Duration.ofMillis(250))
          .readInactivityTimeout(Duration.ofSeconds(3))
          .method(TimeoutCalculation.RSS)
          .sigmaLevel(SigmaLevel.TWO_SIGMA)
          .safetyMarginFactor(1.0)
          .build();

      var threeSigma = InqTimeoutProfile.builder()
          .connectionEstablishmentTimeout(Duration.ofMillis(250))
          .readInactivityTimeout(Duration.ofSeconds(3))
          .method(TimeoutCalculation.RSS)
          .sigmaLevel(SigmaLevel.THREE_SIGMA)
          .safetyMarginFactor(1.0)
          .build();

      // When / Then
      assertThat(threeSigma.timeLimiterTimeout()).isGreaterThan(twoSigma.timeLimiterTimeout());
    }

    @Test
    void one_sigma_profile_produces_a_strictly_smaller_timeout_than_two_sigma_profile() {
      // Given
      var oneSigma = InqTimeoutProfile.builder()
          .connectionEstablishmentTimeout(Duration.ofMillis(250))
          .readInactivityTimeout(Duration.ofSeconds(3))
          .method(TimeoutCalculation.RSS)
          .sigmaLevel(SigmaLevel.ONE_SIGMA)
          .safetyMarginFactor(1.0)
          .build();

      var twoSigma = InqTimeoutProfile.builder()
          .connectionEstablishmentTimeout(Duration.ofMillis(250))
          .readInactivityTimeout(Duration.ofSeconds(3))
          .method(TimeoutCalculation.RSS)
          .sigmaLevel(SigmaLevel.TWO_SIGMA)
          .safetyMarginFactor(1.0)
          .build();

      // When / Then
      assertThat(oneSigma.timeLimiterTimeout()).isLessThan(twoSigma.timeLimiterTimeout());
    }

    @Test
    void sigma_level_setter_is_ignored_when_method_is_not_rss() {
      // Given — sigmaLevel on a WORST_CASE profile must not affect the result
      var worstCaseWithSigma = InqTimeoutProfile.builder()
          .connectionEstablishmentTimeout(Duration.ofSeconds(1))
          .method(TimeoutCalculation.WORST_CASE)
          .sigmaLevel(SigmaLevel.THREE_SIGMA) // has no effect for WORST_CASE
          .safetyMarginFactor(1.0)
          .build();

      var worstCaseWithoutSigma = InqTimeoutProfile.builder()
          .connectionEstablishmentTimeout(Duration.ofSeconds(1))
          .method(TimeoutCalculation.WORST_CASE)
          .safetyMarginFactor(1.0)
          .build();

      // When / Then — both profiles must produce identical timeouts
      assertThat(worstCaseWithSigma.timeLimiterTimeout())
          .isEqualTo(worstCaseWithoutSigma.timeLimiterTimeout());
    }

    @Nested
    @DisplayName("Safety margin")
    class SafetyMargin {

      @Test
      void a_safety_margin_factor_of_one_point_five_scales_the_timeout_by_exactly_that_factor() {
        // Given
        var noMargin = InqTimeoutProfile.builder()
            .connectionEstablishmentTimeout(Duration.ofSeconds(1))
            .readInactivityTimeout(Duration.ofSeconds(2))
            .method(TimeoutCalculation.WORST_CASE)
            .safetyMarginFactor(1.0)
            .build();

        var withMargin = InqTimeoutProfile.builder()
            .connectionEstablishmentTimeout(Duration.ofSeconds(1))
            .readInactivityTimeout(Duration.ofSeconds(2))
            .method(TimeoutCalculation.WORST_CASE)
            .safetyMarginFactor(1.5)
            .build();

        // When
        var baseMs = noMargin.timeLimiterTimeout().toMillis();
        var marginMs = withMargin.timeLimiterTimeout().toMillis();

        // Then
        assertThat(marginMs).isCloseTo((long) (baseMs * 1.5), Offset.offset(1L));
      }

      @Test
      void builder_rejects_a_safety_margin_factor_below_one() {
        // Given / When / Then
        assertThatThrownBy(() ->
            InqTimeoutProfile.builder().safetyMarginFactor(0.8)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("1.0");
      }

      @Test
      void builder_accepts_a_safety_margin_factor_of_exactly_one() {
        // Given / When / Then — boundary: 1.0 must not throw
        assertThat(
            InqTimeoutProfile.builder()
                .connectionEstablishmentTimeout(Duration.ofSeconds(1))
                .safetyMarginFactor(1.0)
                .build()
        ).isNotNull();
      }
    }

    @Nested
    @DisplayName("Slow call threshold alignment")
    class SlowCallThreshold {

      @Test
      void slow_call_duration_threshold_is_always_equal_to_the_timelimiter_timeout() {
        // Given
        var profile = InqTimeoutProfile.builder()
            .connectionEstablishmentTimeout(Duration.ofMillis(500))
            .readInactivityTimeout(Duration.ofSeconds(5))
            .build();

        // When / Then — ADR-012 requirement
        assertThat(profile.slowCallDurationThreshold())
            .isEqualTo(profile.timeLimiterTimeout());
      }
    }

    @Nested
    @DisplayName("Accessor methods")
    class Accessors {

      @Test
      void connection_establishment_timeout_accessor_returns_the_configured_duration() {
        // Given
        var profile = InqTimeoutProfile.builder()
            .connectionEstablishmentTimeout(Duration.ofMillis(250))
            .build();

        // When / Then
        assertThat(profile.connectionEstablishmentTimeout()).isEqualTo(Duration.ofMillis(250));
      }

      @Test
      void read_inactivity_timeout_accessor_returns_the_configured_duration() {
        // Given
        var profile = InqTimeoutProfile.builder()
            .readInactivityTimeout(Duration.ofSeconds(3))
            .build();

        // When / Then
        assertThat(profile.readInactivityTimeout()).isEqualTo(Duration.ofSeconds(3));
      }

      @Test
      void connection_acquire_timeout_accessor_returns_the_pool_acquire_duration() {
        // Given
        var profile = InqTimeoutProfile.builder()
            .connectionAcquireTimeout(Duration.ofMillis(100))
            .build();

        // When / Then
        assertThat(profile.connectionAcquireTimeout()).isEqualTo(Duration.ofMillis(100));
      }

      @Test
      void write_operation_timeout_accessor_returns_the_socket_write_duration() {
        // Given
        var profile = InqTimeoutProfile.builder()
            .writeOperationTimeout(Duration.ofMillis(750))
            .build();

        // When / Then
        assertThat(profile.writeOperationTimeout()).isEqualTo(Duration.ofMillis(750));
      }

      @Test
      void server_response_timeout_accessor_returns_the_ttfb_duration() {
        // Given
        var profile = InqTimeoutProfile.builder()
            .serverResponseTimeout(Duration.ofSeconds(2))
            .build();

        // When / Then
        assertThat(profile.serverResponseTimeout()).isEqualTo(Duration.ofSeconds(2));
      }

      @Test
      void get_timeout_with_explicit_type_returns_the_configured_value() {
        // Given
        var profile = InqTimeoutProfile.builder()
            .connectionEstablishmentTimeout(Duration.ofMillis(250))
            .readInactivityTimeout(Duration.ofSeconds(3))
            .method(TimeoutCalculation.RSS)
            .safetyMarginFactor(1.3)
            .build();

        // When / Then
        assertThat(profile.getTimeout(AgnosticTimeoutType.CONNECTION_ESTABLISHMENT))
            .isEqualTo(Duration.ofMillis(250));
        assertThat(profile.getTimeout(AgnosticTimeoutType.READ_INACTIVITY))
            .isEqualTo(Duration.ofSeconds(3));
        assertThat(profile.getMethod()).isEqualTo(TimeoutCalculation.RSS);
        assertThat(profile.getSafetyMarginFactor()).isEqualTo(1.3);
      }

      @Test
      void rss_profile_exposes_the_configured_sigma_level_via_get_sigma_level() {
        // Given
        var profile = InqTimeoutProfile.builder()
            .connectionEstablishmentTimeout(Duration.ofSeconds(1))
            .method(TimeoutCalculation.RSS)
            .sigmaLevel(SigmaLevel.THREE_SIGMA)
            .build();

        // When / Then
        assertThat(profile.getSigmaLevel())
            .isPresent()
            .contains(SigmaLevel.THREE_SIGMA);
      }

      @Test
      void non_rss_profile_returns_empty_optional_from_get_sigma_level() {
        // Given
        var worstCase = InqTimeoutProfile.builder()
            .connectionEstablishmentTimeout(Duration.ofSeconds(1))
            .method(TimeoutCalculation.WORST_CASE)
            .build();
        var max = InqTimeoutProfile.builder()
            .connectionEstablishmentTimeout(Duration.ofSeconds(1))
            .method(TimeoutCalculation.MAX)
            .build();

        // When / Then — sigma level is only meaningful for RSS
        assertThat(worstCase.getSigmaLevel()).isEmpty();
        assertThat(max.getSigmaLevel()).isEmpty();
      }

      @Test
      void unconfigured_timeout_type_returns_duration_zero() {
        // Given — profile has only connectionEstablishmentTimeout set
        var profile = InqTimeoutProfile.builder()
            .connectionEstablishmentTimeout(Duration.ofMillis(100))
            .build();

        // When / Then — all other types must fall back to ZERO
        assertThat(profile.readInactivityTimeout()).isEqualTo(Duration.ZERO);
        assertThat(profile.connectionAcquireTimeout()).isEqualTo(Duration.ZERO);
        assertThat(profile.writeOperationTimeout()).isEqualTo(Duration.ZERO);
        assertThat(profile.serverResponseTimeout()).isEqualTo(Duration.ZERO);
      }

      @Test
      void get_timeout_components_returns_only_the_keys_that_were_explicitly_configured() {
        // Given
        var profile = InqTimeoutProfile.builder()
            .connectionEstablishmentTimeout(Duration.ofMillis(250))
            .readInactivityTimeout(Duration.ofSeconds(3))
            .build();

        // When
        var components = profile.getTimeoutComponents();

        // Then
        assertThat(components)
            .containsOnlyKeys(
                AgnosticTimeoutType.CONNECTION_ESTABLISHMENT,
                AgnosticTimeoutType.READ_INACTIVITY)
            .hasSize(2);
      }
    }

    @Nested
    @DisplayName("Duplicate parameter handling")
    class DuplicateParameterHandling {

      @Test
      void calling_connection_establishment_timeout_twice_replaces_the_first_value_with_the_second() {
        // Given
        var profile = InqTimeoutProfile.builder()
            .connectionEstablishmentTimeout(Duration.ofMillis(100)) // first call — will be overwritten
            .connectionEstablishmentTimeout(Duration.ofMillis(500)) // second call — must win
            .build();

        // When / Then
        assertThat(profile.connectionEstablishmentTimeout()).isEqualTo(Duration.ofMillis(500));
      }

      @Test
      void calling_read_inactivity_timeout_twice_replaces_the_first_value_with_the_second() {
        // Given
        var profile = InqTimeoutProfile.builder()
            .readInactivityTimeout(Duration.ofSeconds(1))
            .readInactivityTimeout(Duration.ofSeconds(5))
            .build();

        // When / Then
        assertThat(profile.readInactivityTimeout()).isEqualTo(Duration.ofSeconds(5));
      }

      @Test
      void generic_timeout_setter_called_twice_for_the_same_type_keeps_only_the_last_value() {
        // Given
        var profile = InqTimeoutProfile.builder()
            .timeout(AgnosticTimeoutType.WRITE_OPERATION, Duration.ofMillis(200))
            .timeout(AgnosticTimeoutType.WRITE_OPERATION, Duration.ofMillis(800))
            .build();

        // When / Then
        assertThat(profile.writeOperationTimeout()).isEqualTo(Duration.ofMillis(800));
      }

      @Test
      void duplicate_connection_establishment_timeout_calls_do_not_accumulate_into_the_component_map() {
        // Given
        var profile = InqTimeoutProfile.builder()
            .connectionEstablishmentTimeout(Duration.ofMillis(100))
            .connectionEstablishmentTimeout(Duration.ofMillis(200))
            .connectionEstablishmentTimeout(Duration.ofMillis(300))
            .build();

        // When / Then — map must contain exactly one entry for CONNECTION_ESTABLISHMENT
        assertThat(profile.getTimeoutComponents())
            .containsOnlyKeys(AgnosticTimeoutType.CONNECTION_ESTABLISHMENT)
            .hasSize(1);

        assertThat(profile.connectionEstablishmentTimeout()).isEqualTo(Duration.ofMillis(300));
      }

      @Test
      void overwriting_a_timeout_affects_the_computed_timelimiter_timeout() {
        // Given
        var profileWithSmallConnect = InqTimeoutProfile.builder()
            .connectionEstablishmentTimeout(Duration.ofMillis(100))
            .method(TimeoutCalculation.WORST_CASE)
            .safetyMarginFactor(1.0)
            .build();

        // Same chain but the connect timeout is overwritten to a larger value
        var profileWithLargeConnect = InqTimeoutProfile.builder()
            .connectionEstablishmentTimeout(Duration.ofMillis(100))
            .connectionEstablishmentTimeout(Duration.ofMillis(2000)) // overwrites
            .method(TimeoutCalculation.WORST_CASE)
            .safetyMarginFactor(1.0)
            .build();

        // When
        var smallTimeout = profileWithSmallConnect.timeLimiterTimeout();
        var largeTimeout = profileWithLargeConnect.timeLimiterTimeout();

        // Then — overwriting with a bigger value must increase the computed result
        assertThat(largeTimeout).isGreaterThan(smallTimeout);
      }
    }

    @Nested
    @DisplayName("Builder — all five agnostic timeout types")
    class BuilderAllFiveTypes {

      @Test
      void all_five_agnostic_timeout_types_can_be_set_via_dedicated_builder_methods() {
        // Given
        var profile = InqTimeoutProfile.builder()
            .connectionAcquireTimeout(Duration.ofMillis(50))
            .connectionEstablishmentTimeout(Duration.ofMillis(250))
            .readInactivityTimeout(Duration.ofSeconds(3))
            .writeOperationTimeout(Duration.ofMillis(750))
            .serverResponseTimeout(Duration.ofSeconds(2))
            .build();

        // When / Then — each dedicated accessor must return its specific value
        assertThat(profile.connectionAcquireTimeout()).isEqualTo(Duration.ofMillis(50));
        assertThat(profile.connectionEstablishmentTimeout()).isEqualTo(Duration.ofMillis(250));
        assertThat(profile.readInactivityTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(profile.writeOperationTimeout()).isEqualTo(Duration.ofMillis(750));
        assertThat(profile.serverResponseTimeout()).isEqualTo(Duration.ofSeconds(2));
      }

      @Test
      void all_five_agnostic_timeout_types_can_be_set_via_the_generic_timeout_builder_method() {
        // Given
        var profile = InqTimeoutProfile.builder()
            .timeout(AgnosticTimeoutType.CONNECTION_ACQUIRE, Duration.ofMillis(50))
            .timeout(AgnosticTimeoutType.CONNECTION_ESTABLISHMENT, Duration.ofMillis(250))
            .timeout(AgnosticTimeoutType.READ_INACTIVITY, Duration.ofSeconds(3))
            .timeout(AgnosticTimeoutType.WRITE_OPERATION, Duration.ofMillis(750))
            .timeout(AgnosticTimeoutType.SERVER_RESPONSE, Duration.ofSeconds(2))
            .build();

        // When
        var components = profile.getTimeoutComponents();

        // Then
        assertThat(components)
            .containsEntry(AgnosticTimeoutType.CONNECTION_ACQUIRE, Duration.ofMillis(50))
            .containsEntry(AgnosticTimeoutType.CONNECTION_ESTABLISHMENT, Duration.ofMillis(250))
            .containsEntry(AgnosticTimeoutType.READ_INACTIVITY, Duration.ofSeconds(3))
            .containsEntry(AgnosticTimeoutType.WRITE_OPERATION, Duration.ofMillis(750))
            .containsEntry(AgnosticTimeoutType.SERVER_RESPONSE, Duration.ofSeconds(2))
            .hasSize(5);
      }

      @Test
      void named_builder_methods_and_generic_timeout_method_map_to_the_same_enum_keys() {
        // Given — two profiles built differently but with identical logical configuration
        var namedProfile = InqTimeoutProfile.builder()
            .connectionEstablishmentTimeout(Duration.ofMillis(300))
            .readInactivityTimeout(Duration.ofSeconds(2))
            .build();

        var genericProfile = InqTimeoutProfile.builder()
            .timeout(AgnosticTimeoutType.CONNECTION_ESTABLISHMENT, Duration.ofMillis(300))
            .timeout(AgnosticTimeoutType.READ_INACTIVITY, Duration.ofSeconds(2))
            .build();

        // When / Then — computed timeouts must be identical because the components are identical
        assertThat(namedProfile.timeLimiterTimeout())
            .isEqualTo(genericProfile.timeLimiterTimeout());
      }

      @Test
      void builder_rejects_a_null_timeout_type_in_the_generic_setter() {
        // Given / When / Then
        assertThatThrownBy(() ->
            InqTimeoutProfile.builder().timeout(null, Duration.ofSeconds(1))
        ).isInstanceOf(NullPointerException.class);
      }

      @Test
      void builder_rejects_a_null_duration_value_in_the_generic_setter() {
        // Given / When / Then
        assertThatThrownBy(() ->
            InqTimeoutProfile.builder().timeout(AgnosticTimeoutType.READ_INACTIVITY, null)
        ).isInstanceOf(NullPointerException.class);
      }
    }

    @Nested
    @DisplayName("Empty profile fallback")
    class EmptyProfileFallback {

      @Test
      void profile_built_with_no_components_returns_the_five_second_fallback_timeout() {
        // Given
        var emptyProfile = InqTimeoutProfile.builder().build();

        // When
        var timeout = emptyProfile.timeLimiterTimeout();

        // Then
        assertThat(timeout).isEqualTo(Duration.ofSeconds(5));
      }

      @Test
      void all_accessor_methods_on_an_empty_profile_return_duration_zero() {
        // Given
        var emptyProfile = InqTimeoutProfile.builder().build();

        // When / Then
        assertThat(emptyProfile.connectionEstablishmentTimeout()).isEqualTo(Duration.ZERO);
        assertThat(emptyProfile.readInactivityTimeout()).isEqualTo(Duration.ZERO);
        assertThat(emptyProfile.connectionAcquireTimeout()).isEqualTo(Duration.ZERO);
        assertThat(emptyProfile.writeOperationTimeout()).isEqualTo(Duration.ZERO);
        assertThat(emptyProfile.serverResponseTimeout()).isEqualTo(Duration.ZERO);
      }
    }
  }
}
