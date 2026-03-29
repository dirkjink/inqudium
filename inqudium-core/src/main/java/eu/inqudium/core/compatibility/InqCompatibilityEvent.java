package eu.inqudium.core.compatibility;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.event.InqEvent;

import java.time.Instant;

/**
 * Emitted at element creation time to document which compatibility flags
 * are active for a specific element instance (ADR-013).
 *
 * <p>Provides an audit trail for post-incident analysis: "Was the new sliding
 * window behavior active when the circuit breaker opened unexpectedly?"
 *
 * @since 0.1.0
 */
public class InqCompatibilityEvent extends InqEvent {

  private final InqFlag flag;
  private final boolean enabled;
  private final String description;

  /**
   * Creates a new compatibility audit event.
   *
   * @param elementName the element instance being configured
   * @param elementType the element type
   * @param flag        the compatibility flag
   * @param enabled     whether the new behavior is active
   * @param timestamp   when the element was created
   */
  public InqCompatibilityEvent(String elementName, InqElementType elementType,
                               InqFlag flag, boolean enabled, Instant timestamp) {
    super("system", elementName, elementType, timestamp);
    this.flag = flag;
    this.enabled = enabled;
    this.description = String.format(java.util.Locale.ROOT, "%s=%s for %s '%s'",
        flag.name(), enabled ? "new behavior" : "legacy behavior",
        elementType, elementName);
  }

  /**
   * Returns the compatibility flag.
   */
  public InqFlag getFlag() {
    return flag;
  }

  /**
   * Returns whether the new behavior is active.
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Returns a human-readable description.
   */
  public String getDescription() {
    return description;
  }
}
