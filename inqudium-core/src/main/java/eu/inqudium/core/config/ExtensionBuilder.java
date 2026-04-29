package eu.inqudium.core.config;

/**
 * Abstract base class for all configuration extension builders.
 *
 * <h2>Purpose</h2>
 * <p>Provides a uniform contract that the framework's assembly process relies on.
 * During configuration assembly ({@link InqConfig.TopicHub#with(ExtensionBuilder, java.util.function.Consumer)}),
 * the framework:
 * <ol>
 *   <li>Injects the already-built {@link GeneralConfig} via {@link #general(GeneralConfig)},
 *       giving the builder access to framework-wide settings (clock, time source, etc.).</li>
 *   <li>Passes the builder to the user-provided customizer lambda for field configuration.</li>
 *   <li>Calls {@link #build()} to produce the final, immutable {@link ConfigExtension} instance.</li>
 * </ol>
 *
 * <h2>Subclass Responsibilities</h2>
 * <ul>
 *   <li>Implement {@link #build()} to validate fields and construct the concrete
 *       {@link ConfigExtension} record.</li>
 *   <li>Optionally override {@link #general(GeneralConfig)} if the builder needs to
 *       read framework-wide settings (e.g., to extract a default time source or clock).</li>
 * </ul>
 *
 * @param <E> the concrete {@link ConfigExtension} type this builder produces
 */
public abstract class ExtensionBuilder<E extends ConfigExtension<E>> {

    /**
     * Receives the framework-wide general configuration. Called by the assembly process
     * <em>before</em> the user customizer and {@link #build()}.
     *
     * <p>The default implementation is a no-op. Subclasses that need access to general
     * settings (e.g., {@link eu.inqudium.core.element.circuitbreaker.config.InqCircuitBreakerConfigBuilder})
     * should override this method and store the reference.
     *
     * @param generalConfig the general configuration; never {@code null} when called by the framework
     */
    protected void general(GeneralConfig generalConfig) {
    }

    /**
     * Builds and returns the final, immutable configuration extension.
     *
     * <p>Implementations should validate all required fields, apply any remaining defaults,
     * and construct the concrete {@link ConfigExtension} record.
     *
     * @return a fully initialized and validated configuration extension; must not be {@code null}
     */
    public abstract E build();
}
