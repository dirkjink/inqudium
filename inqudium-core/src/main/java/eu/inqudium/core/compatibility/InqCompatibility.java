package eu.inqudium.core.compatibility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Resolved compatibility flag state for an element configuration.
 *
 * <p>Flags are resolved from three layers (lowest to highest priority):
 * <ol>
 *   <li>Built-in defaults (all flags {@code false})</li>
 *   <li>ServiceLoader providers ({@link InqCompatibilityOptions}, Comparable-sorted)</li>
 *   <li>Programmatic API (this builder)</li>
 * </ol>
 *
 * <p>Default merge strategy (Strategy B): programmatic flags override ServiceLoader
 * flags per-flag, not wholesale. Use {@link Builder#ignoreServiceLoader()} for
 * Strategy A (full replacement) (ADR-013).
 *
 * @since 0.1.0
 */
public final class InqCompatibility {

    private static final InqCompatibility DEFAULT = new InqCompatibility(Map.of());

    private final Map<InqFlag, Boolean> flags;

    private InqCompatibility(Map<InqFlag, Boolean> flags) {
        var map = new EnumMap<InqFlag, Boolean>(InqFlag.class);
        map.putAll(flags);
        this.flags = Collections.unmodifiableMap(map);
    }

    /**
     * Returns the default compatibility — all flags at their built-in defaults.
     *
     * @return the default instance
     */
    public static InqCompatibility ofDefaults() {
        return DEFAULT;
    }

    /**
     * Creates a compatibility that adopts all new behaviors.
     *
     * @return an instance with all flags set to {@code true}
     */
    public static InqCompatibility adoptAll() {
        var map = new EnumMap<InqFlag, Boolean>(InqFlag.class);
        for (var flag : InqFlag.values()) {
            map.put(flag, true);
        }
        return new InqCompatibility(map);
    }

    /**
     * Creates a compatibility that preserves all old behaviors.
     *
     * @return an instance with all flags set to {@code false}
     */
    public static InqCompatibility preserveAll() {
        var map = new EnumMap<InqFlag, Boolean>(InqFlag.class);
        for (var flag : InqFlag.values()) {
            map.put(flag, false);
        }
        return new InqCompatibility(map);
    }

    /**
     * Creates a new builder for fine-grained flag configuration.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether the given flag is enabled.
     *
     * <p>If the flag was not explicitly set, returns {@code false} (the built-in default).
     *
     * @param flag the flag to check
     * @return {@code true} if the new behavior is enabled
     */
    public boolean isEnabled(InqFlag flag) {
        return flags.getOrDefault(flag, false);
    }

    /**
     * Returns an unmodifiable view of all explicitly configured flags.
     *
     * @return the flag map
     */
    public Map<InqFlag, Boolean> getFlags() {
        return flags;
    }

    /**
     * Builder for {@link InqCompatibility} with three-layer resolution.
     */
    public static final class Builder {

        private final EnumMap<InqFlag, Boolean> programmaticFlags = new EnumMap<>(InqFlag.class);
        private boolean ignoreServiceLoader = false;

        private Builder() {}

        /**
         * Sets a specific flag value.
         *
         * @param flag    the flag to set
         * @param enabled {@code true} for new behavior, {@code false} for old
         * @return this builder
         */
        public Builder flag(InqFlag flag, boolean enabled) {
            programmaticFlags.put(flag, enabled);
            return this;
        }

        /**
         * Ignores all ServiceLoader-discovered providers for this compatibility
         * instance (Strategy A). Only programmatic flags and defaults apply.
         *
         * @return this builder
         */
        public Builder ignoreServiceLoader() {
            this.ignoreServiceLoader = true;
            return this;
        }

        /**
         * Builds the resolved compatibility by merging defaults, ServiceLoader,
         * and programmatic flags.
         *
         * @return the resolved compatibility
         */
        public InqCompatibility build() {
            var resolved = new EnumMap<InqFlag, Boolean>(InqFlag.class);

            // Layer 1: built-in defaults (all false) — implicit via getOrDefault

            // Layer 2: ServiceLoader providers (unless ignored)
            if (!ignoreServiceLoader) {
                for (var entry : loadServiceLoaderFlags().entrySet()) {
                    resolved.put(entry.getKey(), entry.getValue());
                }
            }

            // Layer 3: programmatic flags override per-flag
            resolved.putAll(programmaticFlags);

            return new InqCompatibility(resolved);
        }

        @SuppressWarnings("unchecked")
        private Map<InqFlag, Boolean> loadServiceLoaderFlags() {
            var result = new EnumMap<InqFlag, Boolean>(InqFlag.class);
            var providers = new ArrayList<InqCompatibilityOptions>();

            try {
                var loader = ServiceLoader.load(InqCompatibilityOptions.class);
                for (var provider : loader) {
                    try {
                        providers.add(provider);
                    } catch (Exception e) {
                        org.slf4j.LoggerFactory.getLogger(InqCompatibility.class)
                                .warn(
                                        "Failed to load InqCompatibilityOptions provider: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(InqCompatibility.class)
                        .warn(
                                "ServiceLoader discovery for InqCompatibilityOptions failed: {}", e.getMessage());
            }

            // Sort: Comparable providers first (ascending), then non-Comparable
            var comparable = new ArrayList<InqCompatibilityOptions>();
            var nonComparable = new ArrayList<InqCompatibilityOptions>();
            for (var provider : providers) {
                if (provider instanceof Comparable) {
                    comparable.add(provider);
                } else {
                    nonComparable.add(provider);
                }
            }
            comparable.sort((a, b) -> ((Comparable<InqCompatibilityOptions>) a).compareTo(b));

            // Merge in order — later providers override earlier for the same flag
            for (var provider : comparable) {
                mergeFlags(result, provider);
            }
            for (var provider : nonComparable) {
                mergeFlags(result, provider);
            }

            return result;
        }

        private void mergeFlags(EnumMap<InqFlag, Boolean> target, InqCompatibilityOptions provider) {
            try {
                var flags = provider.flags();
                if (flags != null) {
                    target.putAll(flags);
                }
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(InqCompatibility.class)
                        .warn(
                                "InqCompatibilityOptions.flags() threw: {}", e.getMessage());
            }
        }
    }
}
