package eu.inqudium.core.pipeline;

import java.util.concurrent.Callable;

/**
 * Ein homogener Wrapper für das Callable-Interface.
 * Nimmt keine Argumente (Void), gibt aber einen Wert zurück und kann checked Exceptions werfen.
 *
 * @param <V> Der Rückgabetyp des Callables.
 */
public class CallableWrapper<V>
    extends BaseWrapper<Callable<V>, Void, V, CallableWrapper<V>>
    implements Callable<V> {

  public CallableWrapper(String name, Callable<V> delegate) {
    super(name, delegate);
  }

  @Override
  public V call() throws Exception {
    try {
      return initiateChain(null);
    } catch (RuntimeException e) {
      // Entpackt die Exception, falls sie im Core geworfen und gewrappt wurde
      if (e.getCause() instanceof Exception) {
        throw (Exception) e.getCause();
      }
      throw e;
    }
  }

  @Override
  protected V invokeCore(Void argument) {
    try {
      return getDelegate().call();
    } catch (Exception e) {
      // Wickelt die checked Exception für den Transport durch die InternalExecutor-Kette ein
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void handleLayer(String callId, Void argument) {
    // Schichtspezifische Logik mit Zugriff auf die Call-ID
  }
}