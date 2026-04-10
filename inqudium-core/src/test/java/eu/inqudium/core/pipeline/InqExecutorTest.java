package eu.inqudium.core.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Tests for {@link InqExecutor} default methods in isolation.
 */
@DisplayName("InqExecutor Direct Execution")
class InqExecutorTest {

    /**
     * Creates a pass-through executor that simply forwards to the next step.
     * Verifies that the basic wiring works without any around-advice logic.
     */
    static <A, R> InqExecutor<A, R> passThrough() {
        return (chainId, callId, arg, next) -> next.execute(chainId, callId, arg);
    }

    /**
     * Creates an executor that records events before and after forwarding,
     * verifying that around-advice is actually applied.
     */
    static <A, R> InqExecutor<A, R> tracking(List<String> events) {
        return (chainId, callId, arg, next) -> {
            events.add("before");
            R result = next.execute(chainId, callId, arg);
            events.add("after");
            return result;
        };
    }

    /**
     * Creates an executor that captures the chainId and callId for assertion.
     */
    static <A, R> InqExecutor<A, R> idCapturing(AtomicReference<Long> chainIdRef,
                                                AtomicReference<Long> callIdRef) {
        return (chainId, callId, arg, next) -> {
            chainIdRef.set(chainId);
            callIdRef.set(callId);
            return next.execute(chainId, callId, arg);
        };
    }

    @Nested
    @DisplayName("executeRunnable")
    class ExecuteRunnable {

        @Test
        @DisplayName("should execute the runnable through the around-advice")
        void should_execute_the_runnable_through_the_around_advice() {
            // Given
            InqExecutor<Void, Void> executor = passThrough();
            AtomicBoolean executed = new AtomicBoolean(false);

            // When
            executor.executeRunnable(() -> executed.set(true));

            // Then
            assertThat(executed).isTrue();
        }

        @Test
        @DisplayName("should apply around-advice before and after the runnable")
        void should_apply_around_advice_before_and_after_the_runnable() {
            // Given
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            InqExecutor<Void, Void> executor = tracking(events);

            // When
            executor.executeRunnable(() -> events.add("core"));

            // Then
            assertThat(events).containsExactly("before", "core", "after");
        }

        @Test
        @DisplayName("should propagate RuntimeException from runnable")
        void should_propagate_RuntimeException_from_runnable() {
            // Given
            InqExecutor<Void, Void> executor = passThrough();
            IllegalStateException expected = new IllegalStateException("boom");

            // When / Then
            assertThat(catchThrowable(() -> executor.executeRunnable(() -> {
                throw expected;
            })))
                    .isSameAs(expected);
        }
    }

    @Nested
    @DisplayName("executeSupplier")
    class ExecuteSupplier {

        @Test
        @DisplayName("should execute the supplier and return its result")
        void should_execute_the_supplier_and_return_its_result() {
            // Given
            InqExecutor<Void, String> executor = passThrough();

            // When
            String result = executor.executeSupplier(() -> "hello");

            // Then
            assertThat(result).isEqualTo("hello");
        }

        @Test
        @DisplayName("should apply around-advice before and after the supplier")
        void should_apply_around_advice_before_and_after_the_supplier() {
            // Given
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            InqExecutor<Void, String> executor = tracking(events);

            // When
            String result = executor.executeSupplier(() -> {
                events.add("core");
                return "value";
            });

            // Then
            assertThat(result).isEqualTo("value");
            assertThat(events).containsExactly("before", "core", "after");
        }

        @Test
        @DisplayName("should propagate RuntimeException from supplier")
        void should_propagate_RuntimeException_from_supplier() {
            // Given
            InqExecutor<Void, String> executor = passThrough();

            // When / Then
            assertThat(catchThrowable(() -> executor.executeSupplier(() -> {
                throw new IllegalArgumentException("bad");
            }))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("executeCallable")
    class ExecuteCallable {

        @Test
        @DisplayName("should execute the callable and return its result")
        void should_execute_the_callable_and_return_its_result() throws Exception {
            // Given
            InqExecutor<Void, Integer> executor = passThrough();

            // When
            int result = executor.executeCallable(() -> 42);

            // Then
            assertThat(result).isEqualTo(42);
        }

        @Test
        @DisplayName("should apply around-advice before and after the callable")
        void should_apply_around_advice_before_and_after_the_callable() throws Exception {
            // Given
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            InqExecutor<Void, Integer> executor = tracking(events);

            // When
            int result = executor.executeCallable(() -> {
                events.add("core");
                return 99;
            });

            // Then
            assertThat(result).isEqualTo(99);
            assertThat(events).containsExactly("before", "core", "after");
        }

        @Test
        @DisplayName("should preserve checked exceptions from callable")
        void should_preserve_checked_exceptions_from_callable() {
            // Given
            InqExecutor<Void, String> executor = passThrough();
            IOException expected = new IOException("disk error");

            // When / Then
            assertThat(catchThrowable(() -> executor.executeCallable(() -> {
                throw expected;
            })))
                    .isSameAs(expected);
        }

        @Test
        @DisplayName("should propagate RuntimeException unwrapped from callable")
        void should_propagate_RuntimeException_unwrapped_from_callable() {
            // Given
            InqExecutor<Void, String> executor = passThrough();
            IllegalArgumentException expected = new IllegalArgumentException("bad");

            // When / Then
            assertThat(catchThrowable(() -> executor.executeCallable(() -> {
                throw expected;
            })))
                    .isSameAs(expected);
        }

        @Test
        @DisplayName("should propagate Error unwrapped from callable")
        void should_propagate_Error_unwrapped_from_callable() {
            // Given
            InqExecutor<Void, String> executor = passThrough();
            OutOfMemoryError expected = new OutOfMemoryError("heap");

            // When / Then
            assertThat(catchThrowable(() -> executor.executeCallable(() -> {
                throw expected;
            })))
                    .isSameAs(expected);
        }
    }

    @Nested
    @DisplayName("executeFunction")
    class ExecuteFunction {

        @Test
        @DisplayName("should execute the function with the input and return its result")
        void should_execute_the_function_with_the_input_and_return_its_result() {
            // Given
            InqExecutor<String, Integer> executor = passThrough();

            // When
            int result = executor.executeFunction(String::length, "hello");

            // Then
            assertThat(result).isEqualTo(5);
        }

        @Test
        @DisplayName("should pass the input through the around-advice to the function")
        void should_pass_the_input_through_the_around_advice_to_the_function() {
            // Given
            AtomicReference<String> capturedInput = new AtomicReference<>();
            InqExecutor<String, String> executor = (chainId, callId, arg, next) -> {
                capturedInput.set(arg);
                return next.execute(chainId, callId, arg);
            };

            // When
            String result = executor.executeFunction(String::toUpperCase, "hello");

            // Then
            assertThat(result).isEqualTo("HELLO");
            assertThat(capturedInput.get()).isEqualTo("hello");
        }

        @Test
        @DisplayName("should apply around-advice before and after the function")
        void should_apply_around_advice_before_and_after_the_function() {
            // Given
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            InqExecutor<String, Integer> executor = tracking(events);

            // When
            int result = executor.executeFunction(s -> {
                events.add("core");
                return s.length();
            }, "test");

            // Then
            assertThat(result).isEqualTo(4);
            assertThat(events).containsExactly("before", "core", "after");
        }
    }

    @Nested
    @DisplayName("executeJoinPoint")
    class ExecuteJoinPoint {

        @Test
        @DisplayName("should execute the proxy execution and return its result")
        void should_execute_the_proxy_execution_and_return_its_result() throws Throwable {
            // Given
            InqExecutor<Void, String> executor = passThrough();

            // When
            String result = executor.executeJoinPoint(() -> "aop-result");

            // Then
            assertThat(result).isEqualTo("aop-result");
        }

        @Test
        @DisplayName("should apply around-advice before and after the proxy execution")
        void should_apply_around_advice_before_and_after_the_proxy_execution() throws Throwable {
            // Given
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            InqExecutor<Void, String> executor = tracking(events);

            // When
            String result = executor.executeJoinPoint(() -> {
                events.add("core");
                return "done";
            });

            // Then
            assertThat(result).isEqualTo("done");
            assertThat(events).containsExactly("before", "core", "after");
        }

        @Test
        @DisplayName("should preserve checked throwable from proxy execution")
        void should_preserve_checked_throwable_from_proxy_execution() {
            // Given
            InqExecutor<Void, String> executor = passThrough();
            IOException expected = new IOException("proxy error");

            // When / Then
            assertThat(catchThrowable(() -> executor.executeJoinPoint(() -> {
                throw expected;
            })))
                    .isSameAs(expected);
        }

        @Test
        @DisplayName("should propagate RuntimeException unwrapped from proxy execution")
        void should_propagate_RuntimeException_unwrapped_from_proxy_execution() {
            // Given
            InqExecutor<Void, String> executor = passThrough();
            IllegalStateException expected = new IllegalStateException("proxy-boom");

            // When / Then
            assertThat(catchThrowable(() -> executor.executeJoinPoint(() -> {
                throw expected;
            })))
                    .isSameAs(expected);
        }
    }

    @Nested
    @DisplayName("ID Generation")
    class IdGeneration {

        @Test
        @DisplayName("should provide a positive chain id and call id to the around-advice")
        void should_provide_a_positive_chain_id_and_call_id_to_the_around_advice() {
            // Given
            AtomicReference<Long> chainId = new AtomicReference<>();
            AtomicReference<Long> callId = new AtomicReference<>();
            InqExecutor<Void, String> executor = idCapturing(chainId, callId);

            // When
            executor.executeSupplier(() -> "ok");

            // Then
            assertThat(chainId.get()).isGreaterThan(0L);
            assertThat(callId.get()).isGreaterThan(0L);
        }

        @Test
        @DisplayName("should generate different call ids for consecutive executions")
        void should_generate_different_call_ids_for_consecutive_executions() {
            // Given
            AtomicReference<Long> chainId = new AtomicReference<>();
            AtomicReference<Long> callId = new AtomicReference<>();
            InqExecutor<Void, String> executor = idCapturing(chainId, callId);

            // When
            executor.executeSupplier(() -> "first");
            long firstCallId = callId.get();
            executor.executeSupplier(() -> "second");
            long secondCallId = callId.get();

            // Then
            assertThat(firstCallId).isNotEqualTo(secondCallId);
        }
    }

    @Nested
    @DisplayName("Around-Advice Interaction")
    class AroundAdviceInteraction {

        @Test
        @DisplayName("should allow the around-advice to catch and replace exceptions with a fallback")
        void should_allow_the_around_advice_to_catch_and_replace_exceptions_with_a_fallback() {
            // Given
            InqExecutor<Void, String> executor = (chainId, callId, arg, next) -> {
                try {
                    return next.execute(chainId, callId, arg);
                } catch (Exception e) {
                    return "fallback";
                }
            };

            // When
            String result = executor.executeSupplier(() -> {
                throw new RuntimeException("fail");
            });

            // Then
            assertThat(result).isEqualTo("fallback");
        }

        @Test
        @DisplayName("should allow the around-advice to transform the result")
        void should_allow_the_around_advice_to_transform_the_result() {
            // Given
            InqExecutor<Void, String> executor = (chainId, callId, arg, next) -> {
                String result = next.execute(chainId, callId, arg);
                return result.toUpperCase();
            };

            // When
            String result = executor.executeSupplier(() -> "hello");

            // Then
            assertThat(result).isEqualTo("HELLO");
        }

        @Test
        @DisplayName("should allow the around-advice to skip execution and return directly")
        void should_allow_the_around_advice_to_skip_execution_and_return_directly() {
            // Given
            AtomicBoolean coreInvoked = new AtomicBoolean(false);
            InqExecutor<Void, String> executor = (chainId, callId, arg, next) -> "cached";

            // When
            String result = executor.executeSupplier(() -> {
                coreInvoked.set(true);
                return "from-core";
            });

            // Then
            assertThat(result).isEqualTo("cached");
            assertThat(coreInvoked).isFalse();
        }

        @Test
        @DisplayName("should allow the around-advice to retry on failure")
        void should_allow_the_around_advice_to_retry_on_failure() {
            // Given
            List<Integer> attempts = Collections.synchronizedList(new ArrayList<>());
            InqExecutor<Void, String> executor = (chainId, callId, arg, next) -> {
                for (int i = 0; i < 3; i++) {
                    try {
                        return next.execute(chainId, callId, arg);
                    } catch (RuntimeException e) {
                        if (i == 2) throw e;
                    }
                }
                throw new IllegalStateException("unreachable");
            };

            // When
            String result = executor.executeSupplier(() -> {
                attempts.add(attempts.size() + 1);
                if (attempts.size() < 3) throw new RuntimeException("transient");
                return "success";
            });

            // Then
            assertThat(result).isEqualTo("success");
            assertThat(attempts).hasSize(3);
        }
    }
}
