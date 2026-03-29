package eu.inqudium.core.event;

import eu.inqudium.core.InqElementType;

import java.time.Instant;

/**
 * Emitted when a ServiceLoader provider fails during construction or execution.
 *
 * <p>This event provides an audit trail for provider failures — useful for
 * diagnosing why a context propagator, event exporter, or compatibility options
 * provider is not functioning (ADR-014, Convention 3).
 *
 * @since 0.1.0
 */
public class InqProviderErrorEvent extends InqEvent {

    /** ServiceLoader provider failed during construction. */
    public static final String CODE_CONSTRUCTION = InqElementType.NO_ELEMENT.errorCode(1);

    /** ServiceLoader provider failed during execution. */
    public static final String CODE_EXECUTION = InqElementType.NO_ELEMENT.errorCode(2);

    private final String code;
    private final String providerClassName;
    private final String spiInterfaceName;
    private final String errorMessage;
    private final String phase;

    /**
     * Creates a new provider error event.
     *
     * @param providerClassName the class name of the failing provider
     * @param spiInterfaceName  the SPI interface the provider implements
     * @param phase             "construction" or "execution"
     * @param errorMessage      the error description
     * @param timestamp         when the error occurred
     */
    public InqProviderErrorEvent(String providerClassName, String spiInterfaceName,
                                 String phase, String errorMessage, Instant timestamp) {
        super("system", "InqServiceLoader", InqElementType.NO_ELEMENT, timestamp);
        this.code = "construction".equals(phase) ? CODE_CONSTRUCTION : CODE_EXECUTION;
        this.providerClassName = providerClassName;
        this.spiInterfaceName = spiInterfaceName;
        this.phase = phase;
        this.errorMessage = errorMessage;
    }

    /** Returns the structured error code (ADR-021). */
    public String getCode() {
        return code;
    }

    /** Returns the fully qualified class name of the failing provider. */
    public String getProviderClassName() {
        return providerClassName;
    }

    /** Returns the SPI interface name the provider was supposed to implement. */
    public String getSpiInterfaceName() {
        return spiInterfaceName;
    }

    /** Returns the failure phase: "construction" or "execution". */
    public String getPhase() {
        return phase;
    }

    /** Returns the error message describing the failure. */
    public String getErrorMessage() {
        return errorMessage;
    }
}
