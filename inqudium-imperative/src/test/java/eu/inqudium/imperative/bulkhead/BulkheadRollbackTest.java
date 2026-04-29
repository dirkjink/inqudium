package eu.inqudium.imperative.bulkhead;

import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.BulkheadEventPublishFailureException;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadRollbackTraceEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadWaitTraceEvent;
import eu.inqudium.core.event.InqEvent;
import eu.inqudium.core.event.InqEventConsumer;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.event.InqSubscription;
import eu.inqudium.core.log.LoggerFactory;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Permit-rollback contract for event-publish failures during bulkhead acquire (REFACTORING.md
 * 2.9). The publishes that run while a permit is held — the success-branch
 * {@link BulkheadWaitTraceEvent} and the {@link BulkheadOnAcquireEvent} — must release the
 * permit on a publish failure, optionally emit a {@link BulkheadRollbackTraceEvent}, and
 * surface the original failure as
 * {@link BulkheadEventPublishFailureException} with the cause attached.
 *
 * <p>The default {@code InqEventPublisher} catches subscriber exceptions internally and only
 * re-throws fatal errors, so a normal misbehaving subscriber would never reach the bulkhead's
 * catch. To exercise the rollback path the tests use {@link ScriptedPublisher}, a fake
 * {@code InqEventPublisher} that lets the test decide which event types to throw on and which
 * to capture into a recording list. That mirrors the situation a custom publisher
 * implementation would create — the realistic trigger for this rollback path in production.
 */
@DisplayName("BulkheadHotPhase publish-failure rollback")
class BulkheadRollbackTest {

    private InqBulkhead<String, String> newBulkhead(BulkheadEventConfig events, ScriptedPublisher publisher) {
        BulkheadSnapshot snap = new BulkheadSnapshot(
                "inventory", 5, Duration.ZERO, Set.of(), null, events,
                new SemaphoreStrategyConfig());
        LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snap);
        GeneralSnapshot general = new GeneralSnapshot(
                InqClock.system(),
                InqNanoTimeSource.system(),
                publisher,                          // runtime publisher (not used here)
                (n, t) -> publisher,                // component publisher factory — return the fake
                LoggerFactory.NO_OP_LOGGER_FACTORY,
                true);
        return new InqBulkhead<>(live, general);
    }

    private static <A> InternalExecutor<A, A> identity() {
        return (chainId, callId, arg) -> arg;
    }

    private static <A> InternalExecutor<A, A> trackingIdentity(AtomicInteger calls) {
        return (chainId, callId, arg) -> {
            calls.incrementAndGet();
            return arg;
        };
    }

    @Nested
    @DisplayName("onAcquire publish failure with rollbackTrace=true")
    class OnAcquirePublishFailureWithRollbackTrace {

        @Test
        void should_release_permit_publish_rollback_trace_and_throw_wrapped_exception() {
            // What is to be tested: a publisher that throws on BulkheadOnAcquireEvent triggers
            // (a) permit release, (b) emission of a BulkheadRollbackTraceEvent on the same
            // publisher, (c) BulkheadEventPublishFailureException to the caller carrying the
            // original RuntimeException as cause, and (d) the user lambda must not have run —
            // the bulkhead never delegated to next.execute.
            // Why important: this is the headline contract of REFACTORING.md 2.9 — a defective
            // observability stack must not corrupt the bulkhead's permit accounting nor silently
            // run a downstream call the operator never intended to permit-track.

            // Given
            BulkheadEventConfig events = new BulkheadEventConfig(
                    /* onAcquire */ true,
                    /* onRelease */ false,
                    /* onReject */ false,
                    /* waitTrace */ false,
                    /* rollbackTrace */ true);

            RuntimeException publisherFailure = new RuntimeException("publisher blew up");
            ScriptedPublisher publisher = new ScriptedPublisher()
                    .throwOn(BulkheadOnAcquireEvent.class, publisherFailure);
            InqBulkhead<String, String> bulkhead = newBulkhead(events, publisher);

            AtomicInteger lambdaCalls = new AtomicInteger();

            // When / Then
            assertThatThrownBy(() -> bulkhead.execute(7L, 11L, "x", trackingIdentity(lambdaCalls)))
                    .isInstanceOf(BulkheadEventPublishFailureException.class)
                    .hasCauseReference(publisherFailure)
                    .extracting("failedEventType")
                    .isEqualTo(BulkheadOnAcquireEvent.class.getSimpleName());

            // Permit is released — every permit is available again
            assertThat(bulkhead.availablePermits())
                    .as("permit must have been rolled back")
                    .isEqualTo(5);
            assertThat(bulkhead.concurrentCalls()).isZero();

            // Rollback trace event reached the publisher
            List<BulkheadRollbackTraceEvent> rollbacks =
                    publisher.captured(BulkheadRollbackTraceEvent.class);
            assertThat(rollbacks).hasSize(1);
            BulkheadRollbackTraceEvent rollback = rollbacks.get(0);
            assertThat(rollback.getElementName()).isEqualTo("inventory");
            assertThat(rollback.getChainId()).isEqualTo(7L);
            assertThat(rollback.getCallId()).isEqualTo(11L);
            assertThat(rollback.getErrorType()).isEqualTo(RuntimeException.class.getName());

            // User lambda was never invoked
            assertThat(lambdaCalls.get())
                    .as("downstream lambda must not run when acquire-publish fails")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("onAcquire publish failure with rollbackTrace=false")
    class OnAcquirePublishFailureWithoutRollbackTrace {

        @Test
        void should_release_permit_and_throw_without_emitting_rollback_event() {
            // What is to be tested: with rollbackTrace=false the rollback path still releases
            // the permit and throws, but does not emit a BulkheadRollbackTraceEvent — the flag
            // is honored as the gate it claims to be.
            // Why important: subscribers with rollbackTrace turned off must not suddenly receive
            // rollback events because the bulkhead "thought it would help" — opt-in stays opt-in.

            BulkheadEventConfig events = new BulkheadEventConfig(
                    true, false, false, false, /* rollbackTrace */ false);

            RuntimeException publisherFailure = new RuntimeException("publisher blew up");
            ScriptedPublisher publisher = new ScriptedPublisher()
                    .throwOn(BulkheadOnAcquireEvent.class, publisherFailure);
            InqBulkhead<String, String> bulkhead = newBulkhead(events, publisher);

            assertThatThrownBy(() -> bulkhead.execute(1L, 1L, "x", identity()))
                    .isInstanceOf(BulkheadEventPublishFailureException.class)
                    .hasCauseReference(publisherFailure);

            assertThat(bulkhead.availablePermits()).isEqualTo(5);
            assertThat(publisher.captured(BulkheadRollbackTraceEvent.class))
                    .as("rollbackTrace=false suppresses the rollback event")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("waitTrace publish failure (success branch)")
    class WaitTracePublishFailure {

        @Test
        void should_release_permit_when_waitTrace_publish_throws() {
            // What is to be tested: the success-branch waitTrace publish runs after a permit is
            // held, just like onAcquire. A throwing publish on it must trigger the same
            // rollback discipline.
            // Why important: the protected region is "publishes that run while a permit is
            // held", not just "the onAcquire publish". A regression that only protected one of
            // them would leak permits when a user has waitTrace enabled.

            BulkheadEventConfig events = new BulkheadEventConfig(
                    /* onAcquire */ false,
                    /* onRelease */ false,
                    /* onReject */ false,
                    /* waitTrace */ true,
                    /* rollbackTrace */ true);

            ScriptedPublisher publisher = new ScriptedPublisher()
                    .throwOn(BulkheadWaitTraceEvent.class,
                            new RuntimeException("waitTrace publisher failed"));
            InqBulkhead<String, String> bulkhead = newBulkhead(events, publisher);

            assertThatThrownBy(() -> bulkhead.execute(2L, 3L, "x", identity()))
                    .isInstanceOf(BulkheadEventPublishFailureException.class)
                    .extracting("failedEventType")
                    .isEqualTo(BulkheadWaitTraceEvent.class.getSimpleName());

            assertThat(bulkhead.availablePermits()).isEqualTo(5);
            assertThat(publisher.captured(BulkheadRollbackTraceEvent.class)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("rollback-trace publish itself fails")
    class RollbackPublishItselfFails {

        @Test
        void should_record_secondary_failure_as_suppressed_and_surface_primary() {
            // What is to be tested: when both the onAcquire publish and the rollback-trace
            // publish throw, the bulkhead state is still correct (permit released — release
            // happens before the rollback publish) and the caller receives
            // BulkheadEventPublishFailureException whose cause is the primary failure. The
            // secondary failure shows up as a suppressed exception on the primary, never as
            // the surface exception.
            // Why important: the rollback path must be belt-and-braces robust — a "publisher
            // also broken" scenario should not produce a publish storm or hide what really
            // went wrong.

            BulkheadEventConfig events = new BulkheadEventConfig(
                    true, false, false, false, true);

            RuntimeException primary = new RuntimeException("primary");
            RuntimeException secondary = new RuntimeException("secondary");
            ScriptedPublisher publisher = new ScriptedPublisher()
                    .throwOn(BulkheadOnAcquireEvent.class, primary)
                    .throwOn(BulkheadRollbackTraceEvent.class, secondary);
            InqBulkhead<String, String> bulkhead = newBulkhead(events, publisher);

            assertThatThrownBy(() -> bulkhead.execute(1L, 1L, "x", identity()))
                    .isInstanceOf(BulkheadEventPublishFailureException.class)
                    .hasCauseReference(primary)
                    .satisfies(thrown -> assertThat(thrown.getCause().getSuppressed())
                            .as("secondary failure recorded as suppressed on primary")
                            .containsExactly(secondary));

            assertThat(bulkhead.availablePermits())
                    .as("permit released before the rollback publish ran")
                    .isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("happy path with rollbackTrace flag on")
    class HappyPathRegression {

        @Test
        void should_run_normally_and_emit_no_rollback_event() {
            // What is to be tested: with the rollbackTrace flag on but no failing publish, the
            // bulkhead runs the normal happy path (lambda invoked, OnRelease emitted, no
            // exception) and does not emit a rollback-trace event. Confirms the rollback path
            // is purely conditional on a publish failure — flag-on alone does nothing
            // observable.

            BulkheadEventConfig events = new BulkheadEventConfig(
                    true, true, false, true, /* rollbackTrace */ true);
            ScriptedPublisher publisher = new ScriptedPublisher();
            InqBulkhead<String, String> bulkhead = newBulkhead(events, publisher);

            AtomicInteger calls = new AtomicInteger();
            String result = bulkhead.execute(1L, 1L, "x", trackingIdentity(calls));

            assertThat(result).isEqualTo("x");
            assertThat(calls.get()).isEqualTo(1);
            assertThat(bulkhead.availablePermits()).isEqualTo(5);

            assertThat(publisher.captured(BulkheadRollbackTraceEvent.class))
                    .as("no rollback event on the success path")
                    .isEmpty();
            assertThat(publisher.captured(BulkheadOnReleaseEvent.class))
                    .as("the release event still fires — rollback path uninvolved")
                    .hasSize(1);
        }
    }

    /**
     * Test fixture: a minimal {@link InqEventPublisher} that lets tests script per-event-type
     * publish failures and capture the events it does receive. Unlike the default publisher, it
     * does <em>not</em> swallow exceptions; whatever the script returns travels straight back
     * to the caller. That is exactly what a misbehaving custom publisher would do in
     * production, and it is the only way the bulkhead's rollback path is reachable in a test.
     */
    private static final class ScriptedPublisher implements InqEventPublisher {

        private final List<InqEvent> captured = new ArrayList<>();
        private final List<Function<InqEvent, RuntimeException>> rules = new ArrayList<>();

        ScriptedPublisher throwOn(Class<? extends InqEvent> type, RuntimeException toThrow) {
            rules.add(event -> type.isInstance(event) ? toThrow : null);
            return this;
        }

        @Override
        public void publish(InqEvent event) {
            for (Function<InqEvent, RuntimeException> rule : rules) {
                RuntimeException toThrow = rule.apply(event);
                if (toThrow != null) {
                    throw toThrow;
                }
            }
            captured.add(event);
        }

        <E extends InqEvent> List<E> captured(Class<E> type) {
            List<E> out = new ArrayList<>();
            for (InqEvent e : captured) {
                if (type.isInstance(e)) {
                    out.add(type.cast(e));
                }
            }
            return out;
        }

        @Override
        public <E extends InqEvent> InqSubscription onEvent(Class<E> eventType, Consumer<E> consumer) {
            // Tests don't subscribe through this fake — they read captured() directly.
            throw new UnsupportedOperationException("ScriptedPublisher does not support onEvent");
        }

        @Override
        public <E extends InqEvent> InqSubscription onEvent(
                Class<E> eventType, Consumer<E> consumer, Duration ttl) {
            throw new UnsupportedOperationException("ScriptedPublisher does not support onEvent");
        }

        @Override
        public InqSubscription onEvent(InqEventConsumer consumer) {
            throw new UnsupportedOperationException("ScriptedPublisher does not support onEvent");
        }

        @Override
        public void close() { /* no resources */ }
    }
}
