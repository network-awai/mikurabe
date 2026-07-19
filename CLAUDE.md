# com-etzhayyim-mikurabe

mikurabe (見比べ) — comparative narrative-framing observer. See `README.md`
for the core contract and full-repo `../../../CLAUDE.md` "Actors" section
for the pattern this follows (containment + independent governor +
append-only ledger). Superproject decision record:
`../../../90-docs/adr/2607197800-mikurabe-world-state-media-narrative-comparison-plus-kawaraban-kouhou-world-scope.edn`.
Design 正本: `docs/adr/0001-architecture.md`. Open method taxonomy:
`docs/methodNote.md`.

## Invariant

mikurabe NEVER publishes an observation the NarrativeGovernor rejects.
Every published observation cites ≥2 of the compared topic-cluster items'
own excerpts — no exception (unlike tashikame, there is no
`:unverifiable`-style escape hatch for an uncited claim; an observation
either has ≥2 citations or it does not publish). Catastrophe-veto /
person-targeting / uncited-conclusive / malformed-citation / no-actuation
proposals are HELD — recorded as a hold in the append-only ledger, never
published.

**The single most important invariant in this repo**: NO field, anywhere
in a proposal, may score, rank, or label a country/government/outlet AS A
STANDING ENTITY (a credibility score, a regime-type label, a propaganda
rank, a trustworthiness rating — anything that persists an opinion about a
SOURCE rather than about ONE topic-cluster's framing). This is enforced
structurally by `mikurabe.governor/entity-verdict-keys`, a recursive scan
over every key in a proposal, and proven by
`test/mikurabe/governor_poisoned_fixture_test.clj` — if you touch
`mikurabe.governor`, re-run that test file specifically and confirm every
poisoned-fixture case still fails to publish. This mirrors ooyake's G11
("do not rank governments or take a political position — descriptive,
neutral") exactly; do not weaken it, do not add an "admin override", do not
special-case it for a "trusted" advisor.

Similarly, no field may assert what ACTUALLY HAPPENED in the underlying
event (`:event-truth-verdict` gate, mirrors kawaraban G1 + danjo G4) —
mikurabe compares framing, it never adjudicates truth.

The named-party publication gate (publishing text that names a specific
outlet/country) is a SEPARATE phase-level lock
(`mikurabe.phase/council-gate-open?`), not a governor gate — see
`docs/adr/0001-architecture.md` §7 for why, and its two-lock design (an
`:approved` resume at `:request-approval` is insufficient by itself; the
phase-2 gate must also be open).

## Passive-only ingestion

mikurabe NEVER fetches an outlet or government site directly — it is a
strictly downstream consumer of what kawaraban/kouhou already made public
(mirrors danjo's G3 passive-only discipline). This is enforced by the
`:malformed-citation` gate: every citation must reference an `:item-id`
mikurabe was actually handed in the request's `:items`. Do not add a code
path that fetches a URL from a `:cites` entry or an `:items` entry — that
would break the passive-only invariant this actor's whole cross-actor
positioning depends on.

## Conventions

- `.cljc` for anything portable (phase/governor/advisor/publisher/
  operation/store/sim) — `.clj` only for JVM-only I/O (cacao, aozora),
  same split as tashikame.
- `mikurabe.cacao` is a direct port of `tashikame.cacao` (itself a port of
  `tsumugu.cacao` — self-sovereign Ed25519 identity + CACAO mint);
  `mikurabe.aozora` is the real app-aozora Publisher over
  `com.atproto.repo.createRecord`, structurally present but NOT wired by
  default (`mikurabe.publisher/mock-publisher` is the default in
  `mikurabe.operation/build`).
- The actor's own Ed25519 identity lives in `.mikurabe/identity.edn`
  (gitignored) — NEVER commit a private key.
- `deps.edn` points at `kotoba-lang/langgraph` + `kotoba-lang/langchain`
  (the current canonical coordinates), NOT the older
  `com-junkawasaki/langgraph-clj`/`langchain-clj` paths tashikame's and
  yosoku's own `deps.edn` still reference — those checkouts are retired
  locally (repo-naming "no -clj suffix" convention, root CLAUDE.md). If you
  see stale sibling actors' deps.edn referencing the old path, that's a
  pre-existing gap in THOSE repos, not something to copy into new ones.
- `clojure -M:lint` (clj-kondo, errors fail) / `clojure -M:dev:test`.
- Adding a new `:technique` category: update `docs/methodNote.md` FIRST,
  then `mikurabe.governor/technique-enum`, then
  `lex/frameObservation.edn`'s enum — in that order (see
  `docs/methodNote.md` "Adding a new technique category").

## R0 honesty (do not overstate)

Mock advisor + mock publisher only. No live kawaraban/kouhou data has ever
been read by this repo. No named-party report has ever been published — R0
default phase is 0 (observe); nothing auto-publishes regardless of
named-party status unless a deployment explicitly raises the phase. See
`MATURITY.md` before describing this actor's capabilities to anyone.
