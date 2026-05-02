/**
 * Library-end-to-end tests for the imperative bulkhead.
 *
 * <p>The tests in this module exercise bulkhead library behaviour end-to-end under
 * realistic conditions. After the example-modules refactor, the surviving content
 * clusters around four themes:
 *
 * <ul>
 *   <li><strong>Concurrency races</strong> — {@code BulkheadConcurrentRemovalAndPatchTest}
 *       interleaves runtime patches and structural removals at the snapshot-listener
 *       seam.</li>
 *   <li><strong>Lifecycle transitions</strong> — {@code BulkheadWrapperLifecycleTest}
 *       pins that decorator wrappers stay correct across cold-to-hot, strategy hot-swap,
 *       and structural removal of the underlying bulkhead.</li>
 *   <li><strong>Wrapper-family compatibility</strong> — {@code BulkheadWrapperFamilyTest}
 *       covers every {@code decorateXxx} factory and every pipeline terminal
 *       ({@code SyncPipelineTerminal}, {@code InqProxyFactory.of(pipeline)},
 *       {@code AspectPipelineTerminal}) against a real {@code InqBulkhead} after
 *       ADR-033's decorator bridge.</li>
 *   <li><strong>Spring Boot integration as a library safety net</strong> — the
 *       {@code BulkheadSpringBoot*Test} triplet pins basic AOP routing plus saturation,
 *       runtime-driven strategy hot-swap observable through the AOP path, and graceful
 *       context shutdown.</li>
 * </ul>
 *
 * <p>Each test class addresses one theme; method names describe one user scenario.
 * Reading the suite top-to-bottom is intended to feel like a tutorial of the library's
 * end-to-end behaviour.
 *
 * <p><strong>These are NOT examples of how to test a user's application.</strong> For
 * application-level test patterns, see the example modules under
 * {@code inqudium-bulkhead-integration/}, each of which exercises a tiny webshop scenario
 * through one integration style (function-based, JDK proxy, AspectJ, Spring Framework,
 * Spring Boot) and tests its own behaviour the way a real application would. The
 * library-end-to-end tests live here so a future reader is not tempted to copy them into
 * their own test suite as a template — they are the library's safety net, not application
 * test patterns.
 *
 * <p>The aspect-pipeline lifecycle scenarios that previously lived here moved to
 * {@code inqudium-bulkhead-integration-aspectj} as part of the example-module refactor:
 * a real AspectJ-woven scenario fits more naturally next to the AspectJ example. What
 * remains in this module on the aspect side is a structural smoke check inside
 * {@code BulkheadWrapperFamilyTest} that exercises {@code AspectPipelineTerminal}
 * without weaving.
 *
 * <p>The module produces no production artifact — it is the test-only counterpart to the
 * existing {@code inqudium-aspect-integration-tests} module, applied to the bulkhead's
 * complete stack. Module-internal collaborators (synthetic strategies, throwing closeables,
 * tiny test-only services) live as static nested types on the test classes that need them.
 *
 * <p>Closes the following carried-forward audit findings:
 * <ul>
 *   <li>2.12.3 — race between {@code markRemoved} and {@code onSnapshotChange} during
 *       hot-swap.</li>
 *   <li>2.17.3 — wrapper compatibility across the cold/hot/removed transitions.</li>
 *   <li>2.17.4 — wrapper and proxy tests against real bulkheads (post ADR-033).</li>
 *   <li>F-2.19-6 — {@code InqShieldAspect} async dispatch through {@code InqBulkhead}.</li>
 *   <li>F-2.19-7 — Spring Boot integration against a real bulkhead.</li>
 * </ul>
 *
 * <p>Two related findings are closed elsewhere and are listed here only for cross-reference:
 * <ul>
 *   <li>2.12.4 ({@code closeStrategy} throw on hot-swap, strategy construction failure on
 *       cold-to-hot) — closed by {@code BulkheadHotPhaseFailureModeTest} in
 *       {@code inqudium-imperative}.</li>
 *   <li>F-2.18-3 (AspectJ integration against a real bulkhead) — closed by
 *       {@code BulkheadAspectLifecycleTest} in
 *       {@code inqudium-bulkhead-integration-aspectj}.</li>
 * </ul>
 */
package eu.inqudium.bulkhead.integration;
