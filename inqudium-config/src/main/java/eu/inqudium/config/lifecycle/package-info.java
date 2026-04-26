/**
 * Component lifecycle and update-propagation contracts.
 *
 * <p>Every live component has a {@link eu.inqudium.config.lifecycle.LifecycleState LifecycleState}
 * (cold or hot). The transition is monotonic, automatic, and component-driven: the first call to
 * {@code execute} flips the component into the hot state. ADR-028 specifies the behaviour;
 * ADR-029 specifies the per-paradigm implementation pattern that realizes it.
 *
 * <p>Updates against a hot component pass through a veto chain: registered
 * {@link eu.inqudium.config.lifecycle.ChangeRequestListener listeners} can reject a patch with a
 * non-blank reason, and the component itself runs an internal mutability check. Cold updates skip
 * the veto chain entirely and apply directly. The veto chain itself is implemented in phase&nbsp;2 of
 * the configuration refactor; phase&nbsp;1 only ships the contract types.
 *
 * <p>The {@link eu.inqudium.config.lifecycle.PostCommitInitializable PostCommitInitializable} hook
 * lets a hot phase perform setup work (subscriptions, scheduled tasks) exactly once, after a
 * successful cold-to-hot CAS but never on a discarded candidate.
 */
package eu.inqudium.config.lifecycle;
