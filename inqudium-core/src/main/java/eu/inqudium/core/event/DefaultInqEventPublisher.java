package eu.inqudium.core.event;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.exception.InqException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Default implementation of {@link InqEventPublisher} that bridges per-element
 * consumers and an {@link InqEventExporterRegistry}.
 *
 * <p>Optimized for read-heavy operations: consumers are stored in an immutable array
 * wrapped in an {@link AtomicReference}. This guarantees lock-free, zero-allocation
 * publishing with optimal CPU cache locality.
 *
 * <h2>Consumer limits</h2>
 * <p>Configurable via {@link InqPublisherConfig}:
 * <ul>
 *   <li><strong>Soft limit</strong> — logs a warning when crossed. No rejection.</li>
 *   <li><strong>Hard limit</strong> — rejects new registrations with
 *       {@link IllegalStateException} when reached.</li>
 * </ul>
 * Both limits are evaluated after expired TTL consumers have been swept.
 *
 * <h2>TTL-based subscriptions</h2>
 * <p>Consumers registered with a {@link Duration} TTL are automatically removed
 * after the specified time by a background {@link InqConsumerExpiryWatchdog}.
 * The watchdog runs on a virtual thread and is started lazily on the first
 * TTL subscription — no overhead if TTL is never used.
 *
 * <p>The {@link #publish(InqEvent)} hot path is completely free of expiry logic.
 * All sweep operations are handled asynchronously by the watchdog and synchronously
 * during {@link #addConsumer} for accurate limit enforcement.
 *
 * <h2>Double registration</h2>
 * <p>Registering the same consumer instance multiple times is allowed and results in
 * the consumer being called once per registration on each event. Each registration
 * receives an independent {@link InqSubscription} for independent cancellation.
 *
 * @since 0.1.0
 */
final class DefaultInqEventPublisher implements InqEventPublisher {

    // Pre-allocated empty array to avoid allocations when resetting to empty
    static final ConsumerEntry[] EMPTY_CONSUMERS = new ConsumerEntry[0];
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultInqEventPublisher.class);
    private final String elementName;
    private final InqElementType elementType;
    private final InqEventExporterRegistry registry;
    private final InqPublisherConfig config;
    private final boolean traceEnabled;

    /**
     * Copy-on-write array holding the local consumers. Array iteration is significantly
     * faster and more cache-friendly than traversing a ConcurrentHashMap.
     */
    private final AtomicReference<ConsumerEntry[]> consumers = new AtomicReference<>(EMPTY_CONSUMERS);

    /**
     * Per-instance subscription ID generator.
     */
    private final AtomicLong subscriptionCounter = new AtomicLong(0);

    /**
     * Lazily initialized watchdog for sweeping expired TTL consumers.
     * Null until the first TTL subscription is registered.
     */
    private final AtomicReference<InqConsumerExpiryWatchdog> watchdog = new AtomicReference<>();

    DefaultInqEventPublisher(String elementName,
                             InqElementType elementType,
                             InqEventExporterRegistry registry,
                             InqPublisherConfig config) {
        this.elementName = Objects.requireNonNull(elementName, "elementName must not be null");
        this.elementType = Objects.requireNonNull(elementType, "elementType must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.traceEnabled = config.traceEnabled();
    }

    // ── Publishing (hot path — no expiry logic) ──

    /**
     * Validates the TTL duration and computes the absolute expiry instant.
     *
     * @param ttl the time-to-live (must be positive and non-null)
     * @return the absolute expiry instant
     */
    private static Instant validateTtlAndComputeExpiry(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive, was: " + ttl);
        }
        return Instant.now().plus(ttl);
    }

    /**
     * Returns a new array with all expired entries removed. If no entries are expired,
     * returns the input array unchanged (no allocation). If all entries are expired,
     * returns the shared {@link #EMPTY_CONSUMERS} sentinel.
     *
     * @param arr the current consumer array
     * @return a cleaned array (same reference if nothing expired)
     */
    static ConsumerEntry[] sweepExpired(ConsumerEntry[] arr, Instant now) {
        int expiredCount = 0;
        for (ConsumerEntry entry : arr) {
            if (entry.isExpired(now)) {
                expiredCount++;
            }
        }

        if (expiredCount == 0) return arr;
        if (expiredCount == arr.length) return EMPTY_CONSUMERS;

        ConsumerEntry[] result = new ConsumerEntry[arr.length - expiredCount];
        int idx = 0;
        for (ConsumerEntry entry : arr) {
            if (!entry.isExpired(now)) {
                result[idx++] = entry;
            }
        }
        return result;
    }

    @Override
    public void publish(InqEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        // Snapshot the array — any concurrent add/remove produces a new array instance,
        // so this iteration is safe without synchronization.
        // No expiry checks here: the background watchdog handles all cleanup asynchronously,
        // keeping the publish path as lean as possible.
        ConsumerEntry[] currentConsumers = consumers.get();

        for (int i = 0; i < currentConsumers.length; i++) {
            ConsumerEntry entry = currentConsumers[i];
            try {
                entry.consumer().accept(event);
            } catch (Throwable t) {
                InqException.rethrowIfFatal(t);
                LOGGER.warn("[{}] Event consumer [{}] threw on event {}",
                        event.getCallId(), entry.description(),
                        event.getClass().getSimpleName(), t);
            }
        }

        // Forward to exporters
        try {
            registry.export(event);
        } catch (Throwable t) {
            InqException.rethrowIfFatal(t);
            LOGGER.warn("[{}] Exporter registry threw on event {}",
                    event.getCallId(), event.getClass().getSimpleName(), t);
        }
    }

    @Override
    public boolean isTraceEnabled() {
        return traceEnabled;
    }

    @Override
    public void publishTrace(Supplier<? extends InqEvent> eventSupplier) {
        if (!this.traceEnabled) {
            return;
        }
        publish(eventSupplier.get());
    }

    // ── Typed subscriptions ──

    @Override
    public <E extends InqEvent> InqSubscription onEvent(Class<E> eventType, Consumer<E> consumer) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(consumer, "consumer must not be null");
        return registerTyped(eventType, consumer, null);
    }

    @Override
    public <E extends InqEvent> InqSubscription onEvent(Class<E> eventType, Consumer<E> consumer,
                                                        Duration ttl) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(consumer, "consumer must not be null");
        Instant expiresAt = validateTtlAndComputeExpiry(ttl);
        InqSubscription subscription = registerTyped(eventType, consumer, expiresAt);
        ensureWatchdogStarted();
        return subscription;
    }

    // ── Lifecycle ──

    @Override
    public void close() {
        InqConsumerExpiryWatchdog current = watchdog.getAndSet(null);
        if (current != null) {
            current.close();
        }
    }

    // ── Internal registration ──

    /**
     * Returns the element name of this publisher. Package-private accessor
     * used by {@link InqConsumerExpiryWatchdog} for thread naming and logging.
     */
    String elementName() {
        return elementName;
    }

    /**
     * Shared registration logic for typed consumers with optional TTL.
     */
    private <E extends InqEvent> InqSubscription registerTyped(Class<E> eventType,
                                                               Consumer<E> consumer,
                                                               Instant expiresAt) {
        // Capture description before wrapping — the wrapper lambda class name is meaningless
        String ttlSuffix = expiresAt != null ? ", TTL expires=" + expiresAt : "";
        String description = "TypedConsumer[eventType=" + eventType.getSimpleName()
                + ", consumer=" + consumer.getClass().getName() + ttlSuffix + "]";

        InqEventConsumer wrapper = event -> {
            if (eventType.isInstance(event)) {
                consumer.accept(eventType.cast(event));
            }
        };

        long subscriptionId = subscriptionCounter.incrementAndGet();
        addConsumer(new ConsumerEntry(subscriptionId, wrapper, description, expiresAt));
        return () -> removeConsumer(subscriptionId);
    }

    // ── Copy-on-write array management ──

    /**
     * Adds a consumer entry using a CAS loop. Before adding, expired entries are
     * swept and consumer limits are checked against the <em>active</em> count.
     *
     * <p>The synchronous sweep during add ensures that expired consumers do not
     * inflate the count and falsely trigger hard limit rejections.
     *
     * @param entry the entry to add
     * @throws IllegalStateException if the hard consumer limit is reached
     */
    private void addConsumer(ConsumerEntry entry) {
        final Instant now = Instant.now();
        while (true) {
            ConsumerEntry[] current = consumers.get();
            // Capture the time once per CAS iteration
            ConsumerEntry[] cleaned = sweepExpired(current, now);

            // Sweep expired entries before limit evaluation — expired consumers
            // must not count towards either threshold.
            int activeCount = cleaned.length;
            // Evaluate soft limit inside the loop so it resets on retry
            boolean softLimitCrossed = activeCount >= config.softLimit();

            // Hard limit check — reject before adding
            if (activeCount >= config.hardLimit()) {
                throw new IllegalStateException(
                        "Publisher '" + elementName + "' (" + elementType + ") has reached the hard " +
                                "consumer limit of " + config.hardLimit() + ". Ensure InqSubscription.cancel() " +
                                "is called when consumers are no longer needed, or increase the limit via " +
                                "InqPublisherConfig.");
            }

            // Build new array from cleaned base + new entry
            ConsumerEntry[] newArr = Arrays.copyOf(cleaned, activeCount + 1);
            newArr[activeCount] = entry;

            // CAS: compare against the original snapshot (not cleaned). If another
            // thread modified consumers in between, CAS fails and we retry with a
            // fresh snapshot — including a fresh sweep.
            if (consumers.compareAndSet(current, newArr)) {
                if (softLimitCrossed) {
                    LOGGER.warn("Publisher '{}' ({}) has reached {} active consumers (soft limit: {}). " +
                                    "Possible subscription leak — ensure InqSubscription.cancel() is called " +
                                    "when consumers are no longer needed.",
                            elementName, elementType, newArr.length, config.softLimit());
                }
                return;
            }
            // CAS failed — retry with fresh snapshot
        }
    }

    private void removeConsumer(long id) {
        consumers.updateAndGet(arr -> {
            int index = -1;
            for (int i = 0; i < arr.length; i++) {
                if (arr[i].id() == id) {
                    index = i;
                    break;
                }
            }

            // Not found or already removed (Idempotent behavior)
            if (index < 0) {
                return arr;
            }

            // If it's the last element, return the shared empty array to avoid allocation
            if (arr.length == 1) {
                return EMPTY_CONSUMERS;
            }

            // Create a new array without the cancelled consumer
            ConsumerEntry[] newArr = new ConsumerEntry[arr.length - 1];
            System.arraycopy(arr, 0, newArr, 0, index);
            System.arraycopy(arr, index + 1, newArr, index, arr.length - index - 1);
            return newArr;
        });
    }

    // ── Expiry sweep (called by watchdog and addConsumer) ──

    /**
     * Performs a single-attempt CAS sweep of expired entries from the consumer array.
     *
     * <p>Called by the {@link InqConsumerExpiryWatchdog} on its virtual thread.
     * Uses a single CAS attempt per invocation — if a concurrent modification
     * caused the CAS to fail, the next watchdog cycle will retry.
     */
    void performExpirySweep() {
        final Instant now = Instant.now();
        ConsumerEntry[] current = consumers.get();
        ConsumerEntry[] cleaned = sweepExpired(current, now);
        if (cleaned != current) {
            if (consumers.compareAndSet(current, cleaned)) {
                int removed = current.length - cleaned.length;
                LOGGER.debug("Expiry watchdog swept {} expired consumer(s) from '{}'",
                        removed, elementName);
            }
            // CAS failure is fine — next cycle retries with a fresh snapshot
        }
    }

    // ── Watchdog lifecycle ──

    /**
     * Lazily starts the expiry watchdog on the first TTL subscription registration.
     *
     * <p>Thread-safe via CAS — if two TTL registrations race, only one watchdog is
     * created. The losing thread's duplicate is discarded without starting its thread.
     */
    private void ensureWatchdogStarted() {
        if (watchdog.get() != null) {
            return;
        }

        var newWatchdog = new InqConsumerExpiryWatchdog(
                this,
                config.expiryCheckInterval(),
                DefaultInqEventPublisher::performExpirySweep);

        if (watchdog.compareAndSet(null, newWatchdog)) {
            // Only start the thread if this thread successfully registered the watchdog
            newWatchdog.startThread();
        }
    }

    // ── Object methods ──

    @Override
    public String toString() {
        final Instant now = Instant.now();
        ConsumerEntry[] current = consumers.get();
        long activeCount = 0;
        for (ConsumerEntry entry : current) {
            if (!entry.isExpired(now)) {
                activeCount++;
            }
        }
        InqConsumerExpiryWatchdog wd = watchdog.get();
        return "InqEventPublisher{" +
                "elementName='" + elementName + '\'' +
                ", elementType=" + elementType +
                ", consumers=" + activeCount +
                (current.length != activeCount ? " (+" + (current.length - activeCount) + " expired)" : "") +
                ", watchdog=" + (wd != null && wd.isRunning() ? "active" : "inactive") +
                ", config=" + config +
                '}';
    }

    // ── Inner types ──

    /**
     * Pairs a subscription ID with its consumer, a human-readable description for
     * diagnostic logging, and an optional expiry instant for TTL-based subscriptions.
     *
     * @param id          unique subscription identifier
     * @param consumer    the actual event consumer
     * @param description human-readable description for log output
     * @param expiresAt   expiry instant, or {@code null} for permanent subscriptions
     */
    record ConsumerEntry(
            long id,
            InqEventConsumer consumer,
            String description,
            Instant expiresAt
    ) {

        /**
         * Returns {@code true} if this entry has a TTL and the TTL has elapsed.
         * Permanent subscriptions (expiresAt == null) never expire.
         */
        boolean isExpired(Instant now) {
            return expiresAt != null && now.isAfter(expiresAt);
        }
    }
}
