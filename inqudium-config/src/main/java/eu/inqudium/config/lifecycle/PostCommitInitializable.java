package eu.inqudium.config.lifecycle;

import eu.inqudium.config.live.LiveContainer;

/**
 * Optional hook implemented by hot phases that need exactly-once setup work after a successful
 * cold-to-hot transition.
 *
 * <p>Hot-phase constructors must be side-effect-free, so that candidates discarded under CAS
 * contention leave no dangling state behind (ADR-029). Anything that genuinely needs to run only
 * once — registering a snapshot subscriber, scheduling a periodic task, acquiring an external
 * resource — belongs in {@link #afterCommit(LiveContainer)}. The per-paradigm lifecycle base class
 * invokes the hook between the successful CAS and the first delegated execute call.
 */
public interface PostCommitInitializable {

    /**
     * @param live the component's live container; the hot phase typically subscribes here to
     *             observe future snapshot replacements.
     */
    void afterCommit(LiveContainer<?> live);
}
