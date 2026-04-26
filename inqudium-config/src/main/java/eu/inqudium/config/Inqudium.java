package eu.inqudium.config;

import eu.inqudium.config.dsl.DefaultInqudiumBuilder;
import eu.inqudium.config.dsl.InqudiumBuilder;

/**
 * Top-level entry point for the Inqudium configuration DSL.
 *
 * <pre>{@code
 * InqRuntime runtime = Inqudium.configure()
 *     .general(g -> g.clock(systemClock))
 *     .imperative(im -> im
 *         .bulkhead("inventory", b -> b.balanced().maxConcurrentCalls(15))
 *     )
 *     .build();
 * }</pre>
 *
 * <p>{@link #configure()} returns a fresh builder; multiple builders may exist concurrently and
 * produce independent {@code InqRuntime} instances. The class is final and stateless — the
 * static factory is the only entry point.
 */
public final class Inqudium {

    private Inqudium() {
        // utility class — not instantiable
    }

    /**
     * @return a new top-level builder. Each call returns a fresh, independent builder.
     */
    public static InqudiumBuilder configure() {
        return new DefaultInqudiumBuilder();
    }
}
