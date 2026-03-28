package eu.inqudium.core.compatibility;

/**
 * Enum of behavioral change flags for safe library upgrades.
 *
 * <p>Each flag gates a specific behavioral change. The default is always
 * the old behavior ({@code false}). Developers opt in to new behavior
 * explicitly (ADR-013).
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Introduced: default = {@code false} (old behavior preserved)</li>
 *   <li>Next minor: default flipped to {@code true} (new behavior is standard)</li>
 *   <li>Next major: flag removed (enum constant deleted → compilation error)</li>
 * </ol>
 *
 * @since 0.1.0
 */
public enum InqFlag {

  // No flags defined yet in 0.1.0-SNAPSHOT.
  // Flags will be added as behavioral changes are introduced.
  // Example:
  //
  // /**
  //  * Since: 0.3.0
  //  * Old behavior: Sliding window boundary check uses < (exclusive).
  //  * New behavior:  Sliding window boundary check uses <= (inclusive).
  //  * Default: false (old behavior preserved)
  //  */
  // SLIDING_WINDOW_BOUNDARY_INCLUSIVE
}
