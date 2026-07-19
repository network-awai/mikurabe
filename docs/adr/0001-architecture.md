# ADR-0001: mikurabe (見比べ) — comparative narrative-framing observer architecture

**Status**: R0 scaffold (2026-07-19)
**Deciders**: Jun Kawasaki
**Superproject ADR**: `90-docs/adr/2607197800-mikurabe-world-state-media-narrative-comparison-plus-kawaraban-kouhou-world-scope.edn`
(source of truth for the full decision — kawaraban/kouhou world-scope
expansion is a SEPARATE, unrelated part of that same superproject ADR; this
file covers only the mikurabe third).

## Context

The owner asked (2026-07-19): who collects and analyzes news from the
world's state broadcasters, governments, and public bodies, for posting to
app-aozora? Investigation found the COLLECTION side already existed:
**kawaraban** (news mirror, R1 live) and **kouhou** (government-PR curator,
R0) — both charter-unchanged, only their outlet/source registries needed
world-scope expansion (a separate, data-only change, not part of this ADR).

The ANALYSIS/COMPARISON side had a real gap, one kawaraban's own
MATURITY.md had already flagged as a deliberate, not-yet-built follow-up
("a future media/writer actor that would analyze/interpret this primary-
source content"). But the gap could not simply be filled by extending an
existing actor:

- **kawaraban G1** (mirror-not-adjudicator) — kawaraban records that outlet
  X published headline H at T; it structurally cannot render any kind of
  verdict, comparative or otherwise (`:article/verdict` and
  `:article/truth-rating` are `:db/allowed [false]` / lexicon `const
  false`).
- **danjo G4** (non-adjudicating) — danjo's entire discipline is scoped to
  PRE-PUBLISHED OPEN GOVERNMENT DATA (Diet records / procurement / budgets),
  not live news media; conflating the two corpora would blur a
  constitutionally clean boundary danjo's own CLAUDE.md is emphatic about.
- **ooyake G11** ("do not rank governments or take a political position —
  descriptive, neutral") — a standing per-country or per-outlet
  credibility/propaganda score is EXACTLY what G11 exists to make
  structurally impossible workspace-wide.

The owner explicitly confirmed (scope-check, 2026-07-19) that they want
comparative/EVALUATIVE analysis, not merely a passthrough aggregation. The
design problem was therefore: satisfy "comparative/evaluative" without
re-opening the ooyake-G11 / danjo-G4 boundary.

## Decision

**mikurabe** (見比べ — "comparing multiple accounts by looking at them side
by side") — a NEW etzhayyim actor, same containment + independent-governor
+ append-only-ledger pattern as every sibling actor in this workspace
(robotaxi-actor / gftd-talent-actor / cloud-itonami / yosoku / tashikame /
danjo), scoped as narrowly as the comparative/evaluative requirement
allows:

1. **The unit of comparison is ONE topic-cluster, never a source.** A run
   compares N already-public kawaraban `:mirror` articles (grouped by
   kawaraban's OWN existing mention-edge/section-route graph — mikurabe
   invents no new grouping logic) or N kouhou briefings on the same
   official announcement. Every published observation is about how THAT
   ONE story was framed differently — never an aggregate opinion about a
   country, government, or outlet's general character.

2. **Passive-only ingestion (mirrors danjo G3).** mikurabe NEVER fetches an
   outlet or government site directly. It is a strictly downstream
   consumer of what kawaraban/kouhou already made public. This is enforced
   structurally by the `:malformed-citation` gate: every citation must
   reference an `:item-id` mikurabe was actually handed in the request's
   `:items` — there is no code path by which it could cite anything else.

3. **Citation-grounded, no exception (continues the tashikame precedent,
   made stricter).** tashikame's FactGovernor requires ≥1 citation for a
   CONCLUSIVE rating (`:supported`/`:refuted`/`:misleading`); an
   `:unverifiable` rating may have zero. mikurabe has no "unverifiable"
   escape hatch — every published observation, of any technique, needs ≥2
   DISTINCT citations from the compared items. Fewer than 2 is
   `:uncited-conclusive`, HARD-held, same as tashikame.

4. **The `:country-or-outlet-verdict` gate — the single most important
   design decision in this actor.** Rather than trust every advisor
   (mock today, real-LLM tomorrow) to simply never generate a
   standing-entity score, the NarrativeGovernor structurally SCANS every
   key in a proposal (recursively, through every nested map/vector) for
   anything shaped like a country/government/outlet credibility score,
   regime-type label, propaganda rank, or trustworthiness rating
   (`mikurabe.governor/entity-verdict-keys`), and HARD-holds the whole
   proposal if it finds one — regardless of where in the proposal it was
   planted. This is proven, not asserted: `test/mikurabe/
   governor_poisoned_fixture_test.clj` feeds `mikurabe.governor/check`
   several deliberately-poisoned proposals (`:outlet/credibilityScore`,
   `:country/propagandaRank`, `:government/regimeTypeLabel`, a poisoned
   field nested inside a citation) and asserts each is refused. The
   `com.etzhayyim.apps.mikurabe.frameObservation` lexicon additionally
   marks the equivalent fields `const false` at the wire-schema layer
   (`lex/frameObservation.edn`) — the same defense-in-depth kawaraban uses
   for its own G1 (`:article/verdict const false` in both the kotoba schema
   and the lexicon).

5. **`:event-truth-verdict` — mikurabe never adjudicates the underlying
   event (mirrors kawaraban G1 + danjo G4).** No `:event/verdict`-shaped
   field is representable; an observation is always about how the
   COMPARED TEXTS differ, never about which account is factually correct.

6. **Open, versioned method taxonomy (mirrors danjo G6).** The
   `:technique` enum an observation may cite —
   `:omission`/`:loaded-language`/`:source-attribution-gap`/
   `:emphasis-divergence`/`:framing-order` — is published in
   `docs/methodNote.md`. New categories require the same open-method
   publication discipline before use; `mikurabe.governor/technique-enum` is
   the single source of truth both the governor and the lexicon derive
   from.

7. **The named-party publication gate is a PHASE gate, not a governor
   gate.** Whether a governor-clean observation's TEXT happens to name a
   specific outlet/country (`mikurabe.phase/named-party?`, derived
   entirely from the input cluster's own `:outlet`/`:country` values — no
   externally-authored denylist) determines whether it needs Council
   Lv6+ + SBT-vote ratification (mirrors danjo's G10 `oversightReport`
   gate) before it may publish. This is deliberately modeled as a SEPARATE
   authorization concern from the governor's content-safety gates: a
   named-party observation can be perfectly citation-grounded, non-
   entity-verdict, non-event-verdict, non-targeting content that simply
   requires a higher bar of collective sign-off before naming names. The
   StateGraph therefore borrows yosoku's `interrupt-before
   #{:request-approval}` human-in-the-loop mechanism (not tashikame's,
   which has none — tashikame's autonomous-publication doctrine,
   ADR-2606281500, does not apply here because mikurabe's named-party case
   is precisely the kind of decision that doctrine's own G10-style
   siblings still gate).

   A DELIBERATE two-lock design: even an `:approved` resume at
   `:request-approval` is insufficient by itself — `mikurabe.operation`
   ALSO checks `mikurabe.phase/council-gate-open?` (phase ≥ 2) before
   honoring the approval. This means no single code path (a compromised or
   over-eager approval flow) can publish named-party content without the
   deployment's phase ALSO having been explicitly raised to 2 by whoever
   controls the actor's run context. See
   `test/mikurabe/operation_test.clj`'s
   `named-party-resume-approved-with-gate-closed-still-holds`.

8. **The default phase is 0 (observe) — more conservative than
   tashikame's default of 1.** tashikame's ADR-2606281500 (種をまく)
   establishes autonomous publication as the norm for citation-grounded
   fact-check speech. mikurabe does not inherit that norm outright: per
   ADR-2607197800's explicit honesty requirement ("R0 ships scaffold only
   ... phase defaults to 0 observe — never auto-publishing named-party
   content"), `mikurabe.phase/default-phase` is 0. A future deployment
   entrypoint that wants phase-1 autonomous-publish-of-non-named behavior
   must set it explicitly in the run context, exactly like tashikame's own
   `sim.cljc`/`deploy.clj` do for phase 1 today.

9. **Injected seams**, same shape as every sibling: Store (`MemStore` ‖
   `DatomicStore` via `langchain.db` `:db-api` ‖ kotoba-server) / Advisor
   (`mock-advisor`, the ONLY advisor shipped at R0 — a real advisor wiring
   `langchain.model` against Murakumo is a documented follow-up, never
   faked here) / Publisher (`MockPublisher` default ‖ real app-aozora
   `createRecord` via `mikurabe.aozora`, structurally present but not
   wired by default) / Phase (0 observe → 1 autonomous-publish-non-named →
   2 council-gated-named-party).

10. **Self-sovereign identity.** `mikurabe.cacao` (direct port of
    `tashikame.cacao`, itself ported from `tsumugu.cacao`) generates +
    persists the actor's own Ed25519 key; `mikurabe.aozora` mints a
    depth-1 CACAO to publish. Private key in `.mikurabe/identity.edn`
    (gitignored) — never committed.

11. **`.cljc` portable.** Core (phase/governor/advisor/publisher/
    operation/store/sim) is `.cljc` (JVM/SCI/cljs/WASM); `.clj` only for
    JVM-only I/O (cacao, aozora) — same split as tashikame.

## Consequences

- (+) A topic-cluster's framing differences can be observed and (once a
  deployment explicitly raises the phase) published without re-opening the
  ooyake-G11 or danjo-G4 constitutional boundaries — the design is
  narrowest-possible while still satisfying "comparative/evaluative".
- (+) The `:country-or-outlet-verdict` gate is proven structurally, not by
  convention — an advisor (including a future real-LLM advisor with no
  guaranteed discipline of its own) cannot smuggle a standing-entity
  verdict past the governor no matter where in the proposal it is placed.
- (+) The two-lock named-party design means raising the deployment phase to
  2 is necessary but not sufficient (an actual `:approved` resume is still
  required), and an approval is necessary but not sufficient (the phase
  gate must also be open) — no single control failure publishes
  named-party content.
- (−) R0 `mock-advisor` is a keyword/text-diff heuristic, not real NLP —
  same honesty scope as every sibling R0 mock advisor. Real assessment
  needs a `langchain.model`-backed advisor wired at deploy, matching
  tashikame's/yosoku's own real-LLM follow-up pattern (not built here).
  R0's catastrophe-veto denylist is illustrative, same note as tashikame's
  own R0 scaffold.
- (−) `:person-targeting` is an R0 marker-string heuristic (`<DOXING>`),
  same limitation tashikame's own ADR-0001 notes for its own gate;
  production needs a richer NLP pass to distinguish institutional
  scrutiny from private-person doxing beyond a literal marker.
- (−) The named-party detector (`mikurabe.phase/named-party?`) is a
  substring match against the input cluster's own outlet/country strings —
  it will miss a paraphrase that names a party without using the exact
  string mikurabe was handed (e.g. an abbreviation or translation), and it
  is a HONEST under-detector, not an over-detector: false negatives are
  possible, false positives are not (it never claims a party is named
  based on external knowledge it wasn't handed).

## Alternatives considered

(Reproduced from the superproject ADR-2607197800, which is authoritative;
restated here for this repo's own local record.)

- **Extend kawaraban itself to also emit comparative verdicts** — rejected.
  kawaraban's G1 is a schema-level `const false` gate; weakening it to
  allow ANY verdict-shaped field would compromise the charter that makes
  kawaraban trustworthy as a pure mirror.
- **Extend danjo to cover news-media framing** — rejected. danjo's entire
  non-adjudicating discipline (G1-G5) is scoped to pre-published open
  government data, not live news media; conflating the two corpora would
  blur a currently very clean constitutional boundary.
- **Build a country/outlet credibility-scoring actor** (the owner's stated
  preference read literally, "go as far as comparative/evaluative
  analysis") — rejected. A standing per-country or per-outlet
  credibility/propaganda score is exactly what ooyake's G11 was written to
  make structurally impossible workspace-wide. Scoping mikurabe to
  per-topic-cluster framing-technique observations (cited, versioned open
  method, never an aggregate entity score) is the narrowest design that
  still satisfies "comparative/evaluative" without re-opening that
  constitutional boundary.
- **Route named-party content through tashikame's autonomous-publication
  doctrine (no interrupt-before at all)** — rejected. ADR-2606281500 (種を
  まく) is about NOT requiring per-post prior restraint for ordinary
  citation-grounded speech; naming a specific state broadcaster or
  government in a comparative report is precisely the kind of act this
  workspace's other actors (danjo G10, its own `oversightReport` gate)
  treat as needing collective sign-off, not individual-actor autonomy. A
  yosoku-style `interrupt-before` escalate path was adopted instead,
  layered with a second independent phase-gate lock so that neither
  mechanism alone is sufficient.
