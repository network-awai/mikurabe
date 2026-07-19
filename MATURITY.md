# mikurabe 見比べ — Maturity

**Stage: R0, scaffold only.** No live data has ever been read; no record
has ever been published anywhere. Everything below is honest about what is
real (the code, the tests, the structural gates) vs. what is not yet real
(any actual comparative analysis of actual kawaraban/kouhou content).

## What is REAL at R0

- **The full StateGraph** (`mikurabe.operation`) — `intake → advise →
  govern → decide → commit | hold | escalate → request-approval [interrupt-
  before] → resume → commit | hold` — runs end-to-end against a MemStore,
  with real `langgraph-clj` checkpointing (not simulated).
- **The NarrativeGovernor** (`mikurabe.governor`) — all 7 HARD gates +
  1 SOFT gate implement real logic against real proposal data, not stub
  functions. In particular, `:country-or-outlet-verdict` (the single most
  important gate) is a real recursive key-scan over the whole proposal,
  and is proven, not just documented — see
  `test/mikurabe/governor_poisoned_fixture_test.clj`, which feeds it
  several deliberately-poisoned proposals and asserts each is refused.
- **The named-party phase gate** (`mikurabe.phase`) and its two-lock
  design (an `:approved` resume is insufficient without the phase-2
  Council gate also being open) are real, tested logic — see
  `test/mikurabe/operation_test.clj`'s
  `named-party-resume-approved-with-gate-closed-still-holds`.
- **The Store contract** — `MemStore` and `DatomicStore` (via
  `langchain.db`'s in-process EAVT `:db-api`) are both real
  implementations, proven to agree by
  `test/mikurabe/store_contract_test.clj`. `DatomicStore` has never been
  pointed at a real Datomic/kotoba-server pod from this repo — that is a
  configuration-only swap (`langchain.kotoba-db/kotoba-api` instead of
  `langchain.db/api`), not a code change, but it has not been exercised.
- **CACAO self-mint + aozora publish path** (`mikurabe.cacao`,
  `mikurabe.aozora`) — direct, byte-faithful ports of tashikame's own
  proven implementation (which is itself live-verified against
  `pds.aozora.app` — see tashikame's MATURITY/README). The CODE is real;
  it has NEVER BEEN RUN from this repo (no identity has ever been minted
  for mikurabe, `.mikurabe/identity.edn` does not exist anywhere, no
  `createSession`/`createRecord` call has ever been made).
- **The `:technique` taxonomy** (`docs/methodNote.md`) is a real,
  versioned document, and `mikurabe.governor/technique-enum` is the real
  single source of truth both the governor and the lexicon derive from —
  this is not aspirational, `test/mikurabe/advisor_test.clj` checks every
  mock-advisor-emitted technique is a member.

## What is MOCK / NOT YET REAL at R0

- **The advisor** (`mikurabe.advisor/mock-advisor`) is a deterministic
  keyword/text-diff heuristic — headline-equality check, a small
  illustrative loaded-word list, an attribution-marker string search, and
  a present-in-all-but-one-item word-diff. This is NOT real NLP and is NOT
  a real LLM. It has never analyzed a real kawaraban article or a real
  kouhou briefing — every test and demo in this repo uses hand-written
  fixture text. A real advisor wiring `langchain.model` against the
  Murakumo fleet is a documented follow-up (same precedent as
  yosoku's/tashikame's own real-LLM follow-up), not built here.
- **The publisher** is `mikurabe.publisher/mock-publisher` by default and
  in every test/demo in this repo — `mikurabe.operation/build` never
  defaults to the real `mikurabe.aozora/aozora-publisher`. No network call
  has ever been made from this repo's code.
- **No live kawaraban/kouhou data has ever been read.** Every `:items`
  value in every test and in `mikurabe.sim` is hand-written fixture text
  designed to exercise a specific gate. mikurabe has never received an
  actual topic-cluster grouped by kawaraban's real mention-edge graph or a
  real kouhou briefing set. Wiring that real input path (reading
  kawaraban's/kouhou's published records and grouping them into the
  `:items` shape mikurabe expects) is a separate, explicit,
  out-of-scope-for-this-ADR follow-up (ADR-2607197800 `:real-production-
  gate`).
- **No named-party report has ever been published, anywhere, by anyone.**
  The default phase is 0 (observe); even the resume/approval StateGraph
  wiring that WOULD allow a phase-2 publish has only ever been exercised
  against fixture data in `test/mikurabe/operation_test.clj` and
  `mikurabe.sim` — never against a real Council decision.
- **RAD identity / manifest / west registration** — this repo does not
  itself register mikurabe into `etzhayyim/root`'s RAD identity ledger
  (`80-data/kotoba-rad/*.identity.journal.edn`) or the superproject's
  `manifest/west.yml` / `manifest/repos.edn`. Per this build's explicit
  scope, that step is owned by the orchestrating session, not this repo
  (same deferral yosoku's own README documents for itself).
- **The `:person-targeting` and catastrophe-veto scans are R0 marker-string
  heuristics** (`<DOXING>`, `<CAT>`/`<CSAM>`/`<FORCE>`/`<SURVEIL>`), same
  honesty note as tashikame's own R0 scaffold — production would wire the
  canonical `etzhayyim_organism.sensors.charter_rider.scan`, not built
  here.
- **The named-party detector is a substring match** against the input
  cluster's own `:outlet`/`:country` strings — it has no knowledge of
  abbreviations, translations, or paraphrases. It is an honest
  UNDER-detector (never a false positive from external knowledge it
  wasn't handed, but can miss a paraphrase) — see
  `docs/adr/0001-architecture.md` Consequences.

## Bottom line

If asked "has mikurabe analyzed any real news," "has mikurabe published
anything," or "does mikurabe know anything about a real country's press,"
the honest answer at this commit is **no** to all three. What exists is a
fully-tested, structurally-proven SCAFFOLD: the safety rails (the
governor's HARD gates, especially `:country-or-outlet-verdict`) are real
and proven against adversarial fixtures; the actual analytical
capability and the actual publication activity are not yet real.
