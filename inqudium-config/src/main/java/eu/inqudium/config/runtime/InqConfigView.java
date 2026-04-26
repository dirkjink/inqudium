package eu.inqudium.config.runtime;

import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.ComponentSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Cross-paradigm read view onto the configured components in an {@code InqRuntime}.
 *
 * <p>The view is consistent at the moment of each call — every {@link ComponentSnapshot}
 * returned reflects its component's state at the time the stream element was produced. The view
 * is not transactionally consistent across components: an update that lands during iteration may
 * produce a mixture of pre- and post-update snapshots. Code that needs an atomic system snapshot
 * should materialize the stream into a list and read from that.
 *
 * <p>Lookups that disambiguate by paradigm — for example, the same bulkhead name registered in
 * both the imperative and the reactive section — accept a {@link ParadigmTag} alongside the
 * name. The view never returns the live handle; it returns the snapshot. Code that needs the
 * handle goes through the paradigm-specific container ({@link InqRuntime#imperative()}).
 */
public interface InqConfigView {

    /**
     * @return the runtime's general snapshot — a convenience accessor that mirrors
     *         {@link InqRuntime#general()}.
     */
    GeneralSnapshot general();

    /**
     * @return a stream over every configured component snapshot, regardless of paradigm.
     */
    Stream<ComponentSnapshot> all();

    /**
     * @return a stream over every configured bulkhead snapshot, regardless of paradigm.
     */
    Stream<BulkheadSnapshot> bulkheads();

    /**
     * @param name     the bulkhead's name.
     * @param paradigm the paradigm tag disambiguating same-name bulkheads in different
     *                 paradigms.
     * @return the snapshot if one is configured for this {@code (name, paradigm)} key,
     *         otherwise empty.
     */
    Optional<BulkheadSnapshot> findBulkhead(String name, ParadigmTag paradigm);
}
