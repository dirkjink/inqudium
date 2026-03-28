package eu.inqudium.core.compatibility;

import java.util.Map;

/**
 * SPI for providing compatibility flag defaults without code changes.
 *
 * <p>Discovered via {@link java.util.ServiceLoader} at startup following ADR-014
 * conventions. Multiple providers are merged: {@link Comparable} providers are
 * sorted first (ascending), then non-Comparable in discovery order. Later
 * providers override earlier ones for the same flag.
 *
 * <h2>Example implementation</h2>
 * <pre>{@code
 * public class CompanyDefaults implements InqCompatibilityOptions,
 *                                         Comparable<InqCompatibilityOptions> {
 *     @Override
 *     public Map<InqFlag, Boolean> flags() {
 *         return Map.of(InqFlag.SLIDING_WINDOW_BOUNDARY_INCLUSIVE, true);
 *     }
 *
 *     @Override
 *     public int compareTo(InqCompatibilityOptions other) {
 *         return 0; // lowest priority — applied first
 *     }
 * }
 * }</pre>
 *
 * <p>Register via {@code META-INF/services/eu.inqudium.core.compatibility.InqCompatibilityOptions}.
 *
 * @since 0.1.0
 */
public interface InqCompatibilityOptions {

  /**
   * Returns the flags this provider configures.
   *
   * <p>Only flags explicitly present in the returned map are set.
   * Absent flags retain their defaults. Return an empty map to
   * configure nothing.
   *
   * @return the flag configuration
   */
  Map<InqFlag, Boolean> flags();
}
