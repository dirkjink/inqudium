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

## 2.17.3 — Lifecycle-Kompatibilität strukturell intakt, aber nicht test-bewiesen

**Bereich:** 2.17.3
**Priorität:** wichtig (positives Finding mit Test-Lücke)
**Vorschlag:** Aufnahme der Test-Coverage in 2.20 (Bulkhead Integration Test Module). Dort
explizit drei Szenarien:
(a) Wrapper über cold→hot-Übergang,
(b) Wrapper über Strategy-Hot-Swap zur Laufzeit,
(c) Wrapper nach struktureller Removal (`ComponentRemovedException`).

**Beobachtung:**

Strukturell sind die Wrapper mit der neuen Architektur kompatibel. Nach ADR-033 implementiert
`InqBulkhead<A, R>` direkt `InqDecorator<A, R>` — die Brücke zwischen Lifecycle-Komponente und
Pipeline-Schicht ist Vertrag, kein Methoden-Referenz-Trick.

Die drei Lifecycle-Szenarien sind im Code korrekt aufgesetzt:

- **Cold→Hot:** Jeder Wrapper-Aufruf landet in `ImperativeLifecyclePhasedComponent.execute(...)`,
  das `phase.get()` jedes Mal frisch liest. `ColdPhase.execute(...)` führt den CAS auf den
  Hot-Phase-Container durch und delegiert. Der Wrapper merkt nichts vom Übergang — aus seiner
  Sicht ist es ein gewöhnlicher `next.execute(...)`-Call.
- **Strategy-Hot-Swap:** `BulkheadHotPhase.strategy` ist `volatile`. `BulkheadHotPhase.execute(...)`
  ruft `tryAcquire(...)` auf, das `strategy` re-liest. Eine via Snapshot-Patch ausgetauschte
  Strategy wird beim nächsten Wrapper-Aufruf aktiv.
- **Removal:** `ImperativeLifecyclePhasedComponent.execute(...)` prüft auf `RemovedPhase` und
  wirft `ComponentRemovedException`. Pro Aufruf, kein Caching beim Wrapper.

Das Problem: kein Test pinnt diese Eigenschaften für die Wrapper-Schicht fest.

- `WrapperPipelineTest`, `SyncPipelineTerminalTest`, `ProxyChainCompositionTest`,
  `InqProxyFactorySyncTest`, `ProxyPipelineTerminalTest`, `AsyncWrapperPipelineTest`,
  `HybridProxyPipelineTerminalTest`, `InqProxyFactoryAsyncTest` etc. nutzen ausschließlich
  synthetische `LayerAction`/`AsyncLayerAction`-Lambdas — kein einziges echtes
  `InqBulkhead`.
- `InqBulkheadTest` testet cold→hot, in-place semaphore tuning, removal — aber nur direkt
  über `bulkhead.execute(...)`. Nie eingebettet in einen Wrapper, nie über einen Proxy.

Wenn morgen jemand einen Caching-Adapter zwischen Bulkhead und Wrapper einführt (z. B.
einen `LayerAction` aus einem konkreten `BulkheadHotPhase` extrahiert statt aus dem
`InqBulkhead`-Handle), gibt es keinen Test, der den Hot-Swap-Verlust auffangen würde.

**Begründung der Priorität:**

Die Lifecycle-Kompatibilität ist heute korrekt. Die Test-Lücke ist die Form, in der die
korrekte Eigenschaft als nicht-load-bearing wahrgenommen wird, bis sie es gewesen wäre.
„Wichtig" statt „kritisch", weil heute keine Code-Änderung nötig ist; aber 2.20 muss die
Eigenschaften ans Test-Netz nageln.

---

## 2.17.4 — Wrapper-/Proxy-Tests verwenden ausschließlich synthetische `LayerAction`s

**Bereich:** 2.17.4
**Priorität:** wichtig
**Vorschlag:** Aufnahme in 2.20 (Bulkhead Integration Test Module). Mindestens je ein Test
pro Wrapper-Familie, der das Wrapping eines via `Inqudium.configure()` aufgebauten
`InqBulkhead` exerciert.

**Beobachtung:**

Eine systematische Sichtung der Wrapper-/Proxy-Tests in beiden Modulen zeigt: keiner baut
eine reale `InqBulkhead` (`Inqudium.configure()...build()` oder ähnlich) und reicht sie an
einen Wrapper / eine `SyncPipelineTerminal` / einen Proxy weiter. Belegstellen
(repräsentativ, nicht erschöpfend):

- `inqudium-core/src/test/java/eu/inqudium/core/pipeline/WrapperPipelineTest.java:43-128`:
  alle Wrapper-Konstruktionen verwenden ad-hoc-Lambdas; `trackingAction(name, log)` als
  hand-rolled `LayerAction`.
- `inqudium-core/src/test/java/eu/inqudium/core/pipeline/proxy/ProxyChainCompositionTest.java:95-317`:
  diverse `LayerAction<Void, Object> innerAction = (chainId, callId, arg, next) -> { … }`,
  keine `InqBulkhead`-Referenz im File.
- `inqudium-imperative/src/test/java/eu/inqudium/imperative/core/pipeline/AsyncWrapperPipelineTest.java:179`:
  hand-rolled `AsyncLayerAction`.
- Weder `decorate*` noch `bulkhead::execute` taucht in irgendeinem Test in
  `inqudium-imperative/src/test/.../bulkhead/` auf.

Im Gegenzug ist die Bulkhead-Lifecycle-Suite (`InqBulkheadTest`, `BulkheadRemovalTest`,
`BulkheadHotPhaseStrategyMaterializationTest`, …) blind für die Wrapper-Schicht — sie ruft
`bulkhead.execute(...)` direkt auf.

Die zwei Test-Welten — Wrapper-Welt und Bulkhead-Welt — überlappen sich nicht. Das ist
genau der Zustand, den 2.20 (laut REFACTORING.md Zeile 772–805) auflösen soll: ein
Integrations-Test-Modul, das den Stack als Ganzes verifiziert.

**Begründung der Priorität:**

Das Findings-Catalog-Item als solches ist nur ein Coverage-Befund, keine sofortige
Code-Drift. Die Priorität „wichtig" ergibt sich daraus, dass diese Lücke der Grund ist, warum
das verwandte 2.17.3-Finding (Lifecycle-Kompatibilität) bisher unauffällig war: ohne
End-to-End-Test schlägt der Drift nicht an.

