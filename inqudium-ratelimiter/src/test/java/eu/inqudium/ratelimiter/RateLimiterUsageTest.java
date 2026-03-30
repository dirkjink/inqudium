package eu.inqudium.ratelimiter;

import eu.inqudium.core.Invocation;
import eu.inqudium.core.InvocationVarargs;
import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.exception.InqFailure;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.ratelimiter.InqRequestNotPermittedException;
import eu.inqudium.core.ratelimiter.RateLimiterConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

@DisplayName("RateLimiter — User Perspective")
class RateLimiterUsageTest {

  interface ApiClientApi {
    String fetchData(String endpoint);

    String fetchDataFiltered(String endpoint, String filter, int limit, boolean cached);
  }

  static class ApiClient implements ApiClientApi {
    private final AtomicInteger callCount = new AtomicInteger(0);

    @Override
    public String fetchData(String endpoint) {
      callCount.incrementAndGet();
      return "response-from-" + endpoint;
    }

    @Override
    public String fetchDataFiltered(String endpoint, String filter, int limit, boolean cached) {
      callCount.incrementAndGet();
      return String.format("%s?filter=%s&limit=%d&cached=%s", endpoint, filter, limit, cached);
    }

    int getCallCount() {
      return callCount.get();
    }
  }

  @Nested
  @DisplayName("Standalone usage")
  class Standalone {

    @Test
    void should_permit_calls_within_the_rate_limit() {
      // Given
      var client = new ApiClient();
      var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
          .limitForPeriod(5)
          .limitRefreshPeriod(Duration.ofSeconds(1))
          .build());
      Supplier<String> resilientFetch = rl.decorateSupplier(() -> client.fetchData("users"));

      // When
      var r1 = resilientFetch.get();
      var r2 = resilientFetch.get();
      var r3 = resilientFetch.get();

      // Then
      assertThat(r1).isEqualTo("response-from-users");
      assertThat(client.getCallCount()).isEqualTo(3);
    }

    @Test
    void should_reject_calls_that_exceed_the_rate_limit() {
      // Given
      var client = new ApiClient();
      var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
          .limitForPeriod(2)
          .limitRefreshPeriod(Duration.ofSeconds(10))
          .build());
      Supplier<String> resilientFetch = rl.decorateSupplier(() -> client.fetchData("data"));

      // When
      resilientFetch.get();
      resilientFetch.get();

      // Then
      assertThatThrownBy(resilientFetch::get)
          .isInstanceOf(InqRequestNotPermittedException.class)
          .satisfies(ex -> {
            var rlEx = (InqRequestNotPermittedException) ex;
            assertThat(rlEx.getCode()).isEqualTo("INQ-RL-001");
            assertThat(rlEx.getElementName()).isEqualTo("apiGateway");
            assertThat(rlEx.getWaitEstimate()).isPositive();
          });
      assertThat(client.getCallCount()).isEqualTo(2);
    }

    @Test
    void should_allow_catching_rate_limit_denials_via_inq_failure() {
      // Given
      var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
          .limitForPeriod(1)
          .limitRefreshPeriod(Duration.ofSeconds(10))
          .build());
      Supplier<String> resilient = rl.decorateSupplier(() -> "data");
      resilient.get();

      // When
      var handled = new AtomicInteger(0);
      try {
        resilient.get();
      } catch (RuntimeException e) {
        InqFailure.find(e)
            .ifRateLimited(info -> {
              handled.incrementAndGet();
              assertThat(info.getWaitEstimate()).isPositive();
            })
            .orElseThrow();
      }

      // Then
      assertThat(handled).hasValue(1);
    }
  }

  @Nested
  @DisplayName("Standalone invocation usage")
  class StandaloneInvocation {

    @Test
    void should_rate_limit_a_single_argument_invocation_with_different_endpoints() throws Exception {
      // Given
      var client = new ApiClient();
      var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
          .limitForPeriod(5)
          .limitRefreshPeriod(Duration.ofSeconds(1))
          .build());
      Invocation<String, String> resilientFetch =
          rl.decorateInvocation(client::fetchData);

      // When — same wrapper, different endpoints
      var r1 = resilientFetch.invoke("users");
      var r2 = resilientFetch.invoke("orders");
      var r3 = resilientFetch.invoke("products");

      // Then
      assertThat(r1).isEqualTo("response-from-users");
      assertThat(r2).isEqualTo("response-from-orders");
      assertThat(r3).isEqualTo("response-from-products");
      assertThat(client.getCallCount()).isEqualTo(3);
    }

    @Test
    void should_rate_limit_a_four_argument_invocation_via_varargs() throws Exception {
      // Given
      var client = new ApiClient();
      var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
          .limitForPeriod(5)
          .limitRefreshPeriod(Duration.ofSeconds(1))
          .build());
      InvocationVarargs<String> resilientFetch = rl.decorateInvocation(
          (InvocationVarargs<String>) args -> client.fetchDataFiltered(
              (String) args[0], (String) args[1],
              (Integer) args[2], (Boolean) args[3]));

      // When
      var r1 = resilientFetch.invoke("users", "active", 100, true);
      var r2 = resilientFetch.invoke("orders", "pending", 50, false);

      // Then
      assertThat(r1).isEqualTo("users?filter=active&limit=100&cached=true");
      assertThat(r2).isEqualTo("orders?filter=pending&limit=50&cached=false");
    }

    @Test
    void should_reject_invocation_when_rate_limit_is_exceeded() throws Exception {
      // Given
      var client = new ApiClient();
      var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
          .limitForPeriod(1)
          .limitRefreshPeriod(Duration.ofSeconds(10))
          .build());
      Invocation<String, String> resilientFetch =
          rl.decorateInvocation(client::fetchData);

      // When — first call consumes the permit
      resilientFetch.invoke("users");

      // Then — second call with different arg is still rejected
      assertThatThrownBy(() -> resilientFetch.invoke("orders"))
          .isInstanceOf(InqRequestNotPermittedException.class);
      assertThat(client.getCallCount()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Pipeline usage")
  class Pipeline {

    @Test
    void should_rate_limit_a_call_through_the_pipeline() {
      // Given
      var client = new ApiClient();
      var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
          .limitForPeriod(5)
          .limitRefreshPeriod(Duration.ofSeconds(1))
          .build());
      Supplier<String> resilient = InqPipeline.of(() -> client.fetchData("pipeline"))
          .shield(rl)
          .decorate();

      // When / Then
      assertThat(resilient.get()).isEqualTo("response-from-pipeline");
    }

    @Test
    void should_carry_a_pipeline_call_id_on_rate_limit_denial() {
      // Given
      var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
          .limitForPeriod(1)
          .limitRefreshPeriod(Duration.ofSeconds(10))
          .build());
      Supplier<String> resilient = InqPipeline.of(() -> "data")
          .shield(rl)
          .decorate();
      resilient.get();

      // When / Then
      assertThatThrownBy(resilient::get)
          .isInstanceOf(InqRequestNotPermittedException.class)
          .satisfies(ex -> assertThat(((InqException) ex).getCallId()).isNotEqualTo("None"));
    }
  }

  @Nested
  @DisplayName("Pipeline invocation usage")
  class PipelineInvocation {

    @Test
    void should_compose_pipeline_with_single_argument_invocation() throws Exception {
      // Given
      var client = new ApiClient();
      var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
          .limitForPeriod(5)
          .limitRefreshPeriod(Duration.ofSeconds(1))
          .build());

      Invocation<String, String> resilientFetch = endpoint ->
          InqPipeline.of(() -> client.fetchData(endpoint))
              .shield(rl)
              .decorate()
              .get();

      // When
      var r1 = resilientFetch.invoke("users");
      var r2 = resilientFetch.invoke("orders");

      // Then
      assertThat(r1).isEqualTo("response-from-users");
      assertThat(r2).isEqualTo("response-from-orders");
    }

    @Test
    void should_compose_pipeline_with_four_argument_invocation() throws Exception {
      // Given
      var client = new ApiClient();
      var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
          .limitForPeriod(5)
          .limitRefreshPeriod(Duration.ofSeconds(1))
          .build());

      InvocationVarargs<String> resilientFetch = args ->
          InqPipeline.of(() -> client.fetchDataFiltered(
                  (String) args[0], (String) args[1],
                  (Integer) args[2], (Boolean) args[3]))
              .shield(rl)
              .decorate()
              .get();

      // When
      var r1 = resilientFetch.invoke("users", "active", 100, true);
      var r2 = resilientFetch.invoke("orders", "pending", 50, false);

      // Then
      assertThat(r1).isEqualTo("users?filter=active&limit=100&cached=true");
      assertThat(r2).isEqualTo("orders?filter=pending&limit=50&cached=false");
    }
  }

  // ── Pipeline — Proxy pattern ──

  @Nested
  @DisplayName("Pipeline proxy usage")
  class PipelineProxy {

    @Test
    void should_create_a_typed_proxy_that_rate_limits_single_argument_calls() {
      // Given
      var client = new ApiClient();
      var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
          .limitForPeriod(5)
          .limitRefreshPeriod(Duration.ofSeconds(1))
          .build());

      ApiClientApi resilient = InqPipeline.of(client, ApiClientApi.class)
          .shield(rl)
          .decorate();

      // When — call like a normal client with different endpoints
      var r1 = resilient.fetchData("users");
      var r2 = resilient.fetchData("orders");
      var r3 = resilient.fetchData("products");

      // Then
      assertThat(r1).isEqualTo("response-from-users");
      assertThat(r2).isEqualTo("response-from-orders");
      assertThat(r3).isEqualTo("response-from-products");
      assertThat(client.getCallCount()).isEqualTo(3);
    }

    @Test
    void should_create_a_typed_proxy_that_rate_limits_four_argument_calls() {
      // Given
      var client = new ApiClient();
      var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
          .limitForPeriod(5)
          .limitRefreshPeriod(Duration.ofSeconds(1))
          .build());

      ApiClientApi resilient = InqPipeline.of(client, ApiClientApi.class)
          .shield(rl)
          .decorate();

      // When
      var r1 = resilient.fetchDataFiltered("users", "active", 100, true);
      var r2 = resilient.fetchDataFiltered("orders", "pending", 50, false);

      // Then
      assertThat(r1).isEqualTo("users?filter=active&limit=100&cached=true");
      assertThat(r2).isEqualTo("orders?filter=pending&limit=50&cached=false");
    }

    @Test
    void should_reject_proxy_calls_when_rate_limit_exceeded() {
      // Given
      var client = new ApiClient();
      var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
          .limitForPeriod(1)
          .limitRefreshPeriod(Duration.ofSeconds(10))
          .build());

      ApiClientApi resilient = InqPipeline.of(client, ApiClientApi.class)
          .shield(rl)
          .decorate();

      resilient.fetchData("first");

      // When / Then
      assertThatThrownBy(() -> resilient.fetchData("second"))
          .isInstanceOf(InqRequestNotPermittedException.class);
    }
  }

  // ── Event subscription ──

  @Nested
  @DisplayName("Event subscription")
  class Events {

    @Test
    void should_receive_permit_events_on_permitted_calls() {
      // Given
      var client = new ApiClient();
      var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
          .limitForPeriod(5)
          .limitRefreshPeriod(Duration.ofSeconds(1))
          .build());
      var permitEvents = new java.util.ArrayList<eu.inqudium.core.ratelimiter.event.RateLimiterOnPermitEvent>();

      rl.getEventPublisher().onEvent(
          eu.inqudium.core.ratelimiter.event.RateLimiterOnPermitEvent.class,
          permitEvents::add);

      Supplier<String> resilient = rl.decorateSupplier(() -> client.fetchData("users"));

      // When
      resilient.get();
      resilient.get();

      // Then
      assertThat(permitEvents).hasSize(2);
      assertThat(permitEvents.get(0).getRemainingTokens()).isEqualTo(4);
      assertThat(permitEvents.get(1).getRemainingTokens()).isEqualTo(3);
    }

    @Test
    void should_receive_reject_events_when_rate_limit_exceeded() {
      // Given
      var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
          .limitForPeriod(1)
          .limitRefreshPeriod(Duration.ofSeconds(10))
          .build());
      var rejectEvents = new java.util.ArrayList<eu.inqudium.core.ratelimiter.event.RateLimiterOnRejectEvent>();

      rl.getEventPublisher().onEvent(
          eu.inqudium.core.ratelimiter.event.RateLimiterOnRejectEvent.class,
          rejectEvents::add);

      Supplier<String> resilient = rl.decorateSupplier(() -> "data");
      resilient.get();

      // When
      catchThrowable(resilient::get);

      // Then
      assertThat(rejectEvents).hasSize(1);
      assertThat(rejectEvents.get(0).getWaitEstimate()).isPositive();
    }
  }
}
