package eu.inqudium.core.pipeline;

import java.util.function.Supplier;
import java.util.function.Supplier;

/**
 * Ein homogener Wrapper für das Supplier-Interface.
 * Da ein Supplier keine Argumente nimmt, ist der Argument-Typ Void.
 *
 * @param <T> Der Rückgabetyp des Suppliers.
 */
public class SupplierWrapper<T>
    extends BaseWrapper<Supplier<T>, Void, T, SupplierWrapper<T>>
    implements Supplier<T> {

  public SupplierWrapper(String name, Supplier<T> delegate) {
    super(name, delegate);
  }

  @Override
  public T get() {
    // Startet die Kette ohne Argument (Void -> null)
    return initiateChain(null);
  }

  @Override
  protected T invokeCore(Void argument) {
    // Führt den tatsächlichen Kern-Supplier aus
    return getDelegate().get();
  }

  @Override
  protected void handleLayer(String callId, Void argument) {
    // Schichtspezifische Logik mit Zugriff auf die Call-ID
  }
}