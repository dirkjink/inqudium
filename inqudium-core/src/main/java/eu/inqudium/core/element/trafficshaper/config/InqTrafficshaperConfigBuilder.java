package eu.inqudium.core.element.trafficshaper.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.ExtensionBuilder;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqElementCommonConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;

import java.util.Objects;

public abstract class InqTrafficshaperConfigBuilder
    <B extends InqTrafficshaperConfigBuilder<B, E>, E extends ConfigExtension<E>>
    extends ExtensionBuilder<E> {

  private GeneralConfig generalConfig;
  private String name;
  private InqEventPublisher eventPublisher;
  private Boolean enableExceptionOptimization;

  protected InqTrafficshaperConfigBuilder() {
  }

  @Override
  protected void general(GeneralConfig generalConfig) {
    this.generalConfig = generalConfig;
  }

  /**
   * Provides subclasses with read access to the general configuration.
   *
   * @return the general configuration, may be null if not yet injected
   */
  protected GeneralConfig getGeneralConfig() {
    return this.generalConfig;
  }

  protected abstract B self();

  // ──────────────────────────────────────────────────────────────────────────
  // Individual Setters — each guards its own value immediately
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Sets the name of this bulkhead element, used for identification in
   * logging, metrics, and event publishing.
   *
   * @param name the bulkhead name, must not be null or blank
   * @return this builder
   * @throws NullPointerException     if {@code name} is null
   * @throws IllegalArgumentException if {@code name} is blank
   */
  public B name(String name) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    this.name = name;
    return self();
  }

  public InqTrafficshaperConfigBuilder<B, E> enableExceptionOptimization(Boolean enableExceptionOptimization) {
    Objects.requireNonNull(enableExceptionOptimization, "enableExceptionOptimization must not be null");
    this.enableExceptionOptimization = enableExceptionOptimization;
    return this;
  }

  /**
   * Sets a custom event publisher for this bulkhead. If not set, a default
   * publisher is derived from the element name and type during inference.
   *
   * @param eventPublisher the event publisher, or {@code null} for automatic derivation
   * @return this builder
   */
  public B eventPublisher(InqEventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
    return self();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Build helper
  // ──────────────────────────────────────────────────────────────────────────

  protected InqTrafficshaperConfig common() {

    InqElementCommonConfig common =
        new InqElementCommonConfig(name,
            InqElementType.BULKHEAD,
            eventPublisher,
            enableExceptionOptimization);

    InqTrafficshaperConfig config = new InqTrafficshaperConfig(
        this.generalConfig,
        common.inference()
    ).inference();

    validate(config);
    return config;
  }

  /**
   * Validates cross-field constraints on the fully inferred configuration.
   * Individual field constraints are already enforced by each setter.
   */
  private void validate(InqTrafficshaperConfig config) {
    // Detect unconfigured fields — name is required and has no sensible default
    if (config.common() == null || config.common().name() == null) {
      throw new IllegalStateException(
          "name must be set. Call name(...) before building the bulkhead.");
    }
  }
}
