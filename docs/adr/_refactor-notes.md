# Bericht: Inkonsistenzen zwischen ADRs und Code

**Stand:** 22. April 2026
**Maßgeblich:** Der bestehende Code in `/inqudium-core/src/main/java/eu/inqudium/core` sowie die Paradigmen-Module (`inqudium-imperative`, ...). Die ADRs müssen diesem folgen.

---

## 1. Kritische strukturelle Inkonsistenzen

Diese Punkte sind fundamentale Architekturabweichungen — die ADRs beschreiben einen komplett anderen Code als tatsächlich existiert.

### 1.1 ADR-022 — Call-ID-Typ: `String` vs. `long`

**ADR sagt:** `InqCall<T>` ist ein Record mit `String callId` (UUID-basiert via `InqCallIdGenerator.generate()`).

**Code sagt:**

- `InqEvent.callId` ist `long` (monoton steigend).
- `InqException.callId` ist `long`.
- Pipeline-Ausführung läuft über `InternalExecutor.execute(long chainId, long callId, A argument, InternalExecutor<A,R> next)` — **zwei** ID-Felder, beide `long`.
- Die Call-ID wird von einem `LongSupplier` aus `PipelineIds.newInstanceCallIdSource()` erzeugt — kein UUID, kein String.
- `chainId` existiert als eigenständiges Feld in `InqEvent` und `InqException`, wird in ADR-022 gar nicht erwähnt.

**Zusätzlich:** Der Kommentar in `ImperativeBulkhead.execute(...)` lügt sogar über den eigenen Code:
> "The {@code chainId} and {@code callId} are converted to {@code String} for event correlation"

Der `InqEvent`-Record erwartet aber `long chainId, long callId` — es gibt keine Konvertierung.

**Empfehlung für ADR-022:** Komplett überarbeiten. Das Design mit `InqCall<T>` record + String UUID wurde durch eine `chainId/callId`-Struktur mit `long`-Werten und einem `InternalExecutor`-Interface ersetzt.

---

### 1.2 ADR-016 — Sliding-Window-Design vs. FailureMetrics-Strategien

**ADR sagt:**

- Interface `SlidingWindow` mit Methoden `WindowSnapshot record(CallOutcome outcome)`, `WindowSnapshot snapshot()`, `reset()`.
- Zwei Implementierungen: count-based und time-based.
- `CircuitBreakerConfig` mit `failureRateThreshold` (Prozent), `slidingWindowType`, `slidingWindowSize`, `minimumNumberOfCalls`, `slowCallDurationThreshold`, `slowCallRateThreshold`.
- Clock-Injektion via `InqClock` (`Instant`-basiert).
- Slow-call-Tracking als integraler Bestandteil.

**Code sagt:**

- Zentrales Interface heißt `FailureMetrics` mit `recordSuccess(long nowNanos)` / `recordFailure(long nowNanos)` / `isThresholdReached(long)` / `reset(long)`.
- Mindestens vier Implementierungen: `SlidingWindowMetrics`, `TimeBasedSlidingWindowMetrics`, `ConsecutiveFailuresMetrics`, `GradualDecayMetrics` (ADR nennt nur zwei).
- Konfiguration als **Extension-Records**: `SlidingWindowConfig`, `TimeBasedSlidingWindowConfig`, `TimeBasedErrorRateConfig` etc. — keine zentrale `CircuitBreakerConfig` mit allen Parametern.
- Schwellwert ist `maxFailuresInWindow` (absolute Zahl), nicht `failureRateThreshold` (Prozent).
- Time-Injektion via `InqNanoTimeSource` (`long` nanos), nicht via `InqClock`.
- Slow-call-Tracking taucht im `FailureMetrics`-Interface überhaupt nicht auf.

**Empfehlung:** ADR-016 ist faktisch obsolet. Neu schreiben mit dem tatsächlichen Strategie-Interface, allen Implementierungen, der Extension-basierten Konfiguration und `InqNanoTimeSource` als Haupttime-Source.

---

### 1.3 ADR-017 + ADR-021 — Fehlendes CACHE-Element, unerwähnter TRAFFIC_SHAPER

**ADR-021 Element-Symbol-Tabelle sagt:**

| Symbol | Element       | Enum-Konstante    |
|--------|---------------|-------------------|
| CB, RT, RL, BH, TL | Circuit Breaker, Retry, Rate Limiter, Bulkhead, Time Limiter | (passend) |
| **CA** | **Cache**     | **`CACHE`**       |
| XX     | No element    | `NO_ELEMENT`      |

**ADR-017 und ADR-024** referenzieren Cache als externes Interceptor-Element mit Priorität.

**Code sagt** (`InqElementType` Enum):

```java
CIRCUIT_BREAKER("CB", 500),
RETRY("RT", 600),
RATE_LIMITER("RL", 300),
BULKHEAD("BH", 400),
TIME_LIMITER("TL", 100),
TRAFFIC_SHAPER("TS", 200),   // ← In KEINER ADR auf der Symbolliste!
NO_ELEMENT("XX", 0);
```

Und im Test `InqElementTypeTest`:

```java
assertThat(values).hasSize(7);   // genau 7, kein CACHE
```

**Konsequenzen:**

- `CACHE` und das Symbol `CA` existieren im Code **nicht** — weder als Enum-Konstante, noch als Package, noch als Implementierung.
- `TRAFFIC_SHAPER` existiert als vollwertiges Element (Package `eu.inqudium.core.element.trafficshaper`, inkl. Config, DSL, Events, SVG-Architektur-Diagramm) — wird aber in **keinem** zentralen ADR als Element geführt.
- Der Javadoc von `InqElementType` selbst widerspricht dem Code: Er beschreibt `CACHE` als existierend ("retains its symbol and error codes but has a `defaultPipelineOrder()` of 0") — das Enum hat aber keinen solchen Wert.
- ADR-024 ist als **Proposed** markiert und referenziert ein Element, das nicht existiert.

**Empfehlung:**

- ADR-021 Symbol-Tabelle: `CA/CACHE` streichen, `TS/TRAFFIC_SHAPER` aufnehmen.
- ADR-017: Das `[Cache]`-Kästchen in beiden kanonischen Ordnungen entfernen oder in einen separaten „externe Interceptors"-Abschnitt verschieben, in dem Cache rein hypothetisch ist.
- ADR-024 in `Status: Deferred` oder auf das Streichen von Cache zurückstutzen, bis das Element tatsächlich implementiert ist.
- Neues ADR für TRAFFIC_SHAPER erstellen (Rechtfertigung, Design, Defaults, Tests).

---

### 1.4 ADR-002 + ADR-017 — Pipeline-API: `InqPipeline.of(...).shield(...).decorate()` vs. `InqPipeline.builder().shield(...).build()`

**ADR-002 und ADR-017 sagen:**

```java
Supplier<R> resilient = InqPipeline
    .of(() -> service.call())
    .shield(circuitBreaker)
    .shield(retry)
    .decorate();
```

**Code sagt** (`InqPipeline.java`):

```java
InqPipeline pipeline = InqPipeline.builder()
    .shield(circuitBreaker)
    .shield(retry)
    .build();

// Ausführung getrennt:
Object result = SyncPipelineTerminal.of(pipeline).execute(() -> "...");
```

Unterschiede:

- Kein `of(Supplier)`-Einstieg — die Pipeline wird ohne Supplier gebaut.
- Factory ist `builder()`, Abschluss ist `build()` — nicht `decorate()`.
- Die eigentliche Ausführung erfolgt über separate Terminals (`SyncPipelineTerminal`, `AsyncResolvedPipeline`, `HybridAspectPipelineTerminal`).
- Es gibt zusätzliche Methoden wie `shieldAll(InqElement...)`, `order(PipelineOrdering)` — in den ADRs nicht dokumentiert.

**Auch die Ordering-API weicht ab:**

| ADR-017                              | Code                                    |
|--------------------------------------|-----------------------------------------|
| `PipelineOrder.INQUDIUM`             | `PipelineOrdering.standard()`           |
| `PipelineOrder.RESILIENCE4J`         | `PipelineOrdering.resilience4j()`       |
| `PipelineOrder.custom(types...)`     | Funktionales Interface: `PipelineOrdering` (lambda `type -> int`) |

**Empfehlung:** Beide ADRs aktualisieren. `PipelineOrder` → `PipelineOrdering`, die Enum-Konstanten durch die Factory-Methoden ersetzen, Builder-Pattern und Terminal-Trennung dokumentieren.

---

### 1.5 ADR-015 — Registry-Interface weicht erheblich ab

**ADR-015 definiert:**

```java
E get(String name);
E get(String name, C config);
E get(String name, String configName);        // Template-Lookup
void addConfiguration(String configName, C config);  // Template registrieren
C getDefaultConfig();
boolean remove(String name);
void clear();
```

**Code sagt** (`InqRegistry<E, C extends InqElementConfig>`):

```java
E get(String name);
E get(String name, C config);
void register(String name, C config);          // Neu
Optional<E> find(String name);                 // Neu
Set<String> getAllNames();                     // Neu
Map<String, E> getAll();                       // Neu
C getDefaultConfig();
```

**Konkrete Abweichungen:**

- Das Template-Konzept (`addConfiguration(String, C)` + `get(String, String)`) **fehlt komplett** im Interface.
- `remove(String)` und `clear()` fehlen, obwohl ADR-015 sie unter „Consequences" explizit als positive Eigenschaften listet („Explicit remove() and reset() lifecycle management").
- Die vom ADR versprochenen Registry-Events (`RegistryEntryAddedEvent`, `RegistryEntryRemovedEvent`, `RegistryConfigurationIgnoredEvent`) sind im Interface nirgends sichtbar; der `BulkheadRegistry` emittiert keine.
- Der `BulkheadRegistry` ignoriert den übergebenen Namen beim Bulkhead selbst (`Bulkhead.of(config)` — der Name kommt aus der Config, nicht aus dem Registry-Key).
- `InqElementRegistry` (`@since 0.8.0`) ist ein **zweites** Registry-Konzept, das ADR-015 überhaupt nicht kennt.

**Empfehlung:** ADR-015 neu schreiben — Template-Feature streichen (oder separates Follow-up-ADR), Events als Option markieren, `InqElementRegistry` als Lookup-Registry für Aspects dokumentieren (separat vom per-Element-Registry wie `BulkheadRegistry`).

---

### 1.6 ADR-010 — TimeLimiter: „No interrupt, even as an opt-in" ist im Code überstimmt

**ADR-010 sagt explizit:**

> We explicitly do **not** offer a `cancelRunningFuture(true)` option or a `mayInterruptIfRunning` flag.

> **Imperative API: CompletionStage only (no synchronous Supplier)**
> Wrapping a synchronous `Supplier<T>` in a hidden thread — even a virtual thread — is not offered.

**Code sagt** (`CompletableFutureAsyncExecutor`):

- `executeAsync(Callable<T> callable, Duration timeout)` wrappt einen synchronen Callable in einem Virtual Thread — genau das, was ADR-010 ausschließt.
- Bei Timeout: `vThread.interrupt()` — genau das, was ADR-010 ausschließt.
- Es existiert eine Config-Option `cancelOnTimeout()` — wird im ADR nicht erwähnt, stellt aber genau den abgelehnten Opt-in-Interrupt bereit.
- Die Javadoc-Tabelle im Code bestätigt: "Callable → vThread.interrupt() → Interrupts: Yes".

**Empfehlung:** ADR-010 muss entweder
(a) anerkennen, dass der Callable-Pfad und `cancelOnTimeout` als bewusste Designentscheidung hinzugekommen sind (mit Abgrenzung, wann Interrupt-basierte Kündigung sicher ist), oder
(b) der Code muss angepasst werden — aber laut User-Vorgabe ist der Code maßgebend, also (a).

---

### 1.7 ADR-018 — Retry-Config und Backoff-Strategien

**ADR-018 RetryConfig:**

```java
record RetryConfig(
    int maxAttempts,
    Duration initialInterval,
    BackoffStrategy backoffStrategy,
    Duration maxInterval,
    Set<Class<? extends Throwable>> retryOn,
    Set<Class<? extends Throwable>> ignoreOn,
    boolean retryOnInqExceptions,
    Predicate<Throwable> retryOnPredicate,
    InqCompatibility compatibility
)
```

**Code RetryConfig** (`eu.inqudium.core.element.retry.RetryConfig`):

```java
record RetryConfig(
    String name,
    int maxAttempts,
    BackoffStrategy backoffStrategy,
    Predicate<Throwable> retryPredicate,
    Predicate<Object> resultPredicate
)
```

**Abweichungen:**

- `name` im Code, nicht im ADR.
- `initialInterval`, `maxInterval` fehlen im Code (in die BackoffStrategies wie `DecorrelatedJitterBackoffStrategy(Duration initialDelay, Duration maxDelay)` verlagert).
- `retryOn`/`ignoreOn` als Sets fehlen — nur ein `retryPredicate`.
- `retryOnInqExceptions` fehlt komplett.
- `resultPredicate` (ergebnisbasierter Retry) **fehlt im ADR**.
- `InqCompatibility` fehlt im Code.

**Zweiter RetryConfig:** Es gibt einen zweiten Record `eu.inqudium.core.element.retry.dsl.RetryConfig(String, int, Duration, double)` — in der ADR überhaupt nicht erwähnt, und inkompatibel zum primären.

**Backoff-Strategien:**

| ADR-018 | Code |
|---------|------|
| `FixedBackoff` | `FixedBackoffStrategy` |
| `ExponentialBackoff` | `ExponentialBackoffStrategy` |
| `RandomizedBackoff` als Decorator für andere Strategies | Existiert nicht als Decorator — stattdessen eigene Klassen: `ExponentialWithJitterBackoffStrategy`, `DecorrelatedJitterBackoffStrategy` |
| — | `LinearBackoffStrategy` |
| — | `FibonacciBackoffStrategy` |
| — | `NoWaitBackoffStrategy` |
| — | `CustomBackoffStrategy` |

Die Decorator-Aussage der ADR („any backoff strategy can be jittered — including custom implementations") ist im Code nicht umgesetzt — Jitter ist in eigenen Strategie-Klassen festverdrahtet.

Auch die Methoden-Signatur weicht ab: ADR sagt `Duration computeDelay(int attemptNumber, Duration initialInterval)`, Code hat `computeDelay(int attemptIndex)` und eine zweite Methode `computeDelay(int attemptIndex, Duration previousDelay)`.

**Empfehlung:** ADR-018 fast komplett neu schreiben. Die realen Backoff-Strategien dokumentieren, den DSL-RetryConfig und den primären RetryConfig abgrenzen oder vereinheitlichen (Letzteres wäre auch Code-Aufräumarbeit).

---

## 2. Mittlere Inkonsistenzen

### 2.1 ADR-005 / ADR-016 — `InqClock` vs. `InqNanoTimeSource`

Beide ADRs (sowie `package-info.java` und `user-guide.md`) schreiben ausschließlich von `InqClock` (mit `Instant instant()`) als injizierbarer Zeit-Source.

Der tatsächliche Code hat **beide**:

- `InqClock` (`Instant`-basiert) — wird laut `GeneralConfigBuilder` für „Wall-Clock" / Log-Timestamps genutzt.
- `InqNanoTimeSource` (`long now()`) — wird von allen Circuit-Breaker-`FailureMetrics` verwendet.

Das ist eine bewusste Trennung im Code (monotonic vs. wall-clock), aber keine einzige ADR erwähnt diese Trennung. Der Javadoc von `InqNanoTimeSource` selbst wirft die Begriffe durcheinander ("uses `InqNanoTimesource` or `InqClock`", Tippfehler inklusive).

**Empfehlung:** ADR-016 ergänzen: Erkläre, warum das System zwei Zeit-Sources hat und wann welche zu verwenden ist.

---

### 2.2 ADR-003 — Event-Publisher-Contract stark unterdokumentiert

**Status:** Überarbeitet im Branch `adr/fix-003-event-system` (2026-04-24). Die ADR dokumentiert jetzt:
`InqSubscription`-Rückgabetyp, TTL-Overloads, `publishTrace`/`isTraceEnabled`, `close()`/`AutoCloseable`,
`InqPublisherConfig` (soft/hard limit, expiry interval, traceEnabled), den `InqConsumerExpiryWatchdog`-Lebenszyklus
(lazy gestartet auf Daemon-Virtual-Thread, `WeakReference` auf den Owner) sowie die `Open → Resolving → Frozen`-
State-Machine von `InqEventExporterRegistry` inklusive Provider-Error-Replay. Das obsolete UUID-/`CallContext`-/
`CallIdSupplier`-Modell wurde durch einen Verweis auf ADR-022 (`chainId` + `callId` als `long`) ersetzt.

**Verbleibend:** Während der Überarbeitung wurde eine tiefere Inkonsistenz aufgedeckt — die Element-Event-Hierarchie
ist gespalten. Siehe §2.6.

**Ursprünglicher Befund (vor der Überarbeitung):**

ADR-003 Publisher-Contract:

```java
void publish(InqEvent event);
void onEvent(InqEventConsumer consumer);
<E extends InqEvent> void onEvent(Class<E> eventType, Consumer<E> consumer);
```

Code hatte zusätzlich:

- `publishTrace(Supplier<? extends InqEvent>)` + `isTraceEnabled()`.
- `onEvent(...)` gibt `InqSubscription` zurück statt `void`.
- Vier zusätzliche TTL-Overloads: `onEvent(consumer, Duration ttl)`, `onEvent(Class, consumer, ttl)`.
- `close()` via `AutoCloseable` + Hintergrund-Watchdog (`InqConsumerExpiryWatchdog`).
- Soft/Hard-Consumer-Limits via `InqPublisherConfig`.

---

### 2.3 ADR-019 — RateLimiter-Parameter-Naming

**ADR-019 nennt den Bucket-Parameter** `bucketSize`.

**Code hat** `capacity` (plus `refillPermits`, `refillPeriod`, `defaultTimeout`, `strategy`, `name`).

Außerdem fehlt im ADR die generische Strategie-Struktur (`RateLimiterStrategy<S extends RateLimiterState>`), die im Code den Rate-Limiter-Algorithmus austauschbar macht. Der Code hat eine vollständige Strategie-Abstraktion (`TokenBucketStrategy`, `TokenBucketState`, `RateLimitPermission`, `ReservationResult`) — das ADR beschreibt nur die Token-Bucket-Implementation als inline-Pseudocode.

Und: Der Code wirft `RateLimiterException` (nicht `InqRequestNotPermittedException` wie ADR-021 vorschreibt) — das sollte geprüft und angeglichen werden.

---

### 2.4 ADR-020 — Bulkhead-Behavior-Contract vs. tatsächliche Strategie-Interfaces

**Status:** Überarbeitet im Branch `adr/fix-020-bulkhead` (2026-04-24). Die ADR dokumentiert jetzt:
die paradigm-typed Spaltung `BulkheadStrategy` / `BlockingBulkheadStrategy` / `NonBlockingBulkheadStrategy`,
`RejectionContext`/`RejectionReason`, alle drei Strategie-Familien (statisch, adaptiv mit `InqLimitAlgorithm`, CoDel),
`release()` vs. `rollback()`, Over-release-Guards, Sync- und Async-Pfad von `ImperativeBulkhead`, korrigierte
Wait-Mechanik-Tabelle, vollständige Event-Liste (inkl. CoDel- und LimitChanged-Trace-Events), beide Exception-Typen
(`InqBulkheadFullException` INQ-BH-001 + `InqBulkheadInterruptedException` INQ-BH-002), und einen Verweis auf
ADR-003 statt einer Wiederholung der Publisher-Mechanik.

**Verbleibend:** Während der Überarbeitung wurde weitere Drift aufgedeckt, die in §2.7 aufgenommen ist.

**Ursprünglicher Befund (vor der Überarbeitung):**

ADR-020 definierte ein Interface `BulkheadBehavior` mit einem reinen Acquire/Release-Entscheidungsmodell
(`BulkheadResult.denied()`) — pures Counter-Logic ohne Blocking.

Code realisierte stattdessen:

- `BulkheadStrategy` als Basis-Interface (die in der ursprünglichen Notiz erwähnte Klasse `AbstractBulkhead`
  existiert gar nicht — Tippfehler in der Notiz).
- Zwei abgeleitete Interfaces: `BlockingBulkheadStrategy` und `NonBlockingBulkheadStrategy`.
- Der Name `BlockingBulkheadStrategy` widersprach direkt der ADR-Aussage „No blocking in the core algorithm — ever".
  Die imperative Implementation `SemaphoreBulkheadStrategy` nutzt tatsächlich
  `semaphore.tryAcquire(timeout, TimeUnit.NANOSECONDS)`. Die ADR ist jetzt umformuliert: das Core-Modul definiert
  beide Verträge (Blocking + Non-Blocking) als bewusste paradigmatische Wahl; Blocking-Implementierungen leben in
  Paradigm-Modulen.

---

### 2.5 ADR-009 vs. `architecture.md` — Widersprüchliche Exception-Hierarchien

ADR-009 und der Code sind konsistent miteinander. `architecture.md` dagegen erfindet Exception-Klassen:

```
architecture.md:   InqCallRejectedException    (CB/RL/BH)
                   InqConfigurationException   (setup errors)

ADR-009 & Code:    InqCallNotPermittedException (CB)
                   InqRequestNotPermittedException (RL)
                   InqBulkheadFullException (BH)
                   — kein InqConfigurationException, stattdessen IllegalArgumentException
```

**Empfehlung:** `architecture.md` an ADR-009 angleichen.

---

### 2.6 ADR-003 — Element-Event-Hierarchie ist gespalten

**Aufgedeckt am 2026-04-24** während der Überarbeitung von ADR-003 (siehe §2.2).

**ADR-003 sagt** (auch in der überarbeiteten Form): Es gibt ein einheitliches Event-Framework. Jeder `InqEvent` fließt
durch `InqEventPublisher`. Subklassen pro Element-Familie tragen `chainId`, `callId`, `elementName`, `elementType`,
`timestamp`.

**Code sagt:** Es gibt **zwei parallele Event-Pattern**, die nicht zusammen passen.

| Element         | Event-Klasse                                  | `extends InqEvent`? | Trägt `chainId`/`callId`? | Fließt durch `InqEventPublisher`? |
|-----------------|-----------------------------------------------|---------------------|---------------------------|-----------------------------------|
| Bulkhead        | `BulkheadEvent` + 7 konkrete Subklassen       | ✓                   | ✓                         | ✓                                 |
| —               | `InqCompatibilityEvent`                       | ✓                   | ✓ (synthetisch)           | ✓                                 |
| —               | `InqProviderErrorEvent`                       | ✓                   | `-1`/`-1` (Sentinel)      | ✓                                 |
| Retry           | `RetryEvent` (`record` + `Type`-Enum)         | **nein**            | nein (`String name`)      | **nein**                          |
| Rate Limiter    | `RateLimiterEvent` (`record` + `Type`-Enum)   | **nein**            | nein                      | **nein**                          |
| Time Limiter    | `TimeLimiterEvent` (`record` + `Type`-Enum)   | **nein**            | nein                      | **nein**                          |
| Traffic Shaper  | `TrafficShaperEvent` (`record` + `Type`-Enum) | **nein**            | nein                      | **nein**                          |
| Fallback        | `FallbackEvent` (`record` + `Type`-Enum)      | **nein**            | nein                      | **nein**                          |
| Circuit Breaker | —                                             | —                   | —                         | — (emittiert keine Events)        |

**Konsequenzen:**

- Die in ADR-003 zentrale Cross-Element-Korrelation per `callId` funktioniert in der Praxis nur für Bulkhead +
  Infrastruktur-Events.
- `InqEventPublisher.publish(InqEvent)` lehnt die fünf Record-Events typsystem-bedingt ab — sie können nicht
  durchgehen, selbst wenn ein Element wollte.
- Konsumenten (Micrometer-Binder, JFR-Binder, custom Listener) sehen heute nur Bulkhead-Events. Retry-/Rate-Limiter-
  /Time-Limiter-/Traffic-Shaper-/Fallback-Events sind über die Publisher-API unsichtbar.
- Die Bezeichnung „unified event system" trifft heute nicht zu — sie beschreibt einen Soll-Zustand.

**Was zu klären ist (eigene PR-Reihe):**

1. Sollen die fünf Record-Events auf `InqEvent`-Subklassen migriert werden? Wenn ja: wie wird `chainId`/`callId` in
   den Aufrufpfad eingespeist (heute kennen die Records keine Pipeline-Kontext-Quelle)?
2. Sollen die `Type`-Enums durch konkrete Subklassen pro Event-Art ersetzt werden (Symmetrie zur Bulkhead-Familie),
   oder bleibt die kompaktere Record+Enum-Form pro Element erhalten?
3. Sollen Circuit-Breaker-Events implementiert werden, oder ist Polling per `getState()`/`getMetrics()` das bewusste
   Design? (Tendenz aus ADR-003: Tier-1-Polling reicht für Circuit-Breaker-Status; Tier-2-Events wären für
   Transition-Diagnostik nützlich.)

**Behandlung in ADR-003:** ADR-003 beschreibt das Framework und listet keinen Element-Event-Katalog mehr (Abschnitt
„Scope of this ADR"). Die hier dokumentierte Spaltung wird in dedizierten Element-ADRs adressiert, sobald die
Migration geplant ist.

---

## 3. Kleinere Inkonsistenzen

### 3.1 `module-info.java` Javadoc vs. tatsächliche Exports

Der Javadoc-Block im `module-info.java` beschreibt:

```
eu.inqudium.core.circuitbreaker
eu.inqudium.core.retry
eu.inqudium.core.ratelimiter
eu.inqudium.core.bulkhead
eu.inqudium.core.timelimiter
eu.inqudium.core.cache    ← existiert nicht
```

Tatsächlich exportierte Pakete:

```
eu.inqudium.core.element.circuitbreaker
eu.inqudium.core.element.retry
eu.inqudium.core.element.ratelimiter
...
eu.inqudium.core.element.trafficshaper   ← fehlt im Javadoc
eu.inqudium.core.element.fallback        ← fehlt im Javadoc, fehlt in allen ADRs
```

Das `eu.inqudium.core.cache`-Paket ist **weder exportiert noch implementiert** — der Javadoc führt einen Phantom.

### 3.2 `exception/package-info.java` — veraltete Paketpfade

Die Hierarchie im Kommentar zeigt:

```
InqCallNotPermittedException (INQ-CB-001) — eu.inqudium.core.circuitbreaker
```

Die Exception liegt tatsächlich in `eu.inqudium.core.element.circuitbreaker`. Gleiches Muster für alle anderen Exceptions.

### 3.3 `package-info.java` (core)

Sagt: "Base interface implemented by all resilience elements (circuit breakers, retries, rate limiters, bulkheads, time limiters, **caches**)."

Weder Cache-Element noch Cache-Package existieren. TRAFFIC_SHAPER wird nicht erwähnt.

### 3.4 Copy-Paste-Fehler in `InqRetryConfigBuilder`

```java
InqElementCommonConfig common =
    new InqElementCommonConfig(name,
        InqElementType.BULKHEAD,   // ← falsch für einen Retry!
        eventPublisher);
```

Und die Error-Message:
```java
throw new IllegalStateException(
    "name must be set. Call name(...) before building the bulkhead.");  // ← auch "bulkhead"
```

Das gleiche findet sich in `DefaultRetryProtection.named()`:
```java
throw new IllegalArgumentException("Bulkhead name must not be blank");  // ← in einer Retry-Klasse
```

Das sind reine Code-Bugs, aber sie zeigen, dass das Retry-Modul offensichtlich aus dem Bulkhead-Modul kopiert und nicht vollständig angepasst wurde.

### 3.5 ADR-023 referenziert ADR-022 mit dem alten Modell

ADR-023 spricht davon, dass der `InqCall`-Abstraction die `callId` trägt und diese durch die CompletableFuture-Kopie weitergereicht wird. Da `InqCall` im Code in der ADR-22-Form vermutlich nicht existiert (siehe §1.1), geht diese Argumentation ins Leere. ADR-023 muss nach der ADR-022-Überarbeitung ebenfalls neu durchgegangen werden.

---

## 4. Zusammenfassung in Prioritätsordnung

| Priorität | ADR(s)               | Kerninkonsistenz |
|-----------|----------------------|------------------|
| **Hoch**  | ADR-022              | `callId`-Typ String→long, fehlendes `chainId`-Feld, ganzes `InqCall<T>`-Record-Design |
| **Hoch**  | ADR-016              | `SlidingWindow` existiert nicht — heißt `FailureMetrics` mit viel mehr Implementierungen |
| **Hoch**  | ADR-017, ADR-021, ADR-024 | `CACHE` existiert nicht im Enum; `TRAFFIC_SHAPER` wird nirgends als Element dokumentiert |
| **Hoch**  | ADR-002, ADR-017     | Pipeline-API (`of().shield().decorate()` vs. `builder().shield().build()` + Terminal); `PipelineOrder` → `PipelineOrdering` |
| **Hoch**  | ADR-015              | Registry-Interface fehlen Template-Methoden (`addConfiguration`), `remove`, `clear`; zweites `InqElementRegistry` nicht dokumentiert |
| **Hoch**  | ADR-010              | Synchroner Callable-Pfad, `vThread.interrupt()`, `cancelOnTimeout` — alle in ADR-10 explizit ausgeschlossen |
| **Hoch**  | ADR-018              | `RetryConfig` Felder weichen massiv ab, Backoff-Decorator-Pattern nicht umgesetzt, vier zusätzliche Strategien nicht dokumentiert, zweiter DSL-RetryConfig-Record |
| ~~Mittel~~ | ~~ADR-003 (§2.2)~~ | ~~Publisher-Contract unterbeschrieben: `publishTrace`, TTL, `InqSubscription`, `close`, Limits~~ — überarbeitet 2026-04-24 |
| **Hoch**  | ADR-003 (§2.6)       | Element-Event-Hierarchie gespalten: nur Bulkhead/Compatibility/ProviderError sind `InqEvent`; Retry/RateLimiter/TimeLimiter/TrafficShaper/Fallback nutzen ad-hoc Records |
| Mittel    | ADR-016, ADR-005     | `InqClock` vs. `InqNanoTimeSource` — Dualität nicht dokumentiert |
| Mittel    | ADR-019              | Parameter heißt `capacity`, nicht `bucketSize`; fehlende Strategie-Abstraktion; `RateLimiterException` vs. erwartete `InqRequestNotPermittedException` |
| ~~Mittel~~ | ~~ADR-020 (§2.4)~~ | ~~Typnamensgebung (`BulkheadBehavior` vs. `BlockingBulkheadStrategy`)~~ — überarbeitet 2026-04-24 |
| **Hoch**  | ADR-020 (§2.7)       | Substanzielle undokumentierte Drift: `RejectionContext`, adaptive Strategien (`InqLimitAlgorithm`, AIMD/Vegas), CoDel, drei Config-Schichten, `release`/`rollback`-Trennung, zwei zusätzliche Trace-Events, `InqBulkheadInterruptedException` (INQ-BH-002), Async-Pfad |
| Niedrig   | `architecture.md`    | Widerspricht ADR-009 bei Exception-Namen (`InqCallRejectedException`, `InqConfigurationException`) |
| Niedrig   | `module-info` + `package-info`s | Veraltete Paketpfade `eu.inqudium.core.circuitbreaker` statt `…element.circuitbreaker`; phantomhaftes Cache-Package; TRAFFIC_SHAPER und FALLBACK fehlen |
| Niedrig   | ADR-023              | Argumentation basiert auf ADR-022-Modell, das im Code so nicht existiert |

---

## 5. Empfohlenes Vorgehen

1. **ADR-022 zuerst.** Sie ist die Grundlage — wenn `callId`-Typ und `chainId`-Konzept geklärt sind, folgen ADR-003 und ADR-023 fast automatisch.
2. **ADR-016 und ADR-018 gemeinsam.** Beide sind algorithmus-lastig und haben Config-Struktur-Abweichungen; am besten mit denselben Code-Referenzen parallel überarbeiten.
3. **Pipeline-Trilogie ADR-002 + ADR-017 + ADR-024.** Diese drei verweisen massiv aufeinander; Einzelüberarbeitung würde neue Inkonsistenzen schaffen. Hier unbedingt die Cache-Frage klären: Entweder CACHE wird implementiert, oder aus allen drei ADRs entfernt.
4. **ADR-015 (Registry).** Template-Funktionalität entweder implementieren oder aus der ADR streichen.
5. **Dokumentations-Housekeeping zuletzt.** `architecture.md`, `module-info.java`, `package-info.java`-Kommentare, sobald die großen ADRs stehen.

Parallel zu den ADR-Updates sollten die Copy-Paste-Fehler im Retry-Modul (§3.4) gefixt werden — das ist reine Code-Arbeit und ADR-unabhängig.
