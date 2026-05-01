/**
 * End-to-end integration tests for the imperative bulkhead.
 *
 * <p>This module exists to pin behaviours that no single module's own test suite covers:
 * the imperative bulkhead exercised through the full pipeline / wrapper / aspect / Spring Boot
 * stack at the same time. Each test class addresses one variant; method names describe one
 * user scenario. Reading the suite top-to-bottom is intended to feel like a tutorial.
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
 *   <li>2.12.4 — strategy construction failure on cold-to-hot, and {@code closeStrategy}
 *       throw on hot-swap.</li>
 *   <li>2.17.3 — wrapper compatibility across the cold/hot/removed transitions.</li>
 *   <li>2.17.4 — wrapper and proxy tests against real bulkheads (post ADR-033).</li>
 *   <li>F-2.18-3 — AspectJ integration against a real bulkhead.</li>
 *   <li>F-2.19-7 — Spring Boot integration against a real bulkhead.</li>
 * </ul>
 */
package eu.inqudium.bulkhead.integration;
