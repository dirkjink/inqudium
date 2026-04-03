package eu.inqudium.core.pipeline;
/**
 * Ein homogener Wrapper für das Runnable-Interface.
 * Nimmt keine Argumente (Void) und gibt nichts zurück (Void).
 */
public class RunnableWrapper
    extends BaseWrapper<Runnable, Void, Void, RunnableWrapper>
    implements Runnable {

  public RunnableWrapper(String name, Runnable delegate) {
    super(name, delegate);
  }

  @Override
  public void run() {
    initiateChain(null);
  }

  @Override
  protected Void invokeCore(Void argument) {
    getDelegate().run();
    return null; // Java verlangt eine Rückgabe für den generischen Typ Void
  }

  @Override
  protected void handleLayer(String callId, Void argument) {
    // Schichtspezifische Logik mit Zugriff auf die Call-ID
  }
}