package eu.inqudium.core.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public record InqConfig(
    GeneralConfig general,
    Map<Class<?>, ConfigExtension<?>> extensions
) {
  public static MandatoryStep configure() {
    return new BuilderState();
  }

  public <T extends ConfigExtension<?>> Optional<T> of(Class<T> type) {
    ConfigExtension<?> extension = extensions.get(type);
    return Optional.ofNullable(type.cast(extension));
  }

  public interface MandatoryStep {
    TopicHub general();

    TopicHub general(Consumer<GeneralConfigBuilder> customizer);
  }

  public interface Buildable {
    InqConfig build();
  }

  public interface TopicHub extends Buildable {
    default <B extends ExtensionBuilder<? extends ConfigExtension<?>>> TopicHub with(
        B builderInstance
    ) {
      return with(builderInstance, c -> {
      });
    }

    <B extends ExtensionBuilder<? extends ConfigExtension<?>>> TopicHub with(
        B builderInstance,
        Consumer<B> customizer
    );
  }

  private static class BuilderState implements MandatoryStep, TopicHub {
    private final Map<Class<?>, ConfigExtension<?>> extensions = new HashMap<>();
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

    private TopicHub general(GeneralConfigBuilder builderInstance, Consumer<GeneralConfigBuilder> customizer) {
      Objects.requireNonNull(builderInstance, "Core builder instance must not be null");
      Objects.requireNonNull(customizer, "Core customizer must not be null");

      customizer.accept(builderInstance);
      this.general = builderInstance.build(Collections.unmodifiableMap(extensions));
      return this;
    }

    @Override
    public <B extends ExtensionBuilder<? extends ConfigExtension<?>>> TopicHub with(
        B builderInstance,
        Consumer<B> customizer) {

      Objects.requireNonNull(builderInstance, "Builder instance must not be null");
      Objects.requireNonNull(customizer, "Customizer must not be null");

      builderInstance.general(general);
      customizer.accept(builderInstance);
      ConfigExtension<?> extensionConfig = builderInstance.build();

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