# Contributing to Inqudium

Thank you for your interest in Inqudium.

## Current project status

Inqudium is in its **concept and foundation phase**. The architecture is defined, but the codebase is actively being
built and is not yet stable enough to accept external contributions in the form of code.

Core interfaces, package structures, and module boundaries are still in flux. A pull request submitted today may
conflict with fundamental changes that happen tomorrow. To avoid wasting your time and energy, **please do not open pull
requests at this stage.**

This is not a permanent policy — it reflects where the project is right now. Once the foundation is stable (core SPI
compiles, first element has tests, CI pipeline is green), this document will be updated to welcome code contributions.
That milestone will be announced in the repository.

## What is welcome right now

Your perspective is valuable even before the first line of production code is merged:

**Architecture feedback** — Do the design decisions documented in [`docs/adr/`](docs/adr/) make sense for your use
cases? Would the native-per-paradigm approach (ADR-004) solve problems you face in production? Is something missing?

**Use cases and pain points** — What resilience challenges do you encounter that existing libraries don't handle well?
What would make you switch from Resilience4J?

**Design discussions** — Open a [GitHub Issue](https://github.com/inqudium/inqudium/issues) with your thoughts. There
are no bad questions at this stage.

**Bug reports on documentation** — If something in the ADRs, README, or project structure is unclear, contradictory, or
incorrect, please let us know.

You can also reach us directly via email: **hello@inqudium.eu**

## What happens next

Once the project reaches the following milestones, this document will be replaced with a full contribution guide
covering code style, testing conventions, PR process, and more:

- [ ] `inqudium-core` compiles and is published as a snapshot
- [ ] At least one element (Circuit Breaker) has a working implementation with tests
- [ ] CI pipeline (build, test, lint) is operational
- [ ] Code style and testing conventions are documented

Star the repository to be notified when this happens.

---

*This document was last updated on 2026-03-22.*
