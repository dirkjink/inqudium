# AUDIT_FINDINGS.md

Findings from the bulkhead pattern completion audit (REFACTORING.md sub-steps
2.12 through 2.20). Each entry describes a concrete observation made during the
audit, its priority, and a suggestion for where it should land — a later sub-step,
TODO.md, IDEAS.md, an ADR update, or no action.

Findings are not commitments. They are inputs for the review session that follows
each audit sub-step. Decisions are made there.

When this file's findings have all been routed to their respective destinations,
the file is deleted along with REFACTORING.md at the end of the bulkhead refactor.

---

## 2.12.1 — `BulkheadHotPhase` does not feed adaptive algorithms (`onCallComplete` missing)

**Bereich:** 2.12.1
**Priorität:** kritisch
**Vorschlag:** Aufnahme in 2.13 (Core-Modul-Sweep) oder eigener Sub-Step vor 2.17 — vor dem Integration-Test-Modul muss das gefixt sein, sonst pinnen die Tests den Defekt.

**Beobachtung:**

`BulkheadHotPhase.execute(...)` (Zeilen 371–381) ruft im `finally`-Block ausschließlich
`strategy.release()`, gefolgt von einem optionalen `BulkheadOnReleaseEvent`-Publish.
Es gibt weder einen `nanoTimeSource.now()`-Sample am Anfang von `next.execute(...)`
noch einen RTT-berechnenden Sample am Ende, und es gibt keinen Aufruf von
`strategy.onCallComplete(rttNanos, isSuccess)` vor `release()`.

ADR-020, Abschnitt „Adaptive — Feedback ordering matters" (Zeilen 249–258), ist hier
explizit: „The reactive/imperative facade must call `onCallComplete(rttNanos, isSuccess)`
*before* `release()` so the in-flight count passed to the algorithm still includes
the completing call. … Forgetting `onCallComplete` entirely silently degrades the
adaptive strategy to a static limiter."

Konsequenz: Eine durch die neue Architektur instanziierte `InqBulkhead` mit
`AdaptiveBulkheadStrategy` oder `AdaptiveNonBlockingBulkheadStrategy` läuft
permanent auf dem konfigurierten Initial-Limit des Algorithmus. AIMD erhöht nie,
Vegas reagiert nie auf RTT-Trends, der Error-Rate-Threshold feuert nie. Der
Algorithmus existiert, wird vom `BulkheadStrategyFactory` korrekt verdrahtet,
und ist von außen sichtbar — verhält sich aber als statischer Limiter.

Die Legacy-`ImperativeBulkhead.releaseAndReport(...)` (Zeilen 405–443) macht es
korrekt: misst RTT, ruft `onCallComplete` vor `release`, fängt Algorithm-Fehler
und loggt sie. Diese Logik wurde beim Übergang auf `BulkheadHotPhase` nicht
mitgenommen.

Die Strategie-Materialisierungs-Tests
(`BulkheadHotPhaseStrategyMaterializationTest.should_serve_calls_with_an_adaptive_strategy_running_AIMD`
und Geschwister) verifizieren nur, dass eine adaptive Strategy einen Call
serviert — sie verifizieren nicht, dass der Algorithmus Feedback erhält. Daher
fällt der Defekt in keinem bestehenden Test auf.

**Begründung der Priorität:**

Zwei der vier Strategies sind im neuen Code-Pfad funktional unbenutzbar — der
Adaptive-Aspekt fehlt vollständig. Das ist die schwerste Form von Drift: das
Pattern existiert nominell vollständig, aber zwei seiner Implementations laufen
nicht so, wie ihr Name suggeriert.

---

## 2.12.1 — `InqBulkheadFullException` always constructed with optimization disabled

**Bereich:** 2.12.1
**Priorität:** wichtig
**Vorschlag:** Aufnahme in 2.13 oder dedizierten Fix-Step. Verdrahtung über `general()`-Snapshot wie bei der Legacy-Klasse.

**Beobachtung:**

`BulkheadHotPhase.execute(...)` Zeile 344–345:

```java
throw new InqBulkheadFullException(
        chainId, callId, component.name(), rejection, false);
```

Das letzte Argument ist hartkodiert `false`. Die Legacy-`ImperativeBulkhead` an
gleicher Stelle: `config.general().enableExceptionOptimization()`.

ADR-020 Zeile 491–495: „Overrides `fillInStackTrace()` to a no-op. A bulkhead
rejection is a flow-control signal, not a programming error; `RejectionContext`
already carries the diagnostic data, and stack-trace generation dominates the
rejection path's cost under high rejection rates."

Die Optimierung ist daher ein dokumentiertes Performance-Feature für den
Reject-Pfad. Im neuen Code ist sie permanent ausgeschaltet — jede Rejection
zahlt die volle Stack-Trace-Generierung.

Gleiches Symptom bei `InqBulkheadInterruptedException` Zeile 329 (auch dort
`false`).

**Begründung der Priorität:**

Performance-Regression auf einem dokumentiert hot pfad (ADR-020 nennt das
explizit als Reject-Pfad-Kostendominanz). Funktional korrekt; nur teurer als
spezifiziert.

---

## 2.12.1 — ADR-020 „Configuration" und „Registry" Sektionen beschreiben pre-Phase-1-Architektur

**Bereich:** 2.12.1
**Priorität:** wichtig
**Vorschlag:** ADR-020-Update beim ADR-Audit-Refactor (das nächste REFACTORING.md). Markieren als bekannte Drift in `docs/adr/_refactor-notes.md` falls vorhanden.

**Beobachtung:**

ADR-020 enthält drei Code-Listings, die nicht zum aktuellen Code passen:

1. **Configuration** (Zeilen 299–337): Beschreibt `InqImperativeBulkheadConfig`
   mit `composes` `InqBulkheadConfig` plus `inference()`-Methode, die je nach
   Algorithmus-Konfiguration eine Strategie wählt. Im aktuellen Code lebt
   Strategie-Wahl auf `BulkheadSnapshot.strategy()` als sealed
   `BulkheadStrategyConfig` (ADR-032), und die Auflösung passiert in
   `BulkheadStrategyFactory`. Der `inference()`-Mechanismus existiert nicht mehr
   als Auflösungspfad für die neue `InqBulkhead`.

2. **Registry** (Zeilen 506–509): „Named bulkhead instances are managed by
   `BulkheadRegistry` (per ADR-015): `register`, `get(name)`, `get(name, config)`,
   `find`, `getAllNames`, `getAll`." Im aktuellen Code gibt es keinen
   `BulkheadRegistry` mit dieser Schnittstelle. Bulkheads werden über
   `runtime.imperative().bulkhead("name")` aufgelöst — die Runtime ist die
   Registry. ADR-026 dokumentiert diese Architektur.

3. **Synchronous path** (Zeilen 346–376) und **Asynchronous path** (Zeilen 392–406):
   Die Code-Listings entsprechen exakt der Legacy-`ImperativeBulkhead` mit
   `handleAcquireFailure(...)`, `handleAcquireSuccess(...)`,
   `releaseAndReport(...)` Helpers. Die neue `BulkheadHotPhase` inlinet die
   Event-Publishing-Logik in `execute(...)` und hat diese Helpers nicht.

**Begründung der Priorität:**

ADR-020 ist die zentrale Bulkhead-Design-ADR. Drei größere Sektionen drift sind
ein Risiko: nächste Person, die die ADR liest, baut sich ein falsches Modell.
Code-First-Disziplin sagt jedoch klar: nicht jetzt fixen, in dedizierten
ADR-Audit aufnehmen.

---

## 2.12.1 — `InqBulkhead` hat keine asynchrone Variante

**Bereich:** 2.12.1
**Priorität:** wichtig
**Vorschlag:** Aufnahme in TODO.md (existierender Code-TODO macht es bereits explizit) und Verweis im ADR-Update. Eigene ADR vermutlich notwendig (siehe TODO-Kommentar in `InqBulkhead.java`).

**Beobachtung:**

`InqBulkhead.java` Zeile 137–143 enthält einen TODO-Kommentar, der explizit
festhält, dass die asynchrone Variante (`executeAsync` /
`InqAsyncDecorator`-Pendant) für die neue Architektur nicht designed ist.
ADR-020 spezifiziert die asynchrone Pfad-Semantik aber als Teil des
Bulkhead-Patterns (Zeilen 388–406, „Asynchronous path", inkl. ADR-023-Verweis
auf decorated copy).

Die Legacy-`ImperativeBulkhead` implementiert den asynchronen Pfad korrekt
(Zeilen 232–302). Das Pattern ist also nicht verloren — es muss nur in die neue
Architektur übersetzt werden, was nach Code-Kommentar eine eigene ADR
rechtfertigt.

**Begründung der Priorität:**

Pattern-Vollständigkeit erfordert beide Pfade. Aktuell deckt die neue Klasse
nur den synchronen Fall ab; der asynchrone Pfad ist in der Library-Oberfläche
nicht erreichbar (außer über die deprecated `ImperativeBulkhead`). Wichtig, aber
nicht kritisch — Async-Bulkhead ist bei virtuellen Threads ein selteneres
Use-Case-Pattern als der synchrone, und die Legacy-Klasse existiert noch als
Brücke.

---

## 2.12.1 — Legacy `Bulkhead.java` und `ImperativeBulkhead.java` koexistieren mit neuer Architektur

**Bereich:** 2.12.1
**Priorität:** nachrangig
**Vorschlag:** keine Aktion — explizit dokumentiert in REFACTORING.md Schritt 3.1. Erwähnen im 2.13 Sweep zur Bestätigung der Deprecation-Form.

**Beobachtung:**

`inqudium-imperative/src/main/java/eu/inqudium/imperative/bulkhead/Bulkhead.java`
und `ImperativeBulkhead.java` tragen
`@Deprecated(forRemoval = true, since = "0.4.0")` mit explizitem Verweis auf
„removed alongside the legacy bulkhead surface in REFACTORING.md step 3.1".
Der Hinweis auf Schritt 3.1 zeigt: Die Removal ist geplant, aber außerhalb der
Phase-2-Sequenz. Die Klassen existieren weiter, weil
`eu.inqudium.imperative.circuitbreaker.CircuitBreaker` und der Legacy-`Resilience`-DSL
sie noch konstruieren.

**Begründung der Priorität:**

Bekannte, dokumentierte Übergangsschuld. Phase 2 schließt den Bulkhead nicht in
dem Sinne ab, dass die Legacy-Klasse weg ist — sondern in dem Sinne, dass die
neue Architektur die kanonische Implementation ist. Der finale Cleanup ist
Phase 3.

---

## 2.12.2 — Algorithmus-Sub-Config-Wechsel innerhalb gleicher Strategy-Type silently no-op

**Bereich:** 2.12.2
**Priorität:** wichtig
**Vorschlag:** Erweiterung des bestehenden TODO.md-Eintrags „Strategy config tweaks without strategy-type change" — der dort gegebene Fix-Shape deckt diesen Fall mit ab, aber der Eintrag listet aktuell als konkretes Beispiel nur CoDel-Parameter. Algorithm-Wechsel (AIMD ↔ Vegas) verdient eigene Erwähnung im selben Eintrag, weil er semantisch dramatischer ist.

**Beobachtung:**

`BulkheadHotPhase.strategyChanged(...)` (Zeilen 216–231) prüft nur die
Strategy-Klassen-Identität. Ein Patch, der `AdaptiveStrategyConfig` mit AIMD
durch `AdaptiveStrategyConfig` mit Vegas ersetzt, fällt durch beide Type-Checks
gleich aus (beide Male `AdaptiveBulkheadStrategy`), wird also nicht als „swap"
erkannt. Der Snapshot reflektiert die neue Algorithmen-Wahl, der laufende
Strategy-Algorithmus bleibt aber unverändert.

Das ist nicht „nur eine Parameter-Änderung", wie es bei CoDel `targetDelay`
wäre — es ist ein kompletter Algorithmen-Wechsel zwischen mathematisch
unterschiedlichen Limit-Berechnungs-Verfahren. Operator-erwarteter Effekt
(„AIMD ist zu konservativ, schalte auf Vegas um") tritt nicht ein, ohne
Indikation.

**Begründung der Priorität:**

Verstärkt die in TODO.md dokumentierte Lücke um einen sichtbar wichtigeren
Fall. Gleicher Code-Pfad, gleiche Lösung — daher kein eigenständiges Finding,
nur Erweiterung des Beispiels.

---

## 2.12.2 — Adaptive-Strategien sind ohne `onCallComplete`-Fix faktisch nicht live-tunable

**Bereich:** 2.12.2
**Priorität:** kritisch (Folge-Finding zu 2.12.1 / `onCallComplete` missing)
**Vorschlag:** Fix dieses Finding ist Voraussetzung für jede Aussage über Adaptive-Strategy-Vollständigkeit. Aufnahme in selben Sub-Step wie das `onCallComplete`-Fix.

**Beobachtung:**

Wenn `onCallComplete` nicht aufgerufen wird (siehe 2.12.1, kritisches Finding),
hängt der Algorithmus auf seinem Initial-Limit fest. Live-Tuning auf
Algorithmen-Parameter (AIMD `errorRateThreshold`, Vegas `alpha/beta`,
`smoothingTimeConstant`) hätte daher selbst dann keinen Effekt, wenn der
strategyChanged-Mechanismus aus dem TODO-Eintrag gefixt wäre. Die Algorithmen
laufen aktuell als geschriebene Records, die nie ihren `update()`-Hook sehen.

**Begründung der Priorität:**

Ist konzeptuell ein Folge-Finding — sobald `onCallComplete` korrekt verdrahtet
ist, verschwindet dieses Finding mit. Dokumentiert hier nur, damit beim Review
der Zusammenhang explizit ist.

---

## 2.12.2 — `SemaphoreBulkheadStrategy.adjustMaxConcurrent` Javadoc nennt veraltete Phase-Begriffe

**Bereich:** 2.12.2
**Priorität:** nachrangig
**Vorschlag:** Aufnahme in 2.13 (Core-Modul-Sweep) — kleiner Javadoc-Fix.

**Beobachtung:**

Zeilen 56–60 von `SemaphoreBulkheadStrategy.java`:

```
This is the in-place adjustment Phase 1 of the configuration refactor supports.
Strategy-type changes (semaphore → CoDel, etc.) are Phase 2 and require
coordination with the veto chain to drain in-flight calls before swapping.
```

Phase 2 ist abgeschlossen; die Phase-Referenz ist überholt. Außerdem ist die
Aussage „require drain" jetzt unzutreffend — ADR-032 entschied sich gegen
Drainage zugunsten des atomaren Veto-Modells.

**Begründung der Priorität:**

Reine Doku-Hygiene, keine funktionale Auswirkung. Trifft sich gut mit dem
generellen Phase-Verweis-Cleanup aus 2.11.

---

## 2.12.3 — Cold-Phase-Accessoren liefern für Adaptive Strategies irreführenden Wert

**Bereich:** 2.12.3
**Priorität:** wichtig
**Vorschlag:** Diskussion beim Review — entweder dokumentieren als bewusste Discontinuity, oder Cold-Phase-Accessoren so anpassen, dass sie für Adaptive den Algorithmen-Initial-Limit aus der Sub-Config lesen.

**Beobachtung:**

`InqBulkhead.availablePermits()` und `concurrentCalls()` (Zeilen 118–135) lesen
im Cold-Zustand vom Snapshot:

```java
return p instanceof BulkheadHotPhase hot
        ? hot.availablePermits()
        : snapshot().maxConcurrentCalls();
```

Für `SemaphoreStrategyConfig` ist das korrekt: das Snapshot-Feld ist tatsächlich
das Limit, das die Strategy beim Hot-Werden bekommt. Für `CoDelStrategyConfig`
ist es ebenfalls korrekt (CoDel nimmt `snapshot.maxConcurrentCalls()` als
Pin-Limit). Für `AdaptiveStrategyConfig` und `AdaptiveNonBlockingStrategyConfig`
ist es **inkorrekt**: `BulkheadStrategyFactory` ignoriert
`snapshot.maxConcurrentCalls()` für die Adaptive-Pfade vollständig (siehe
Factory Zeilen 80–83 + Konstruktor-Aufrufe Zeilen 69 / 72) und konstruiert die
Strategy ausschließlich aus den Algorithmus-Sub-Config-Werten (`initialLimit`,
`minLimit`, `maxLimit`).

Konsequenz: Cold-availability sagt z.B. 50 (Snapshot-Default oder
User-`maxConcurrentCalls(50)`-Setter), Hot-availability nach erstem Call sagt
20 (AIMD `initialLimit`). Reading-Discontinuity beim Warm-up, nirgends
dokumentiert, kein Test pinnt das Verhalten in beide Richtungen.

**Begründung der Priorität:**

Beobachtbarkeit-API liefert zwei verschiedene Zahlen für „dasselbe" Limit, je
nachdem ob der Bulkhead bereits einen Call gesehen hat. Operator wundert sich.
Kein funktionaler Bug (die Zahlen sind beide isoliert korrekt — sie sind nur
nicht dasselbe).

---

## 2.12.3 — Race zwischen `markRemoved` und `onSnapshotChange` während Hot-Swap nicht getestet

**Bereich:** 2.12.3
**Priorität:** nachrangig
**Vorschlag:** Aufnahme in 2.17 (Bulkhead Integration Test Module). Dort gehört die End-to-End-Verifikation hin.

**Beobachtung:**

`BulkheadHotPhase.shutdown()` schließt die `subscription`, `markRemoved` ruft
`shutdown()` vor dem Installieren der `RemovedPhase`. Aber: ein Hot-Swap, der
gerade vom Dispatcher angestoßen ist und in der Mitte von `onSnapshotChange`
steht, könnte mit `markRemoved` racen. Insbesondere wenn `markRemoved` die
Subscription schließt, während der Dispatcher gerade die nächste
Listener-Notification aufruft, oder wenn `onSnapshotChange` gerade die alte
Strategy in `closeStrategy(...)` schließt, während `markRemoved` schon den
RemovedPhase-Sentinel installiert hat.

Code-Lesung suggeriert keine Korrektheits-Probleme: `closeStrategy` ist
best-effort, `subscription = null` nach Close, RemovedPhase blockt alle
weiteren execute-Calls. Aber kein Test pinnt diese Reihenfolge unter Last.

**Begründung der Priorität:**

Plausibel benigne. Fehlende Test-Abdeckung eher Hygiene-Lücke als akute
Korrektheits-Frage.

---

## 2.12.3 — Cold-zu-Hot-CAS-Verlierer allokieren Strategy-State

**Bereich:** 2.12.3
**Priorität:** nachrangig
**Vorschlag:** keine Aktion — fällt unter „strict reading of ADR-029 vs. pragmatic interpretation". Dokumentieren beim Review-Gespräch.

**Beobachtung:**

`BulkheadHotPhase`-Konstruktor (Zeile 117) ruft `BulkheadStrategyFactory.create`,
das `Semaphore`/`AtomicInteger`/`ReentrantLock`/`Condition` allokiert.
ADR-029 fordert „side-effect-free" für Hot-Phase-Konstruktoren, „discarded
candidates ... will be GC'd without any cleanup work". Die Allokation ist nicht
„side effect" im Sinne externer Beobachtbarkeit (kein Event publish, kein
Listener-Subscribe, kein File-Open) — nur Memory-Pressure.

Unter realistischer Last ist CAS-Contention beim Cold-zu-Hot-Übergang selten
(typisch erste Anfrage gewinnt, der Rest sieht bereits den Hot-Phase). Praktisch
irrelevant.

**Begründung der Priorität:**

Strenge ADR-Lesung zeigt eine technische Grauzone. Praktisch irrelevant; nicht
fix-würdig.

---

## 2.12.4 — Strategy-Konstruktions-Fehler beim Cold→Hot-Übergang nicht test-abgedeckt

**Bereich:** 2.12.4
**Priorität:** wichtig
**Vorschlag:** Aufnahme in 2.17 (Integration-Test-Modul) als Negative-Test, oder eigener Sub-Step-Test in `BulkheadHotPhaseStrategyMaterializationTest` falls trivial schreibbar.

**Beobachtung:**

`BulkheadStrategyFactory.create(...)` kann theoretisch werfen — z.B. wenn ein
Algorithmen-Sub-Config-Konstruktor in der Validierung scheitert (Vegas
`smoothingTimeConstant <= 0` o.Ä.). Compact-Constructors fangen das beim
Snapshot-Build, aber falls jemals ein Sub-Config-Setter durchschlüpft (etwa via
ServiceLoader-Provider oder zukünftige SPI-Strategy), würde der Wurf vom
`BulkheadHotPhase`-Konstruktor durch `cold.execute` an den Caller propagieren.
Die `phase`-Referenz bleibt im `ColdPhase`, der nächste Call würde retry. Das
Verhalten ist plausibel aber nirgends spezifiziert oder getestet.

Beim Hot-Swap (in `onSnapshotChange`) noch heikler: ein Wurf hier würde an die
Live-Container-Subscription propagieren. Der Caller (Dispatcher-Thread) bekommt
die Exception. Die `strategy`-Volatile bleibt auf der alten Strategy, aber
der Snapshot wurde bereits committet. Sich-aus-dem-Tritt-bringende
Snapshot-vs-Runtime-Diskrepanz.

**Begründung der Priorität:**

Kein bekannter Pfad löst das aktuell aus, aber das ist eine fragile Annahme.
Kleiner Test, der einen synthetisch werfenden Sub-Config-Provider injiziert,
würde die Erwartungen festschreiben.

---

## 2.12.4 — `closeStrategy(...)`-Wurf-Pfad hat Logging aber keinen Test

**Bereich:** 2.12.4
**Priorität:** nachrangig
**Vorschlag:** Aufnahme in 2.13 oder 2.17 — ein synthetischer `AutoCloseable`-Strategy-Mock, der beim Close wirft, würde den Pfad pinnen.

**Beobachtung:**

`BulkheadHotPhase.closeStrategy(...)` (Zeilen 244–257) ist defensiv gegen Worf:
loggt warn und schluckt. Aktuell implementiert keine der vier Strategies
`AutoCloseable`, also läuft der Pfad nie. Der `instanceof AutoCloseable closeable`
Guard überspringt sofort. Der Code ist forward-looking — designed für eine
zukünftige Strategy mit Subscription-style-Resourcen.

Kein Test verifiziert: (a) dass `closeStrategy` korrekt unterscheidet zwischen
„nicht Closable" (no-op) und „Closable, wirft" (loggt-und-schluckt),
(b) dass nach einem Close-Wurf der Hot-Swap erfolgreich abgeschlossen ist
(neue Strategy installiert, alte „best-effort" beendet).

**Begründung der Priorität:**

Forward-looking Code ohne Test. Nicht heute brennend; lohnt erst ab erster
realer `AutoCloseable`-Strategy.

---

## 2.12.4 — ADR-032-„benign race" zwischen Veto-Check und Field-Write nicht test-gepinnt

**Bereich:** 2.12.4
**Priorität:** nachrangig
**Vorschlag:** keine Aktion — explizit als „benign" und unbedeutend in ADR-032 dokumentiert (Q1, Zeilen 306–326). Race ist designed-acceptable.

**Beobachtung:**

ADR-032 Q1 dokumentiert: zwischen `evaluate(...)`-Veto-Check und dem
Field-Write der neuen Strategy kann ein neuer Acquire die alte Strategy
erreichen und durchlaufen. Designed acceptable, weil Permit-Accounting
isoliert ist. Kein Test reproduziert dies — würde auch synthetic timing
brauchen, weil das Race-Window genau ein CAS und ein Volatile-Write ist.

`BulkheadHotSwapTest.InFlightAcrossSwap.in_flight_call_should_complete_on_old_strategy_after_swap`
testet die ähnliche Garantie für Calls, die *vor* dem Swap acquired haben.
Die symmetrische Garantie für Calls, die *zwischen* Veto und Swap acquired
haben, ist nicht abgedeckt.

**Begründung der Priorität:**

Bewusst akzeptiertes Race, fundamentaler Trade-off im atomaren Hot-Swap-Design.
Nicht-test-gepinnt ist OK.

---

## 2.12.4 — Listener wirft RuntimeException (statt Veto) — Verhalten unbekannt

**Bereich:** 2.12.4
**Priorität:** wichtig
**Vorschlag:** Aufnahme in TODO.md — kleiner Test im `BulkheadHandleListenerTest` würde das Verhalten festschreiben. Falls aktuelles Verhalten unerwünscht ist (Exception propagiert an Update-Caller), eigener Fix-Step.

**Beobachtung:**

`BulkheadHandleListenerTest` testet Listener-Veto, Listener-Survival, mehrere
Listener und Short-Circuit. Es testet nicht, was passiert, wenn ein Listener
selbst eine `RuntimeException` wirft (statt sauber `ChangeDecision.veto(...)`
zurückzugeben). Aus Code-Lesung (`ImperativeLifecyclePhasedComponent.evaluate`,
ADR-028 Veto-Chain) wird das vermutlich an den Dispatcher propagiert, der es an
den `runtime.update(...)`-Caller weiterreicht — also potentiell kein
sauberer Veto, sondern eine Update-Exception.

Da kein Test das pinnt, ist das gewünschte Verhalten unklar:
(a) Wirft → Update fehlschlägt mit Exception (aktueller Defacto-Pfad?).
(b) Wirft → wird als Veto behandelt mit synthetischem Reason
    („listener X threw IllegalStateException").
(c) Wirft → wird geloggt und übergangen, andere Listener weitergeführt.

**Begründung der Priorität:**

Saubere Veto-Chain-Semantik ist wichtig für Operator-vertrauen. Fehlende
Spec ist eine Lücke; aktuelles Verhalten kann gut sein, ist nur nicht
festgeschrieben.

---

## 2.12.5 — `docs/user-guide/bulkhead/bulkhead.md` dokumentiert weder Strategy-DSL noch `strategy`-Feld

**Bereich:** 2.12.5
**Priorität:** wichtig
**Vorschlag:** Aufnahme in 2.13 (Core-Modul-Sweep) — eigentlich genauer ein „User-Guide-Sweep". Eventuell eigener Sub-Step zwischen 2.13 und 2.14, falls die Doku-Arbeit nicht in 2.13 reinpasst.

**Beobachtung:**

Der Bulkhead-User-Guide (`docs/user-guide/bulkhead/bulkhead.md`) wurde
offensichtlich für die neue Architektur aktualisiert (verwendet
`Inqudium.configure().imperative(...).bulkhead(...)`-Form, Presets,
`BulkheadEventConfig`). Aber die Strategy-DSL aus ADR-032 (`semaphore()`,
`codel(...)`, `adaptive(...)`, `adaptiveNonBlocking(...)`) wird **nirgends
erwähnt**. Die Configuration-Reference-Tabelle (Zeilen 113–119) listet `name`,
`maxConcurrentCalls`, `maxWaitDuration`, `tags`, `events` — kein `strategy`-Feld.

Konsequenz: User, der nur die Doku liest, hat keine Möglichkeit zu erfahren,
dass CoDel, Adaptive oder AdaptiveNonBlocking als Wahl existieren. Das gesamte
Strategy-Pattern ist undokumentiert.

Außerdem fehlen:
- Live-Tunability-Regeln (welches Feld kann zur Laufzeit geändert werden,
  welches nicht — die Veto-Mechanik ist relevant für Operator).
- Hot-Swap-Voraussetzungen (zero in-flight für `STRATEGY`-Patches).
- Adaptive-Algorithmen-Wahl (AIMD vs. Vegas).
- Erwähnung der Asymmetrie zwischen `maxConcurrentCalls`-Tuning auf
  Semaphore (geht) und auf nicht-Semaphore (vetoed).

**Begründung der Priorität:**

User-Guide ist die User-Eintritts-Doku. Eine vollständige Strategy-Pattern-
Implementation, die in der User-Doku unsichtbar ist, ist im Sinne der
„Pattern-Vollständigkeit"-Frage von 2.12 nicht wirklich vollständig.

---

## 2.12.5 — `BulkheadHotPhase`-Klassen-Javadoc nennt veraltete Strategy-Hardcodierung

**Bereich:** 2.12.5
**Priorität:** nachrangig
**Vorschlag:** Aufnahme in 2.13 (Core-Modul-Sweep) — kleiner Javadoc-Fix.

**Beobachtung:**

`BulkheadHotPhase.java` Zeile 41:

```
Carries the actual permit-management state — a {@link SemaphoreBulkheadStrategy}
constructed from the snapshot at cold-to-hot transition time.
```

Stand vor 2.10.B. Aktuell konstruiert `BulkheadStrategyFactory` eine von vier
möglichen Strategies. Der Klassen-Javadoc sollte das reflektieren.

Zeile 86 hat ein ähnliches Problem:

```
then propagates {@code maxConcurrentCalls} to the strategy via
{@link SemaphoreBulkheadStrategy#adjustMaxConcurrent}.
```

Das ist nur einer von drei möglichen Pfaden in `onSnapshotChange` (die anderen
sind Strategy-Hot-Swap und no-op-für-non-Semaphore).

**Begründung der Priorität:**

Klein, kosmetisch, aber ein Class-Level-Javadoc, der „carries a Semaphore"
behauptet während der Konstruktor in Wahrheit die Factory anruft, ist eine
unmittelbare Quelle für Verwirrung beim ersten Lesen.

---

## 2.12.5 — Migration-Guide existiert nicht

**Bereich:** 2.12.5
**Priorität:** nachrangig
**Vorschlag:** keine Aktion. README explizit „concept / pre-alpha", REFACTORING.md guiding principle 1: „No backward compatibility, no migration shims. ... The codebase has no external consumers yet". Kein Migrations-User existiert.

**Beobachtung:**

`docs/` enthält keine Migration-Dokument. Plausibel und gewünscht für die
aktuelle Pre-Alpha-Phase. Wäre Phase-3-Thema, sobald externe User existieren
und die alte API tatsächlich Migrationen erfordert.

**Begründung der Priorität:**

Bewusst leer, weil kein User zu migrieren ist.

---

## 2.12.5 — README ist Vision-Doku, kein API-Tutorial

**Bereich:** 2.12.5
**Priorität:** nachrangig
**Vorschlag:** keine Aktion. Bewusste Designentscheidung gemäß README-Inhalt selbst („This repository documents the architecture and vision for Inqudium. ... a blueprint — not a product.").

**Beobachtung:**

Repo-Level-`README.md` enthält keine Code-Samples für die aktuelle DSL,
keinen Bulkhead-Quickstart. Stattdessen Vision-Statement, geplante Module mit
Status-Spalte, Design-Prinzipien. Die einzige Bulkhead-Erwähnung ist im
„Planned elements"-Tabellen-Eintrag mit Symbol `Bh`.

Konsistent mit dem expliziten Pre-Alpha-Status. Tutorial-Stil-Inhalte gehören
in den User-Guide; der existiert (`docs/user-guide/`).

**Begründung der Priorität:**

Bewusste Setzung. Würde sich erst ändern, wenn die Library Release-ready ist.

---

## Post-2.14 — `inqudium-core` declares SLF4J as a compile-scope dependency

**Bereich:** 2.14.3 (cross-cutting, surfaced during the dead-code sweep)
**Priorität:** wichtig
**Vorschlag:** ADR-Audit-Refactor (das nächste REFACTORING.md). Architectural drift between documentation and actual dependencies.

**Beobachtung:**

`inqudium-core/pom.xml` declares `org.slf4j:slf4j-api` with `<scope>compile</scope>`
and `ch.qos.logback:logback-classic` with `<scope>provided</scope>`. The relevant
classes are `eu.inqudium.core.log.Slf4jLoggerFactory` and the `Slf4j*Action`
helpers used by it.

`CLAUDE.md` describes `inqudium-core` as carrying *"JDK-only dependencies"* and
ADR-005 is frequently cited for that constraint. The current dependency
declaration contradicts that statement: SLF4J is on the compile path, which means
every consumer of `inqudium-core` transitively pulls in `slf4j-api` whether they
want logging or not.

Three plausible resolutions, in order of likelihood:

1. **`inqudium-core` should be JDK-only (intent matches doc).** Then the SLF4J
   compile dependency is a bug; `Slf4jLoggerFactory` and `Slf4j*Action` should
   move to `inqudium-context-slf4j` (which is currently effectively empty — see
   companion finding below). `inqudium-core` keeps only the `LoggerFactory`
   abstraction it already exposes.

2. **`inqudium-core` is allowed to carry SLF4J (doc is stale).** Then `CLAUDE.md`
   and any related ADR text need updating. The compile-scope dependency would be
   intentional and documented.

3. **A middle path: `optional=true` or `<scope>provided</scope>`.** The classes
   stay where they are, but consumers do not pull them in transitively. Less
   architecturally pure than option 1, but smaller change.

The architectural-statement version (option 1) is the one that fits the existing
"JDK-only" framing: a resilience library that does not impose a logging framework
on its consumers. The presence of an effectively-empty
`inqudium-context-slf4j` module suggests this was the original intent.

**Begründung der Priorität:**

Documentation and module structure are out of sync. A consumer reading `CLAUDE.md`
or ADR-005 builds a different mental model than what the build files produce.
The fix is mechanical (move two-three classes between modules, adjust pom.xml
scopes); the design decision (which option) is the question for the ADR audit.

---

## Post-2.14 — `inqudium-context-slf4j` is effectively empty

**Bereich:** 2.14.2 (companion to the SLF4J-in-core finding)
**Priorität:** nachrangig
**Vorschlag:** ADR-Audit-Refactor — bundle with the SLF4J-in-core decision; the resolution of the two findings is coupled.

**Beobachtung:**

The `inqudium-context-slf4j` module exists in the multi-module build but contains
no Java sources — only an SPI marker file. If this module is meant to be the
home for the SLF4J bridge (the companion finding above suggests it is), then
the actual bridge code (`Slf4jLoggerFactory`, `Slf4j*Action`) lives in the wrong
place: in `inqudium-core` instead of here.

Resolution paths track those of the SLF4J-in-core finding:

- If `inqudium-core` is to become JDK-only, the bridge moves into this module
  and the module gains its first real content. Consumers who want SLF4J wiring
  add a dependency on `inqudium-context-slf4j` explicitly.
- If `inqudium-core` is to keep SLF4J, this module is redundant and should be
  deleted to match.

Either resolution closes the gap; choosing between them is what the ADR audit
must do.

**Begründung der Priorität:**

A module that contains nothing is either a stub waiting for content or a leftover
that should be deleted. Either way it confuses readers of the build structure.
Routed together with the SLF4J-in-core finding because the same decision settles
both.

---

## Post-2.12 — Algorithm presets are missing from the DSL sub-builders

**Bereich:** 2.12.2 (post-hoc; missed during the original 2.12 audit pass)
**Priorität:** wichtig
**Vorschlag:** Aufnahme in einen neuen Sub-Step **2.16** (Restore algorithm presets in the strategy DSL). The existing 2.16–2.19 sequence renumbers to 2.17–2.20.

**Beobachtung:**

The legacy `AimdLimitAlgorithm` and `VegasLimitAlgorithm` classes carry three named presets
each as static factory methods:

- `AimdLimitAlgorithm.protective()` / `.balanced()` / `.permissive()`
- `VegasLimitAlgorithm.protective()` / `.balanced()` / `.permissive()`

Each preset is a carefully-tuned parameter set with a documented operational archetype:
*protective* prioritizes stability over throughput (initialLimit=20, slow windowed growth,
aggressive 0.5 backoff, tolerant 15% error rate threshold, 5s smoothing); *balanced* is the
production default; *permissive* tolerates higher error rates for non-critical or legacy
services. The Javadoc on each preset includes a parameter table with deliberate values —
this is substantive operational knowledge encoded in code.

The new DSL sub-builders introduced in 2.10.C — `AimdAlgorithmBuilder` and
`VegasAlgorithmBuilder` — expose per-field setters and a single `balanced`-derived default
set, but **do not expose the three named presets**. A user who wants a *protective adaptive
bulkhead* through the DSL must read the legacy class's Javadoc and replicate the parameter
values manually:

```java
// What the user must write today:
.bulkhead("payments", b -> b
    .protective()
    .adaptive(a -> a.aimd(x -> x
        .initialLimit(20)
        .minLimit(1)
        .maxLimit(200)
        .backoffRatio(0.5)
        .smoothingTimeConstant(Duration.ofSeconds(5))
        .errorRateThreshold(0.15)
        .windowedIncrease(true)
        .minUtilizationThreshold(0.5))))

// What the user should be able to write:
.bulkhead("payments", b -> b
    .protective()
    .adaptive(a -> a.aimd(x -> x.protective())))
```

The asymmetry compounds: the top-level `BulkheadBuilder` has `.protective()` /
`.balanced()` / `.permissive()`. The algorithm sub-builders inside `.adaptive(...)` and
`.adaptiveNonBlocking(...)` do not. A user reading the user guide encounters preset
vocabulary at the top level but no preset vocabulary one level deeper, with no explanation
for the asymmetry.

`VegasAlgorithmBuilder.java` carries an explicit comment:
*"Defaults match the `balanced` preset of the deprecated phase-1 `VegasLimitAlgorithmConfigBuilder`
so that `.vegas(v -> {})` produces a usable algorithm out of the box."* — confirming that
the *balanced* preset survived as the default, while *protective* and *permissive* did not
make the migration. This is a Phase-1-migration omission, not a deliberate design choice.

**Begründung der Priorität:**

This is a functional regression of substantive operational knowledge. The presets are not
cosmetic — they encode parameter tuning that a typical user has neither the expertise nor
the time to redo manually. Without them, the adaptive strategies are technically reachable
through the DSL but practically harder to use than they need to be, and the discoverability
of *"protective vs. permissive"* archetypes is lost. Pattern-completeness in the sense of
2.12 requires DSL parity with the legacy surface, not just functional reachability of the
underlying strategies.

---

## Post-2.12 — Audit process: DSL-surface depth was not part of the 2.12 audit

**Bereich:** Audit-process retrospective (no specific 2.12.x bucket)
**Priorität:** nachrangig
**Vorschlag:** keine Aktion. Recorded so the same blind spot does not recur on the next
pattern's audit (Circuit Breaker). Should inform the audit checklist for that effort.

**Beobachtung:**

The 2.12 audit checked whether all four bulkhead strategies are *reachable* through the
DSL — they are. It did not check whether the *depth* of the DSL surface (preset
vocabulary, sub-config richness, parameter coverage) matches the depth of the legacy
surface. The algorithm-preset omission (companion finding above) was therefore missed.

The blind spot has a name: the audit treated the DSL as a binary gate (*"can the user
construct each strategy?"*) rather than as a surface with depth (*"can the user construct
each strategy at the same level of convenience the legacy API offered?"*). The first
question has a yes/no answer; the second is a richer question that requires comparing the
new DSL's setter inventory against the legacy class's Javadoc and static factory methods,
sub-builder by sub-builder.

For the next pattern audit (Circuit Breaker, when its own refactor reaches a similar
maturity), the checklist should include explicitly:

- For each named DSL building block, list every static factory method, named preset, and
  Javadoc-documented archetype on the legacy counterpart.
- Verify that the new DSL exposes each one. If a deliberate omission was made (e.g.,
  *"this preset never made sense in retrospect"*), it must be documented in an ADR or in
  REFACTORING.md, not silently dropped.
- Run this check sub-builder by sub-builder, not just at the top level.

**Begründung der Priorität:**

Process retrospective, not a code defect. The fix is to remember the lesson next time. No
action needed in the bulkhead refactor itself — the companion finding (the actual
preset-restoration) handles the substantive work.
