package eu.inqudium.core.event;

import eu.inqudium.core.element.InqElementType;

import java.time.Instant;
import java.util.Objects;

/**
 * Emitted when a ServiceLoader provider fails during construction or execution.
 *
 * <p>This event provides an audit trail for provider failures — useful for
 * diagnosing why a context propagator, event exporter, or compatibility options
 * provider is not functioning (ADR-014, Convention 3).
 *
 * @since 0.1.0
 */
public final class InqProviderErrorEvent extends InqEvent {

  private final String code;
  private final String providerClassName;
  private final String spiInterfaceName;
  private final ProviderPhase phase;
  private final String errorMessage;

  /**
   * Creates a new provider error event.
   *
   * @param providerClassName the class name of the failing provider
   * @param spiInterfaceName  the SPI interface the provider implements
   * @param phase             the phase in which the failure occurred
   * @param errorMessage      the error description
   * @param timestamp         when the error occurred
   */
  public InqProviderErrorEvent(String providerClassName, String spiInterfaceName,
                               ProviderPhase phase, String errorMessage, Instant timestamp) {
    super("system", "InqServiceLoader", InqElementType.NO_ELEMENT, timestamp);
    this.providerClassName = Objects.requireNonNull(providerClassName, "providerClassName must not be null");
    this.spiInterfaceName = Objects.requireNonNull(spiInterfaceName, "spiInterfaceName must not be null");
    this.phase = Objects.requireNonNull(phase, "phase must not be null");
    this.errorMessage = Objects.requireNonNull(errorMessage, "errorMessage must not be null");
    this.code = InqElementType.NO_ELEMENT.errorCode(phase.errorIndex());
  }

  /**
   * Returns the structured error code (ADR-021).
   */
  public String getCode() {
    return code;
  }

  /**
   * Returns the fully qualified class name of the failing provider.
   */
  public String getProviderClassName() {
    return providerClassName;
  }

  /**
   * Returns the SPI interface name the provider was supposed to implement.
   */
  public String getSpiInterfaceName() {
    return spiInterfaceName;
  }

  /**
   * Returns the failure phase.
   */
  public ProviderPhase getPhase() {
    return phase;
  }

  /**
   * Returns the error message describing the failure.
   */
  public String getErrorMessage() {
    return errorMessage;
  }

  /**
   * The phase in which a provider failure occurred.
   *
   * <p>Each phase maps to a unique error code suffix via {@link #errorIndex()},
   * ensuring structured, machine-readable error codes (ADR-021).
   *
   * @since 0.2.0
   */
  public enum ProviderPhase {

    /**
     * Failure during provider instantiation (ServiceLoader construction).
     */
    CONSTRUCTION(1),

    /**
     * Failure during provider execution (e.g. exporter.export()).
     */
    EXECUTION(2);

    private final int errorIndex;

    ProviderPhase(int errorIndex) {
      this.errorIndex = errorIndex;
    }

    /**
     * Returns the error code index used with {@link InqElementType#errorCode(int)}.
     */
    public int errorIndex() {
      return errorIndex;
    }
  }
}
