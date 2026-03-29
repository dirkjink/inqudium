package eu.inqudium.core.pipeline;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqCallIdGenerator;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.context.InqContextPropagation;
import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.exception.InqRuntimeException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Fluent API for composing multiple resilience elements into a single
 * decoration chain with explicit, validated ordering (ADR-017).
 *
 * <p>The pipeline generates a callId and wraps the operation in an {@link InqCall}.
 * This call flows through all decorators — each element reads
 * {@code call.callId()} for event correlation. No thread-local, no hidden state.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Supplier<Result> resilient = InqPipeline
 *     .of(() -> service.call())
 *     .order(PipelineOrder.INQUDIUM)
 *     .shield(circuitBreakerDecorator)
 *     .shield(retryDecorator)
 *     .decorate();
 *
 * Result result = resilient.get();
 * }</pre>
 *
 * @since 0.1.0
 */
public final class InqPipeline {

  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(InqPipeline.class);

  private InqPipeline() {
  }

  /**
   * Starts building a pipeline for the given callable.
   *
   * <p>This is the primary factory method — use it for lambdas, method references,
   * and {@link Callable} instances:
   * <pre>{@code
   * // Lambda (most common)
   * InqPipeline.of(() -> service.call())
   *
   * // Method reference
   * InqPipeline.of(service::call)
   *
   * // Callable variable
   * Callable<Payment> op = () -> service.charge(order);
   * InqPipeline.of(op)
   * }</pre>
   *
   * <p>Checked exceptions flow naturally through the decoration chain and are
   * wrapped in {@link InqRuntimeException} at the {@code Supplier} boundary
   * when {@link Builder#decorate()} is invoked.
   *
   * <p>If you have a {@link Supplier} variable, use {@link #ofSupplier(Supplier)}.
   *
   * @param callable the operation to protect
   * @param <T>      the result type
   * @return a new pipeline builder
   */
  public static <T> Builder<T> of(Callable<T> callable) {
    return new Builder<>(Objects.requireNonNull(callable, "callable must not be null"));
  }

  /**
   * Starts building a pipeline for the given supplier.
   *
   * <p>Convenience method for when you already have a {@link Supplier} variable.
   * For lambdas, prefer {@link #of(Callable)} — it handles both checked and
   * unchecked operations without ambiguity.
   * <pre>{@code
   * // Supplier variable
   * Supplier<Payment> op = () -> service.charge(order);
   * InqPipeline.ofSupplier(op)
   * }</pre>
   *
   * @param supplier the operation to protect
   * @param <T>      the result type
   * @return a new pipeline builder
   */
  public static <T> Builder<T> ofSupplier(Supplier<T> supplier) {
    Objects.requireNonNull(supplier, "supplier must not be null");
    return new Builder<>(supplier::get);
  }

  /**
   * Starts building a pipeline proxy for the given service interface.
   *
   * <p>Returns a typed proxy that implements the service interface. Every method
   * invocation on the proxy goes through the resilience pipeline — callId generation,
   * decoration chain, context propagation, boundary wrapping.
   *
   * <pre>{@code
   * // Define your service as an interface
   * interface PaymentService {
   *     Payment charge(String orderId);
   *     Payment chargeDetailed(String orderId, String currency, int amount);
   * }
   *
   * // Create the resilient proxy — decorate once, call like a normal service
   * PaymentService resilient = InqPipeline.of(paymentService, PaymentService.class)
   *     .shield(circuitBreaker)
   *     .shield(retry)
   *     .decorate();
   *
   * // Every method call is pipeline-protected with a fresh callId
   * resilient.charge("order-1");
   * resilient.chargeDetailed("order-2", "EUR", 1000);
   * }</pre>
   *
   * <p>The target must implement the given interface. Only interface methods are
   * decorated — {@code Object} methods ({@code toString}, {@code equals},
   * {@code hashCode}) are delegated directly to the target.
   *
   * @param target        the service instance to protect
   * @param interfaceType the service interface (must be an interface)
   * @param <T>           the service type
   * @return a new proxy builder
   * @throws IllegalArgumentException if interfaceType is not an interface
   * @throws IllegalArgumentException if target does not implement interfaceType
   */
  public static <T> ProxyBuilder<T> of(T target, Class<T> interfaceType) {
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(interfaceType, "interfaceType must not be null");
    if (!interfaceType.isInterface()) {
      throw new IllegalArgumentException(
          interfaceType.getName() + " is not an interface — InqPipeline proxy requires an interface type");
    }
    if (!interfaceType.isInstance(target)) {
      throw new IllegalArgumentException(
          target.getClass().getName() + " does not implement " + interfaceType.getName());
    }
    return new ProxyBuilder<>(target, interfaceType);
  }

  static void validateChain(List<InqDecorator> sorted) {
    int retryPos = -1;
    int cbPos = -1;
    int tlPos = -1;
    int rlPos = -1;

    for (int i = 0; i < sorted.size(); i++) {
      switch (sorted.get(i).getElementType()) {
        case RETRY -> retryPos = i;
        case CIRCUIT_BREAKER -> cbPos = i;
        case TIME_LIMITER -> tlPos = i;
        case RATE_LIMITER -> rlPos = i;
        default -> {
        }
      }
    }

    if (retryPos >= 0 && cbPos >= 0 && retryPos < cbPos) {
      LOGGER.warn(
          "Pipeline warning: Retry '{}' is outside CircuitBreaker '{}'. " +
              "Retry may attempt to retry against an open circuit breaker. " +
              "Consider configuring Retry to not retry on InqCallNotPermittedException, " +
              "or move Retry inside CircuitBreaker.",
          sorted.get(retryPos).getName(), sorted.get(cbPos).getName());
    }

    if (tlPos >= 0 && retryPos >= 0 && tlPos > retryPos) {
      LOGGER.warn(
          "Pipeline warning: TimeLimiter '{}' is inside Retry '{}'. " +
              "Each retry attempt gets a fresh timeout, but total caller wait time is unbounded.",
          sorted.get(tlPos).getName(), sorted.get(retryPos).getName());
    }

    if (rlPos >= 0 && retryPos >= 0 && rlPos > retryPos) {
      LOGGER.warn(
          "Pipeline warning: RateLimiter '{}' is inside Retry '{}'. " +
              "Each retry attempt consumes a rate limit permit. " +
              "Consider moving RateLimiter outside Retry.",
          sorted.get(rlPos).getName(), sorted.get(retryPos).getName());
    }
  }

  /**
   * Pipeline builder that collects decorators and composes them.
   *
   * @param <T> the result type of the protected operation
   */
  public static final class Builder<T> {

    private final Callable<T> callable;
    private final List<InqDecorator> decorators = new ArrayList<>();
    private PipelineOrder order = PipelineOrder.INQUDIUM;
    private InqCallIdGenerator callIdGenerator = InqCallIdGenerator.uuid();

    private Builder(Callable<T> callable) {
      this.callable = callable;
    }

    /**
     * Adds a resilience element to the pipeline.
     *
     * @param decorator the element's decorator
     * @return this builder
     */
    public Builder<T> shield(InqDecorator decorator) {
      Objects.requireNonNull(decorator, "decorator must not be null");
      decorators.add(decorator);
      return this;
    }

    /**
     * Sets the pipeline ordering strategy.
     *
     * @param order the ordering to use (default: {@link PipelineOrder#INQUDIUM})
     * @return this builder
     */
    public Builder<T> order(PipelineOrder order) {
      this.order = Objects.requireNonNull(order, "order must not be null");
      return this;
    }

    /**
     * Sets the call ID generator for this pipeline.
     *
     * @param callIdGenerator the generator to use (default: UUID)
     * @return this builder
     */
    public Builder<T> callIdGenerator(InqCallIdGenerator callIdGenerator) {
      this.callIdGenerator = Objects.requireNonNull(callIdGenerator, "callIdGenerator must not be null");
      return this;
    }

    /**
     * Composes all elements into a single decorated supplier.
     *
     * <p>Elements are sorted according to the selected order. On each invocation,
     * the pipeline generates a callId, wraps the supplier in an {@link InqCall},
     * and passes it through the decoration chain. Context propagation is activated
     * around the outermost call.
     *
     * <p>The returned supplier also implements {@link InqPipelineProxy} for
     * runtime introspection of the pipeline composition:
     * <pre>{@code
     * Supplier<r> resilient = InqPipeline.of(() -> service.call())
     *     .shield(cb).decorate();
     *
     * if (resilient instanceof InqPipelineProxy proxy) {
     *     proxy.getPipelineInfo().toChainDescription();
     * }
     * }</pre>
     *
     * @return the decorated supplier (also implements {@link InqPipelineProxy})
     */
    public Supplier<T> decorate() {
      var sorted = new ArrayList<>(decorators);
      sorted.sort(Comparator.comparingInt(d -> order.positionOf(d.getElementType())));

      validate(sorted);

      var chain = List.copyOf(sorted);
      final InqCallIdGenerator gen = callIdGenerator;
      final Callable<T> originalCallable = callable;

      Supplier<T> delegate = () -> {
        var callId = gen.generate();

        InqCall<T> call = InqCall.of(callId, originalCallable);
        for (int i = chain.size() - 1; i >= 0; i--) {
          call = chain.get(i).decorate(call);
        }

        final InqCall<T> outermost = call;
        try (var ctxScope = InqContextPropagation.activateFor(
            callId, "pipeline", InqElementType.NO_ELEMENT)) {
          return outermost.execute();
        } catch (InqException ie) {
          throw ie;
        } catch (RuntimeException re) {
          LOGGER.error("[{}] pipeline: {}", callId, re.toString());
          throw re;
        } catch (Exception e) {
          LOGGER.error("[{}] pipeline: {}", callId, e.toString());
          throw new InqRuntimeException(callId, "pipeline", InqElementType.NO_ELEMENT, e);
        }
      };

      var info = new InqPipelineInfo(chain, order, gen, null, null);
      return new InqDecoratedSupplier<>(delegate, info);
    }

    private void validate(List<InqDecorator> sorted) {
      validateChain(sorted);
    }
  }

  // ── Shared validation logic ──

  /**
   * Proxy builder that creates a typed resilience proxy for a service interface.
   *
   * <p>The proxy implements the given interface and routes every method call
   * through the decoration chain. {@code Object} methods ({@code toString},
   * {@code equals}, {@code hashCode}) are delegated directly to the target.
   *
   * @param <T> the service interface type
   */
  public static final class ProxyBuilder<T> {

    private static final Method OBJECT_EQUALS;
    private static final Method OBJECT_HASHCODE;
    private static final Method OBJECT_TOSTRING;
    private static final Method GET_PIPELINE_INFO;

    static {
      try {
        OBJECT_EQUALS = Object.class.getMethod("equals", Object.class);
        OBJECT_HASHCODE = Object.class.getMethod("hashCode");
        OBJECT_TOSTRING = Object.class.getMethod("toString");
        GET_PIPELINE_INFO = InqPipelineProxy.class.getMethod("getPipelineInfo");
      } catch (NoSuchMethodException e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    private final T target;
    private final Class<T> interfaceType;
    private final List<InqDecorator> decorators = new ArrayList<>();
    private PipelineOrder order = PipelineOrder.INQUDIUM;
    private InqCallIdGenerator callIdGenerator = InqCallIdGenerator.uuid();

    private ProxyBuilder(T target, Class<T> interfaceType) {
      this.target = target;
      this.interfaceType = interfaceType;
    }

    /**
     * Adds a resilience element to the pipeline.
     *
     * @param decorator the element's decorator
     * @return this builder
     */
    public ProxyBuilder<T> shield(InqDecorator decorator) {
      Objects.requireNonNull(decorator, "decorator must not be null");
      decorators.add(decorator);
      return this;
    }

    /**
     * Sets the pipeline ordering strategy.
     *
     * @param order the ordering to use (default: {@link PipelineOrder#INQUDIUM})
     * @return this builder
     */
    public ProxyBuilder<T> order(PipelineOrder order) {
      this.order = Objects.requireNonNull(order, "order must not be null");
      return this;
    }

    /**
     * Sets the call ID generator for this pipeline proxy.
     *
     * @param callIdGenerator the generator to use (default: UUID)
     * @return this builder
     */
    public ProxyBuilder<T> callIdGenerator(InqCallIdGenerator callIdGenerator) {
      this.callIdGenerator = Objects.requireNonNull(callIdGenerator, "callIdGenerator must not be null");
      return this;
    }

    /**
     * Creates the resilience proxy.
     *
     * <p>The returned proxy implements both the service interface and
     * {@link InqPipelineProxy} for runtime introspection. Every method
     * call on the proxy goes through the decoration chain:
     * <ol>
     *   <li>A fresh callId is generated</li>
     *   <li>The method call is wrapped as a {@link Callable} in an {@link InqCall}</li>
     *   <li>All decorators are applied in the configured order</li>
     *   <li>Context propagation is activated</li>
     *   <li>The outermost call is executed</li>
     *   <li>Checked exceptions are wrapped in {@link InqRuntimeException}</li>
     * </ol>
     *
     * @return the resilience proxy (also implements {@link InqPipelineProxy})
     */
    @SuppressWarnings("unchecked")
    public T decorate() {
      var sorted = new ArrayList<>(decorators);
      sorted.sort(Comparator.comparingInt(d -> order.positionOf(d.getElementType())));

      validateChain(sorted);

      var chain = List.copyOf(sorted);
      final InqCallIdGenerator gen = callIdGenerator;
      final T proxyTarget = target;
      final var info = new InqPipelineInfo(chain, order, gen, interfaceType, proxyTarget);

      return (T) Proxy.newProxyInstance(
          interfaceType.getClassLoader(),
          new Class<?>[]{interfaceType, InqPipelineProxy.class},
          (proxy, method, args) -> {

            // InqPipelineProxy.getPipelineInfo() — return metadata
            if (GET_PIPELINE_INFO.equals(method)) {
              return info;
            }

            // Object methods — delegate directly, no resilience overhead
            if (OBJECT_EQUALS.equals(method)) {
              return proxyTarget.equals(args[0]);
            }
            if (OBJECT_HASHCODE.equals(method)) {
              return proxyTarget.hashCode();
            }
            if (OBJECT_TOSTRING.equals(method)) {
              return "InqPipeline.proxy[" + interfaceType.getSimpleName() + "]{"
                  + proxyTarget.toString() + "}";
            }

            // Resilience path — full pipeline for every interface method
            var callId = gen.generate();

            Callable<Object> callable = () -> {
              try {
                method.setAccessible(true);
                return method.invoke(proxyTarget, args);
              } catch (InvocationTargetException ite) {
                throw ite.getCause() instanceof Exception ex
                    ? ex : new RuntimeException(ite.getCause());
              }
            };

            InqCall<Object> call = InqCall.of(callId, callable);
            for (int i = chain.size() - 1; i >= 0; i--) {
              call = chain.get(i).decorate(call);
            }

            final InqCall<Object> outermost = call;
            try (var ctxScope = InqContextPropagation.activateFor(
                callId, "pipeline-proxy", InqElementType.NO_ELEMENT)) {
              return outermost.execute();
            } catch (InqException ie) {
              throw ie;
            } catch (RuntimeException re) {
              LOGGER.error("[{}] pipeline-proxy {}.{}: {}",
                  callId, interfaceType.getSimpleName(), method.getName(), re.toString());
              throw re;
            } catch (Exception e) {
              LOGGER.error("[{}] pipeline-proxy {}.{}: {}",
                  callId, interfaceType.getSimpleName(), method.getName(), e.toString());
              throw new InqRuntimeException(
                  callId, "pipeline-proxy", InqElementType.NO_ELEMENT, e);
            }
          });
    }
  }
}
