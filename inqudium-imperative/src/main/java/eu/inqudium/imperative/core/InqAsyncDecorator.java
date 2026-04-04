package eu.inqudium.imperative.core;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.ProxyExecution;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Supplier;

public interface InqAsyncDecorator<A, R> extends InqElement, LayerAction<A, R> {

  Future<Void> decorateRunnable(Runnable delegate);

  <T> Supplier<Future<T>> decorateSupplier(Supplier<T> delegate);

  <V> Callable<Future<V>> decorateCallable(Callable<V> delegate);

  Function<A, Future<R>> decorateFunction(Function<A, R> delegate);

  <T> ProxyExecution<Future<T>> decorateJoinPoint(ProxyExecution<T> delegate);
}
