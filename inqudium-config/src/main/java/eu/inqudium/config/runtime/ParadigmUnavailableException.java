package eu.inqudium.config.runtime;

/**
 * Thrown when a paradigm is referenced (via the DSL or via
 * {@code runtime.imperative()} / {@code runtime.reactive()} / ... accessors) but no
 * {@link eu.inqudium.config.spi.ParadigmProvider ParadigmProvider} for it is on the runtime
 * classpath.
 *
 * <p>The exception is a runtime exception because it represents a configuration mismatch
 * (a missing module on the classpath) rather than a recoverable condition. The message names
 * the missing module so the operator's first action — adding a dependency — is unambiguous.
 */
public final class ParadigmUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message the human-readable explanation, naming the missing module.
     */
    public ParadigmUnavailableException(String message) {
        super(message);
    }
}
