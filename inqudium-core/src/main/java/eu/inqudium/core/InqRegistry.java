package eu.inqudium.core;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry contract for named element instances.
 *
 * <p>Thread-safe via {@link ConcurrentHashMap#computeIfAbsent}. First-registration-wins:
 * if a name already exists, the existing instance is returned and the new configuration
 * is ignored (ADR-015).
 *
 * <p>Each element type has its own registry. Registries are not global singletons —
 * the application creates and owns them.
 *
 * @param <E> the element type
 * @param <C> the configuration type
 * @since 0.1.0
 */
public interface InqRegistry<E extends InqElement, C extends InqConfig> {

  /**
   * Returns the element for the given name, creating it with the default
   * configuration if it does not exist.
   *
   * @param name the instance name
   * @return the element instance (existing or newly created)
   */
  E get(String name);

  /**
   * Returns the element for the given name, creating it with the given
   * configuration if it does not exist. If the name already exists, the
   * existing instance is returned and the provided config is ignored.
   *
   * @param name   the instance name
   * @param config the configuration to use if creating a new instance
   * @return the element instance (existing or newly created)
   */
  E get(String name, C config);

  /**
   * Pre-registers an element with a specific configuration.
   *
   * <p>Uses first-registration-wins semantics: if the name already exists,
   * the existing instance is kept and the new config is ignored.
   *
   * @param name   the instance name
   * @param config the configuration
   */
  void register(String name, C config);

  /**
   * Looks up an element by name without creating it.
   *
   * @param name the instance name
   * @return the element, or empty if not registered
   */
  Optional<E> find(String name);

  /**
   * Returns the names of all registered elements.
   *
   * @return an unmodifiable set of names
   */
  Set<String> getAllNames();

  /**
   * Returns all registered elements as an unmodifiable map.
   *
   * @return name → element mapping
   */
  Map<String, E> getAll();

  /**
   * Returns the default configuration used when creating instances on demand.
   *
   * @return the default config
   */
  C getDefaultConfig();
}
