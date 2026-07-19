# mikurabe methodNote — the `:technique` taxonomy (open, versioned)

**Status**: v1 (R0, 2026-07-19). **Discipline**: G6-style (mirrors danjo's
own methodNote convention — "every detector heuristic is published as a
methodNote, open, versioned. No closed/secret scoring. The public audits
the detector, not only its output."). This document IS the audit surface;
`mikurabe.governor/technique-enum` is the single source of truth the
governor and the `com.etzhayyim.apps.mikurabe.frameObservation` lexicon
(`lex/frameObservation.edn`) both derive from — if you add a category, add
it here FIRST, then in the enum, then in the lexicon, in that order.

A `:frameObservation`'s `:observations` may only cite one of the five v1
categories below. **New categories require this same open-method
publication discipline before use** — a closed or undocumented technique
value is not just discouraged, `mikurabe.governor/technique-enum` makes it
impossible for the mock advisor's output to be considered valid by any
test that checks membership (see `test/mikurabe/advisor_test.clj`
`every-observation-technique-is-in-the-published-enum`).

## v1 categories

| technique | what it means | what disqualifies it from being conclusive |
|---|---|---|
| `:omission` | a fact/word present in the majority of the compared items' excerpts is absent from one | needs ≥2 distinct cites (the item that has it + the item that doesn't) — an `:omission` claim about a SINGLE unconfirmed excerpt with nothing to compare against is uncited-conclusive |
| `:loaded-language` | one compared item's excerpt uses a charged/loaded term not present in another compared item's excerpt for the same story | the term itself must appear verbatim in the cited item's own excerpt (not paraphrased by the advisor) |
| `:source-attribution-gap` | one compared item's excerpt carries a claim with no attribution marker (`"according to"`, `"officials said"`, …) while another compared item's excerpt does attribute its account | this is about the PRESENCE of an attribution marker in the excerpt text mikurabe was handed, not a claim about whether the underlying fact is true (that would be `:event-truth-verdict` — forbidden) |
| `:emphasis-divergence` | the compared accounts lead with (or otherwise emphasize) a different fact/frame for the same underlying story | shown as a neutral side-by-side citation of both headlines/leads — never phrased as one being "wrong" |
| `:framing-order` | the compared accounts differ in which fact comes first / which is chosen as the headline | distinct from `:emphasis-divergence` in intent (ordering/selection, not which fact is stressed once mentioned) — v1 treats these as related but separate categories to keep each testable independently |

## What every category explicitly EXCLUDES (the reason mikurabe can exist at all)

None of the five categories may, by construction, be used to:

1. **Score, rank, or label a country/government/outlet as a standing
   entity** (a credibility score, a regime-type classification, a
   propaganda rank, a trustworthiness rating). This is the
   `:country-or-outlet-verdict` HARD gate (`mikurabe.governor`) — see
   `test/mikurabe/governor_poisoned_fixture_test.clj` for the structural
   proof that a field shaped like this is refused even if an advisor tries
   to attach one. It mirrors ooyake's G11 ("do not rank governments or take
   a political position — descriptive, neutral") exactly, made concrete
   for narrative comparison.
2. **Assert what actually happened** in the underlying event. A
   `:source-attribution-gap` or `:omission` observation is about what the
   COMPARED TEXTS say (or don't say), never about which account is
   factually correct. That would be an `:event/verdict`-shaped field — the
   `:event-truth-verdict` HARD gate refuses it (mirrors kawaraban G1 +
   danjo G4).

## Adding a new technique category

1. Document it here (name, definition, disqualification rule) — this
   section, in a new PR, before any code change.
2. Add the keyword to `mikurabe.governor/technique-enum`.
3. Add it to the `enum` array in `lex/frameObservation.edn`'s
   `observations.items.properties.technique`.
4. Only then may `mikurabe.advisor` (mock or a future real advisor) emit
   it.

No category may be added that would require a field shaped like a
standing-entity verdict to be represented — if a proposed new category
cannot be expressed as "a technique observed in THIS topic-cluster's
framing, cited from the compared items' own text", it does not belong in
this taxonomy and does not belong in mikurabe at all (see
`docs/adr/0001-architecture.md` "Alternatives considered").
