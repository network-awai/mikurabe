# com-etzhayyim-mikurabe (見比べ)

**mikurabe** — a comparative narrative-framing observer. It takes a
topic-cluster (N already-public **kawaraban** `:mirror` articles grouped by
kawaraban's own mention-edge/section-route graph, or N **kouhou** briefings
on the same official announcement) and observes HOW DIFFERENTLY it was
framed across the compared accounts — never WHAT actually happened, and
never a standing opinion about any of the sources involved. Publishes
citation-grounded technique observations to app-aozora (collection
`com.etzhayyim.apps.mikurabe.frameObservation`).

**DID**: `did:web:etzhayyim.github.io:com-etzhayyim-mikurabe` (depth-1
self-minted `did:key` carried in the published record; RAD identity journal
registration is a superproject-side follow-up, not done by this repo).
**Namespace**: `com.etzhayyim.apps.mikurabe.*`.
**ADR**: `90-docs/adr/2607197800-...edn` (superproject, authoritative) +
`docs/adr/0001-architecture.md` (this repo's own design 正本).
**Status**: R0 scaffold — `mock-advisor` + `mock-publisher`; fixture-only
tests; NO live kawaraban/kouhou data ever read; NO named-party report ever
published. See `MATURITY.md`.
**First-touch channel**: app-aozora (`com.atproto.repo.createRecord`).
**Cross-actor**: reads (passively, never fetches) from **kawaraban** (news
mirror) and **kouhou** (government-PR curator); its gates are derived from
**tashikame** (citation-grounded conclusive-observation precedent),
**danjo** (G3 passive-only ingestion, G4 non-adjudicating, G6 open method,
G10 named-party Council gate), and **ooyake** (G11 no-government-ranking —
mirrored exactly by mikurabe's own most important gate).

## Overview

mikurabe fills the exact gap kawaraban's own `MATURITY.md` flagged as a
deliberate, not-yet-built follow-up: a media/writer actor that analyzes and
interprets what kawaraban/kouhou already collected. But it is scoped
narrowly enough to avoid colliding with two workspace-wide constitutional
boundaries: ooyake's G11 (no ranking governments) and danjo's G4
(non-adjudicating). The unit of comparison is always ONE topic-cluster —
never a source. mikurabe never renders an opinion about a country,
government, or outlet as a standing entity; it only ever observes how ONE
story was framed differently across the accounts it was handed.

This is **containment + independent governor + append-only ledger**: the
intelligence node (`narrative-llm`) is sealed into a single graph node and
returns a *proposal only*; an independent **NarrativeGovernor** censors it;
only `:commit` writes the Store + (when the phase allows) publishes.
Publication is SPEECH, not ACTUATION — mikurabe assesses framing, it never
moves funds, grants access, or actuates anything.

## StateGraph (one topic-cluster comparison = one run)

```
intake → advise(narrative-llm) → govern(NarrativeGovernor) → decide ─┬ commit ────────▶ END
                                                                      ├ escalate ──────▶ request-approval [interrupt-before]
                                                                      │                    resume ─▶ commit | hold
                                                                      └ hold ──────────▶ END
```

| node | role |
|---|---|
| `:advise` | `narrative-llm` (contained) — text-diff/keyword heuristics over the topic-cluster, returns technique observations + citations. Proposal only. |
| `:govern` | `NarrativeGovernor` — independent censor (separate system). |
| `:decide` | HARD violation → `:hold`. Governor-clean + names a party → `:escalate`. Governor-clean + non-named-party → `:commit`. |
| `:request-approval` | paused by `interrupt-before`; a Council reviewer resumes with `:approval`. Even an `:approved` resume still needs the phase-2 Council gate open (two independent locks). |
| `:commit` | writes the observation to Store + append-only ledger; publishes to app-aozora when the phase allows. |
| `:hold` | records the rejection/gate-closure as a hold; no SSoT mutation, no publish. |

## NarrativeGovernor gates

**HARD → HOLD (never publish):**
- `:no-actuation` — proposal `:effect ≠ :assessment`.
- `:uncited-conclusive` — an observation with < 2 distinct compared-item citations (no exception — unlike tashikame's `:unverifiable` escape hatch, mikurabe has none).
- `:malformed-citation` — a cite that does not reference one of the input cluster's own item-ids (mikurabe cannot cite anything it wasn't handed).
- `:country-or-outlet-verdict` — **THE MOST IMPORTANT GATE.** ANY field shaped like a standing country/government/outlet credibility score, regime-type label, propaganda rank, or trustworthiness rating. Structurally scanned, not just documented — see `test/mikurabe/governor_poisoned_fixture_test.clj`. Mirrors ooyake's G11 exactly.
- `:event-truth-verdict` — any `:event/verdict`-shaped field. mikurabe never asserts what actually happened, only how differently it was described (mirrors kawaraban G1 + danjo G4).
- `:person-targeting` — doxing/harassing a private person (institutional scrutiny of a state broadcaster's / government press office's own communications is in-bounds).
- `:catastrophe-veto` — Rider §2 catastrophe-veto scan hit on observation text.

**SOFT → publish with a transparency tag (not a block):**
- `:low-confidence` — overall confidence below floor; the observation still publishes, tagged.

**Named-party publication gate (a PHASE gate, not a governor gate):**
publishing an observation whose text names a specific outlet/country is
Council Lv6+ + SBT-vote gated for R2+ (mirrors danjo's G10
`oversightReport` gate). See `mikurabe.phase` and
`docs/adr/0001-architecture.md` for the two-lock design (approval alone is
insufficient; the phase-2 gate must also be open).

## Phase rollout

| Phase | label | publish? (non-named) | named-party gate |
|---|---|---|---|
| 0 (**default**) | observe | no — shadow-recorded only | closed |
| 1 | autonomous-publish-non-named-party | yes | closed |
| 2 | council-gated-named-party | yes | **open** |

Unlike tashikame (default phase 1, ADR-2606281500 autonomous publication),
mikurabe's default is the most conservative phase: 0. Nothing auto-
publishes at R0/R1, named-party or not, unless a deployment explicitly
raises the phase in its run context.

## Injected seams (each a swap, core unchanged)

- **Store** — `MemStore` ‖ `DatomicStore` (langchain.db `:db-api`) ‖ kotoba-server pod.
- **Advisor** — `mock-advisor` (deterministic keyword/text-diff heuristics — the ONLY advisor shipped at R0) ‖ real LLM on `langchain.model` / Murakumo (documented follow-up, not built here).
- **Publisher** — `MockPublisher` ‖ real app-aozora createRecord (`mikurabe.aozora`, structurally present, not wired by default).
- **Phase** — 0 observe (default) → 1 autonomous-publish-non-named-party → 2 council-gated-named-party.

## The open `:technique` taxonomy

`:omission` / `:loaded-language` / `:source-attribution-gap` /
`:emphasis-divergence` / `:framing-order` — published, open, versioned in
`docs/methodNote.md` (G6-style discipline, mirrors danjo). New categories
require the same open-method publication discipline before use.

## Run

```bash
clojure -M:lint          # clj-kondo, errors fail
clojure -M:dev:test      # cognitect test-runner (canonical)
clojure -M:dev:run       # offline demo (5 representative proposals + resume demo, mock publisher)
```

## Related files

- `docs/adr/0001-architecture.md` — design 正本.
- `docs/methodNote.md` — the open, versioned `:technique` taxonomy.
- `../../../90-docs/adr/2607197800-...edn` — superproject ADR (authoritative spec for this actor).
- `CLAUDE.md` — repo invariants / conventions.
- `MATURITY.md` — honest R0 status: what is real vs. mocked.
