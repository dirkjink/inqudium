package eu.inqudium.core.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Validates an {@link InqPipeline} for common anti-patterns in element
 * ordering and composition.
 *
 * <p>Anti-patterns are <strong>not errors</strong> — the pipeline still
 * works, but the ordering may produce unexpected behavior. The validator
 * emits warnings that help developers catch misconfigurations early.</p>
 *
 * <h3>Known anti-patterns</h3>
 * <table>
 *   <tr><th>Pattern</th><th>Problem</th><th>Fix</th></tr>
 *   <tr>
 *     <td>Retry outside CircuitBreaker</td>
 *     <td>CB doesn't see individual retry attempts — failure rate
 *         is based on the final outcome after all retries</td>
 *     <td>Place Retry innermost (standard ordering)</td>
 *   </tr>
 *   <tr>
 *     <td>TimeLimiter inside Retry</td>
 *     <td>Each retry attempt has its own timeout, but total wait
 *         time across all attempts is unbounded</td>
 *     <td>Place TimeLimiter outermost (standard ordering)</td>
 *   </tr>
 *   <tr>
 *     <td>Bulkhead inside Retry</td>
 *     <td>Each retry attempt acquires a new permit — retries can
 *         exhaust bulkhead capacity</td>
 *     <td>Place Bulkhead outside Retry (standard ordering)</td>
 *   </tr>
 *   <tr>
 *     <td>RateLimiter inside Retry</td>
 *     <td>Each retry attempt consumes a rate limit token — retries
 *         drain the token bucket faster than expected</td>
 *     <td>Place RateLimiter outside Retry (standard ordering)</td>
 *   </tr>
 * </table>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * InqPipeline pipeline = InqPipeline.builder()
 *         .shield(retry).shield(circuitBreaker)
 *         .order(PipelineOrdering.resilience4j())
 *         .build();
 *
 * // Validate and log warnings
 * ValidationResult result = PipelineValidator.validate(pipeline);
 * result.warnings().forEach(w -> log.warn("Pipeline: {}", w));
 *
 * // Or fail fast on anti-patterns
 * result.throwIfWarnings();
 * }</pre>
 *
 * <h3>When to validate</h3>
 * <p>Validation is optional and typically done once at startup — not on
 * the hot path. The standard and Resilience4J orderings are designed to
 * avoid these anti-patterns, so validation is most useful for
 * {@code CUSTOM} orderings.</p>
 *
 * @since 0.8.0
 */
public final class PipelineValidator {

    private PipelineValidator() {
    }

    /**
     * Validates the given pipeline for known anti-patterns.
     *
     * @param pipeline the pipeline to validate
     * @return the validation result with any warnings
     */
    public static ValidationResult validate(InqPipeline pipeline) {
        Objects.requireNonNull(pipeline, "Pipeline must not be null");

        if (pipeline.isEmpty() || pipeline.depth() < 2) {
            return ValidationResult.CLEAN;
        }

        List<String> warnings = new ArrayList<>();
        List<InqElement> elements = pipeline.elements();

        checkRetryOutsideCircuitBreaker(elements, warnings);
        checkTimeLimiterInsideRetry(elements, warnings);
        checkBulkheadInsideRetry(elements, warnings);
        checkRateLimiterInsideRetry(elements, warnings);

        return warnings.isEmpty()
                ? ValidationResult.CLEAN
                : new ValidationResult(List.copyOf(warnings));
    }

    // ======================== Anti-pattern checks ========================

    /**
     * Retry outside CircuitBreaker: CB doesn't see individual retry attempts.
     * Expected: CB before RT (lower index = outermost).
     * Anti-pattern: RT before CB.
     */
    private static void checkRetryOutsideCircuitBreaker(
            List<InqElement> elements, List<String> warnings) {
        int retryIndex = indexOf(elements, InqElementType.RETRY);
        int cbIndex = indexOf(elements, InqElementType.CIRCUIT_BREAKER);

        if (retryIndex >= 0 && cbIndex >= 0 && retryIndex < cbIndex) {
            warnings.add(
                    "Retry ('" + elements.get(retryIndex).getName()
                            + "') is outside CircuitBreaker ('"
                            + elements.get(cbIndex).getName()
                            + "'). The circuit breaker will not see individual retry "
                            + "attempts — failure rate is based on the final outcome "
                            + "after all retries. Consider placing Retry innermost "
                            + "(standard ordering).");
        }
    }

    /**
     * TimeLimiter inside Retry: each attempt has its own timeout,
     * but total wait time is unbounded.
     * Expected: TL before RT (outermost).
     * Anti-pattern: TL after RT (inside retry loop).
     */
    private static void checkTimeLimiterInsideRetry(
            List<InqElement> elements, List<String> warnings) {
        int tlIndex = indexOf(elements, InqElementType.TIME_LIMITER);
        int retryIndex = indexOf(elements, InqElementType.RETRY);

        if (tlIndex >= 0 && retryIndex >= 0 && tlIndex > retryIndex) {
            warnings.add(
                    "TimeLimiter ('" + elements.get(tlIndex).getName()
                            + "') is inside Retry ('"
                            + elements.get(retryIndex).getName()
                            + "'). Each retry attempt has its own timeout, but total "
                            + "wait time across all attempts is unbounded. Consider "
                            + "placing TimeLimiter outermost (standard ordering).");
        }
    }

    /**
     * Bulkhead inside Retry: each retry attempt acquires a new permit.
     * Expected: BH before RT (outermost).
     * Anti-pattern: BH after RT.
     */
    private static void checkBulkheadInsideRetry(
            List<InqElement> elements, List<String> warnings) {
        int bhIndex = indexOf(elements, InqElementType.BULKHEAD);
        int retryIndex = indexOf(elements, InqElementType.RETRY);

        if (bhIndex >= 0 && retryIndex >= 0 && bhIndex > retryIndex) {
            warnings.add(
                    "Bulkhead ('" + elements.get(bhIndex).getName()
                            + "') is inside Retry ('"
                            + elements.get(retryIndex).getName()
                            + "'). Each retry attempt acquires a new bulkhead permit — "
                            + "retries can exhaust bulkhead capacity. Consider placing "
                            + "Bulkhead outside Retry (standard ordering).");
        }
    }

    /**
     * RateLimiter inside Retry: each retry consumes a token.
     * Expected: RL before RT.
     * Anti-pattern: RL after RT.
     */
    private static void checkRateLimiterInsideRetry(
            List<InqElement> elements, List<String> warnings) {
        int rlIndex = indexOf(elements, InqElementType.RATE_LIMITER);
        int retryIndex = indexOf(elements, InqElementType.RETRY);

        if (rlIndex >= 0 && retryIndex >= 0 && rlIndex > retryIndex) {
            warnings.add(
                    "RateLimiter ('" + elements.get(rlIndex).getName()
                            + "') is inside Retry ('"
                            + elements.get(retryIndex).getName()
                            + "'). Each retry attempt consumes a rate limit token — "
                            + "retries drain the token bucket faster than expected. "
                            + "Consider placing RateLimiter outside Retry "
                            + "(standard ordering).");
        }
    }

    // ======================== Helper ========================

    private static int indexOf(List<InqElement> elements, InqElementType type) {
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i).getElementType() == type) {
                return i;
            }
        }
        return -1;
    }

    // ======================== Validation result ========================

    /**
     * The result of a pipeline validation.
     *
     * @param warnings the list of warnings (empty if clean)
     */
    public record ValidationResult(List<String> warnings) {

        /** A clean result with no warnings. */
        static final ValidationResult CLEAN = new ValidationResult(List.of());

        /**
         * Returns {@code true} if the pipeline has no anti-pattern warnings.
         */
        public boolean isClean() {
            return warnings.isEmpty();
        }

        /**
         * Returns the number of warnings.
         */
        public int count() {
            return warnings.size();
        }

        /**
         * Throws an {@link IllegalStateException} if there are any warnings.
         * Useful for fail-fast validation at startup.
         *
         * @throws IllegalStateException if the pipeline has anti-pattern warnings
         */
        public void throwIfWarnings() {
            if (!warnings.isEmpty()) {
                throw new IllegalStateException(
                        "Pipeline has " + warnings.size()
                                + " anti-pattern warning(s):\n  - "
                                + String.join("\n  - ", warnings));
            }
        }
    }
}
