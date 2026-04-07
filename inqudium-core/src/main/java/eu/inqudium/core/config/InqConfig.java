package eu.inqudium.core.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Top-level, immutable configuration container for the Inqudium framework.
 *
 * <h2>Purpose</h2>
 * <p>Aggregates the framework-wide {@link GeneralConfig} together with all registered
 * {@link ConfigExtension}s (element-specific configs) into a single, sealed configuration
 * object. Once built, an {@code InqConfig} is fully immutable and thread-safe.
 *
 * <h2>Fluent Assembly DSL</h2>
 * <p>Configuration is assembled through a step-builder pattern that enforces a mandatory
 * ordering:
 * <ol>
 *   <li>{@link #configure()} — entry point, returns a {@link MandatoryStep}.</li>
 *   <li>{@link MandatoryStep#general()} — builds the {@link GeneralConfig} (mandatory).</li>
 *   <li>{@link TopicHub#with(ExtensionBuilder, Consumer)} — registers zero or more
 *       element extensions (optional, repeatable).</li>
 *   <li>{@link Buildable#build()} — produces the final {@link InqConfig}.</li>
 * </ol>
 *
 * <p>Example usage:
 * <pre>
 *   InqConfig config = InqConfig.configure()
 *       .general(g -&gt; g.nanoTimeSource(myTimeSource))
 *       .with(new SlidingWindowConfigBuilder(), b -&gt; b.balanced())
 *       .build();
 * </pre>
 *
 * <h2>Extension Lifecycle</h2>
 * <p>When {@link TopicHub#with(ExtensionBuilder, Consumer)} is called, the framework:
 * <ol>
 *   <li>Injects the already-built {@link GeneralConfig} into the builder via
 *       {@link ExtensionBuilder#general(GeneralConfig)}.</li>
 *   <li>Passes the builder to the user-provided customizer for field configuration.</li>
 *   <li>Calls {@link ExtensionBuilder#build()} and stores the resulting extension
 *       in the internal map, keyed by its concrete class.</li>
 * </ol>
 *
 * @param general    the framework-wide general configuration; never {@code null}
 * @param extensions an unmodifiable map of registered extensions, keyed by concrete class
 * @see GeneralConfig
 * @see ConfigExtension
 * @see ExtensionBuilder
 */
public record InqConfig(
    GeneralConfig general,
    Map<Class<?>, ConfigExtension<?>> extensions
) {

  /**
   * Entry point for the fluent configuration DSL.
   *
   * @return a {@link MandatoryStep} that requires the general configuration to be set first
   */
  public static MandatoryStep configure() {
    return new BuilderState();
  }

  /**
   * Retrieves a registered configuration extension by its concrete class.
   *
   * @param type the concrete class of the desired extension
   * @param <T>  the extension type
   * @return an {@link Optional} containing the extension, or empty if not registered
   */
  public <T extends ConfigExtension<?>> Optional<T> of(Class<T> type) {
    ConfigExtension<?> extension = extensions.get(type);
    return Optional.ofNullable(type.cast(extension));
  }

  /**
   * First step in the builder DSL — forces the caller to configure the
   * {@link GeneralConfig} before anything else.
   */
  public interface MandatoryStep {

    /**
     * Builds the general configuration with all defaults (no customization).
     *
     * @return the {@link TopicHub} for registering extensions
     */
    TopicHub general();

    /**
     * Builds the general configuration with user-provided customizations.
     *
     * @param customizer a consumer that configures the {@link GeneralConfigBuilder}
     * @return the {@link TopicHub} for registering extensions
     */
    TopicHub general(Consumer<GeneralConfigBuilder> customizer);
  }

  /**
   * Terminal step in the builder DSL — produces the final {@link InqConfig}.
   */
  public interface Buildable {

    /**
     * Builds the final, immutable {@link InqConfig}.
     *
     * @return the assembled configuration
     * @throws NullPointerException if the general configuration was not set
     */
    InqConfig build();
  }

  /**
   * Extension registration step in the builder DSL. Allows registering zero or more
   * {@link ConfigExtension}s via their builders, and terminates with {@link #build()}.
   */
  public interface TopicHub extends Buildable {

    /**
     * Registers an extension using its builder with default settings (no customization).
     *
     * @param builderInstance the extension builder
     * @param <B>             the builder type
     * @return this {@link TopicHub} for chaining further extensions
     */
    default <B extends ExtensionBuilder<? extends ConfigExtension<?>>> TopicHub with(
        B builderInstance
    ) {
      return with(builderInstance, c -> {
      });
    }

    /**
     * Registers an extension using its builder with user-provided customizations.
     *
     * <p>The framework injects the {@link GeneralConfig} into the builder before
     * the customizer runs, so the builder can access framework-wide settings.
     *
     * @param builderInstance the extension builder
     * @param customizer      a consumer that configures the builder
     * @param <B>             the builder type
     * @return this {@link TopicHub} for chaining further extensions
     */
    <B extends ExtensionBuilder<? extends ConfigExtension<?>>> TopicHub with(
        B builderInstance,
        Consumer<B> customizer
    );
  }

  /**
   * Internal mutable state that implements all builder DSL interfaces.
   *
   * <p>Collects extensions in a {@link HashMap} during assembly, then wraps it in
   * an unmodifiable map when {@link #build()} is called.
   */
  private static class BuilderState implements MandatoryStep, TopicHub {

    /** Mutable extension registry; made unmodifiable at build time. */
    private final Map<Class<?>, ConfigExtension<?>> extensions = new HashMap<>();

    /** The built general configuration; must be set before any extension or final build. */
    private GeneralConfig general;

    @Override
    public TopicHub general() {
      return general(new GeneralConfigBuilder(), (c) -> {
      });
    }

    @Override
    public TopicHub general(Consumer<GeneralConfigBuilder> customizer) {
      return general(new GeneralConfigBuilder(), customizer);
    }

    /**
     * Internal helper that builds the {@link GeneralConfig} from a builder and customizer.
     * The extension map snapshot is passed to the general config builder so that extensions
     * registered before {@code general()} (if any) are visible — though the typical
     * ordering is general-first.
     */
    private TopicHub general(GeneralConfigBuilder builderInstance, Consumer<GeneralConfigBuilder> customizer) {
      Objects.requireNonNull(builderInstance, "Core builder instance must not be null");
      Objects.requireNonNull(customizer, "Core customizer must not be null");

      customizer.accept(builderInstance);
      this.general = builderInstance.build(Collections.unmodifiableMap(extensions));
      return this;
    }

    /**
     * Registers a single extension: injects the general config, runs the customizer,
     * builds the extension, and stores it keyed by its concrete class.
     */
    @Override
    public <B extends ExtensionBuilder<? extends ConfigExtension<?>>> TopicHub with(
        B builderInstance,
        Consumer<B> customizer) {

      Objects.requireNonNull(builderInstance, "Builder instance must not be null");
      Objects.requireNonNull(customizer, "Customizer must not be null");

      // Inject framework-wide config so the builder can read global settings
      builderInstance.general(general);
      customizer.accept(builderInstance);
      ConfigExtension<?> extensionConfig = builderInstance.build();
      Objects.requireNonNull(extensionConfig, "Extension config must not be null");

      // Store by concrete class for type-safe lookup via InqConfig.of(Class)
      this.extensions.put(extensionConfig.getClass(), extensionConfig);
      return this;
    }

    @Override
    public InqConfig build() {
      Objects.requireNonNull(general, "Core configuration is mandatory");
      return new InqConfig(general, Collections.unmodifiableMap(extensions));
    }
  }
}
