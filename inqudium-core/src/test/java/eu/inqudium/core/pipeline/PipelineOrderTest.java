package eu.inqudium.core.pipeline;

import eu.inqudium.core.InqElementType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PipelineOrder")
class PipelineOrderTest {

  @Nested
  @DisplayName("Inqudium canonical order")
  class InqudiumOrder {

    @Test
    void should_place_cache_outermost_and_retry_innermost() {
      // Given
      var order = PipelineOrder.INQUDIUM;

      // Then
      assertThat(order.positionOf(InqElementType.CACHE)).isZero();
      assertThat(order.positionOf(InqElementType.RETRY)).isEqualTo(5);
    }

    @Test
    void should_place_timelimiter_outside_retry() {
      // Given
      var order = PipelineOrder.INQUDIUM;

      // Then — lower position = outer
      assertThat(order.positionOf(InqElementType.TIME_LIMITER))
          .isLessThan(order.positionOf(InqElementType.RETRY));
    }

    @Test
    void should_place_circuitbreaker_outside_retry() {
      // Given
      var order = PipelineOrder.INQUDIUM;

      // Then
      assertThat(order.positionOf(InqElementType.CIRCUIT_BREAKER))
          .isLessThan(order.positionOf(InqElementType.RETRY));
    }

    @Test
    void should_sort_elements_into_canonical_order() {
      // Given — unsorted array
      var elements = new InqElementType[]{
          InqElementType.RETRY, InqElementType.CACHE,
          InqElementType.CIRCUIT_BREAKER, InqElementType.TIME_LIMITER
      };

      // When
      Arrays.sort(elements, PipelineOrder.INQUDIUM.comparator());

      // Then — canonical order
      assertThat(elements).containsExactly(
          InqElementType.CACHE,
          InqElementType.TIME_LIMITER,
          InqElementType.CIRCUIT_BREAKER,
          InqElementType.RETRY
      );
    }
  }

  @Nested
  @DisplayName("Resilience4J compatible order")
  class R4jOrder {

    @Test
    void should_place_retry_outermost() {
      // Given
      var order = PipelineOrder.RESILIENCE4J;

      // Then
      assertThat(order.positionOf(InqElementType.RETRY)).isZero();
    }

    @Test
    void should_place_timelimiter_inside_circuitbreaker() {
      // Given
      var order = PipelineOrder.RESILIENCE4J;

      // Then
      assertThat(order.positionOf(InqElementType.TIME_LIMITER))
          .isGreaterThan(order.positionOf(InqElementType.CIRCUIT_BREAKER));
    }

    @Test
    void should_deviate_from_inqudium_order_for_retry_position() {
      // Given
      var inq = PipelineOrder.INQUDIUM;
      var r4j = PipelineOrder.RESILIENCE4J;

      // Then — Retry is innermost in INQUDIUM, outermost in R4J
      assertThat(inq.positionOf(InqElementType.RETRY))
          .isGreaterThan(r4j.positionOf(InqElementType.RETRY));
    }
  }

  @Nested
  @DisplayName("Custom order")
  class CustomOrder {

    @Test
    void should_respect_custom_element_sequence() {
      // Given
      var order = PipelineOrder.custom(
          InqElementType.BULKHEAD,
          InqElementType.CIRCUIT_BREAKER,
          InqElementType.RETRY
      );

      // Then
      assertThat(order.positionOf(InqElementType.BULKHEAD)).isZero();
      assertThat(order.positionOf(InqElementType.CIRCUIT_BREAKER)).isEqualTo(1);
      assertThat(order.positionOf(InqElementType.RETRY)).isEqualTo(2);
    }

    @Test
    void should_place_unlisted_elements_at_the_end() {
      // Given — only two elements listed
      var order = PipelineOrder.custom(
          InqElementType.CIRCUIT_BREAKER,
          InqElementType.RETRY
      );

      // Then — CACHE is not listed → MAX_VALUE
      assertThat(order.positionOf(InqElementType.CACHE)).isEqualTo(Integer.MAX_VALUE);
    }
  }
}
