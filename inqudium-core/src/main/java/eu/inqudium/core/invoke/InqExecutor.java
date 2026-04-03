package eu.inqudium.core.invoke;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.exception.InqRuntimeException;
import eu.inqudium.core.pipeline.InqPipeline;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

public interface InqExecutor extends InqElement {
  boolean enableExceptionOptimization = false;

  static <R> R executeCallable(Callable<R> callable, String name, InqElementType type, String callId) {
    try {
      return callable.call();
    } catch (RuntimeException re) {
      throw re;
    } catch (Exception e) {
      throw new InqRuntimeException(callId, name, type, e, enableExceptionOptimization);
    }
  }

  static <R> R executeSupplier(Supplier<R> supplier, String name, InqElementType type, String callId) {
    return executeCallable(supplier::get, name, type, callId);
  }

  static <R> R executeInqCall(InqCall<R> inqCall, String name, InqElementType type, String callId) {
    return executeCallable(inqCall::execute, name, type, callId);
  }

  static void executeRunnable(Runnable runnable, String name, InqElementType type, String callId) {
    executeCallable(() -> {
      runnable.run();
      return null;
    }, name, type, callId);
  }


  /**
   * Decorates and immediately executes a callable.
   *
   * <p><strong>Composition of multiple elements is not supported via this method.</strong>
   * Use {@link InqPipeline} to compose elements.
   *
   * @param callable the callable to execute
   * @param <T>      the result type
   * @return the result
   */
  <T> T executeCallable(Callable<T> callable);

  /**
   * Decorates and immediately executes a supplier.
   *
   * <p><strong>Composition of multiple elements is not supported via this method.</strong>
   * Use {@link InqPipeline} to compose elements.
   *
   * @param supplier the supplier to execute
   * @param <T>      the result type
   * @return the result
   */
  <T> T executeSupplier(Supplier<T> supplier);

  /**
   * Decorates and immediately executes a runnable.
   *
   * <p><strong>Composition of multiple elements is not supported via this method.</strong>
   * Use {@link InqPipeline} to compose elements.
   *
   * @param runnable the runnable to execute
   */
  void executeRunnable(Runnable runnable);
}
