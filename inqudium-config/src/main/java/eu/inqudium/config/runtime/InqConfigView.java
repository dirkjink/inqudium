package eu.inqudium.config.runtime;

import eu.inqudium.config.snapshot.ComponentSnapshot;

import java.util.stream.Stream;

/**
 * Cross-paradigm read view onto the configured components in an {@code InqRuntime}.
 *
 * <p>The view is consistent at the moment of each call — every {@link ComponentSnapshot} returned
 * reflects its component's state at the time the stream element was produced. The view is not
 * transactionally consistent across components: an update that lands during iteration may produce
 * a mixture of pre- and post-update snapshots. Code that needs an atomic system snapshot should
 * materialize the stream into a list and read from that.
 *
 * <p>Phase&nbsp;1 of the configuration refactor exposes only the generic {@link #all()} accessor.
 * Phase&nbsp;1.7 expands the view with per-component-type stream accessors
 * ({@code bulkheads()}, {@code circuitBreakers()}, ...). Cross-component diagnostic rules
 * registered today consume only what is currently exposed and gain access to richer queries in
 * subsequent phases.
 */
public interface InqConfigView {

    /**
     * @return a stream over every configured component snapshot, regardless of paradigm. The
     *         stream is finite and reflects the runtime's state at the moment of iteration.
     */
    Stream<ComponentSnapshot> all();
}
