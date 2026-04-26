/**
 * Live containers — atomic snapshot holders with subscriber dispatch.
 *
 * <p>A {@link eu.inqudium.config.live.LiveContainer LiveContainer&lt;S&gt;} owns an
 * {@code AtomicReference&lt;S&gt;} and a list of subscribers. Reads are a single volatile load.
 * Writes apply a patch via a CAS retry loop (read snapshot, build new snapshot, compare-and-set);
 * on success, every subscriber is notified exactly once. The container is the bridge between the
 * configuration framework and the runtime components: a hot component subscribes to its container
 * and adapts its internal state when a new snapshot is published.
 */
package eu.inqudium.config.live;
