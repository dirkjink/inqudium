package eu.inqudium.core.config;

/**
 * Marker and self-typing interface for all configuration extension types.
 *
 * <h2>Purpose</h2>
 * <p>Every configuration record that participates in the Inqudium extension system
 * implements this interface. It serves two roles:
 * <ol>
 *   <li><strong>Type token:</strong> Extensions are stored in a {@code Map<Class<?>, ConfigExtension<?>>}
 *       inside {@link GeneralConfig} and {@link InqConfig}. Implementing this interface signals
 *       that a record is a valid extension and can be looked up by its concrete class.</li>
 *   <li><strong>Inference hook:</strong> The {@link #inference()} method provides a seam for
 *       applying default values or deriving computed fields before the configuration is
 *       finalized. The default implementation simply returns {@link #self()}, meaning no
 *       inference is needed. Implementations that require defaulting (e.g.,
 *       {@link InqElementCommonConfig}) override this method.</li>
 * </ol>
 *
 * <h2>Self-Type Pattern</h2>
 * <p>The recursive type parameter {@code C extends ConfigExtension<C>} enables the
 * {@link #self()} method to return the concrete type without an unchecked cast at the
 * call site. This pattern is commonly known as the "Curiously Recurring Template Pattern"
 * (CRTP) or "self-type idiom" in Java.
 *
 * @param <C> the concrete configuration type (self-type)
 */
public interface ConfigExtension<C extends ConfigExtension<C>> {

    /**
     * Returns a version of this configuration with all defaults applied and computed
     * fields derived. Called by the framework during assembly, after user-provided
     * values have been set but before the configuration is sealed.
     *
     * <p>The default implementation performs no inference and returns {@link #self()}.
     *
     * @return the inferred configuration; may be a new instance with defaults filled in
     */
    default C inference() {
        return self();
    }

    /**
     * Returns {@code this} typed as the concrete configuration type.
     * Required by the self-type pattern to avoid unchecked casts.
     *
     * @return {@code this}
     */
    C self();
}
