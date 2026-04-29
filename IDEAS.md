# IDEAS.md

A parking place for forward-looking ideas about Inqudium's evolution. Each entry sketches a
direction without committing to a timeline. Some entries may turn into ADRs and concrete work;
others may sit here indefinitely, or be removed when reality has moved past them.

The bar for adding an entry is low: a thought worth not losing. The bar for taking one out and
turning it into actual work is much higher and lives elsewhere.

---

## Time-scheduled configuration profiles

Inqudium's `runtime.update(...)` mechanism lets the application change configuration at runtime
through validated, vetoable patches. A natural extension is to let the *system itself* trigger
those updates on a schedule:

- *"Daytime: 100 permits. Night: 20 permits."*
- *"Weekdays: semaphore strategy. Weekends: adaptive."*
- *"Black-Friday window from 2024-11-29 00:00 for 72 hours: elevated limits for the
  `payments` bulkhead."*
- *"Q4 2024: temporarily relaxed rules for the `inventory` bulkhead."*

These are all instances of the same pattern: a *schedule* triggers *configuration updates* at
predefined times. The mechanism is not a new architecture — it is a new *consumer* of the
existing `runtime.update(...)` API. A scheduler component evaluates time-based triggers and
calls `runtime.update(...)` with the appropriate patches.

The architecture lines up well with this idea:

- The veto chain applies automatically. Listeners can veto a schedule-triggered patch the
  same way they veto a manual one. The scheduler is not privileged.
- Live-tunability checks apply automatically. A schedule that tries to set
  `MAX_CONCURRENT_CALLS` on a CoDel-strategy bulkhead gets the same `VETOED` outcome as a
  manual attempt.
- Topology events fire automatically. Operations tooling cannot tell a schedule-driven update
  apart from a manual one — both produce `RuntimeComponentPatchedEvent`.
- `dryRun(...)` becomes a validation tool for schedules: before activating a schedule, the
  caller can dry-run its patch and inspect the would-be `BuildReport`.

Open design questions, when the time comes:

**Schedule definition shape.** Where do schedules get configured?

- *Statically at build time:* `Inqudium.configure().schedule(s -> s.daily(LocalTime.of(18, 0), update -> update.imperative(...)))`. The schedule is part of the initial configuration. Advantage: everything in one place, the schedule itself can be validated at build time. Disadvantage: schedule changes require a restart.
- *Dynamically at runtime:* `runtime.scheduler().add(schedule)`. Schedules can be added and removed at runtime. Advantage: operational flexibility. Disadvantage: schedules are themselves not snapshot-able and live outside the update mechanism.
- *Both:* static schedules at build time plus an operations API for runtime additions and removals. Probably the right shape for a mature library.

**Schedule granularity.** Which time patterns are supported?

- *Cron-style expressions:* maximum convenience for power users, but cron expressions are notoriously error-prone.
- *Structured builder API:* `s.dailyAt(LocalTime.of(18, 0))`, `s.between(LocalDateTime.of(...), Duration.ofHours(72))`, `s.onWeekdays(MONDAY, FRIDAY).at(LocalTime.of(9, 0))`. More readable, but every supported pattern has to be designed deliberately.
- *Both side by side:* cron for power users, builder for the common cases.

**Configuration form.** How are profiles described?

- *Patches:* every schedule trigger event is a `runtime.update(...)` call with a patch. Clear, but for "daytime A, nighttime B" you have to define *two* patches — A at sunrise, B at sunset. Implication: after a restart in the middle of the day, the system does not know which patch was last active.
- *Profiles:* named configuration sets (`day-profile`, `night-profile`) and schedules switch between profiles. Cleaner mental model, but it introduces a new concept into the vocabulary.
- *Hybrid:* schedules reference either a profile name or an ad-hoc patch.

**Schedule conflict resolution.** What happens when two schedules trigger at the same moment?

- *Last-writer-wins:* the schedule evaluated last wins. Simple, but tied to evaluation order.
- *Priority-based:* schedules carry a priority; higher wins. More discipline required.
- *Veto-resolution:* both schedules emit their patches and the veto chain decides which one survives. Architecturally clean, but surprising for an operator (*"why did my schedule not take effect?"* → *"because another schedule fired first and a listener vetoed the second one"*).

**Restart behaviour.** On startup — which schedule state is correct?

- The scheduler must, at startup, evaluate *all* schedules and activate whichever fits the current time. *"It is 22:30, so apply the night profile."* This is a requirement, not a bonus — otherwise a restart in the middle of the night would leave the default daytime mode active.
- It follows that schedules must be *idempotent*. When the scheduler applies the night profile at startup and a later trigger event re-applies the night profile, the update must produce an `UNCHANGED` outcome rather than a duplicate apply. Fortunately the patch system is *already* idempotent (`UNCHANGED` is an established outcome). This falls out for free.

**Time source discipline.** The scheduler has to take its `Clock` from `GeneralSnapshot.clock()` so tests can inject a fixed clock. Already available; just a constraint to honour.

When this becomes concrete work, it warrants its own ADR. The design surface is rich enough
that "we'll figure it out as we go" would produce a tangle, not a feature.
