(ns mikurabe.phase
  "Phase 0→1→2 staged rollout for mikurabe (見比べ). Unlike tashikame's
  default-phase=1 (ADR-2606281500, 種をまく — autonomous publication is the
  norm for citation-grounded fact-check speech), mikurabe's DEFAULT is the
  most conservative phase: 0 (observe). Per ADR-2607197800, this actor SHIPS
  AS SCAFFOLD ONLY at R0/R1 — nothing publishes to app-aozora unless a
  caller explicitly raises `:phase` in the run context, and even then a
  NAMED-PARTY observation (one whose text names a specific outlet/country
  from the input cluster) needs phase 2 — the Council Lv6+ + SBT-vote gate
  (mirrors danjo's G10 `oversightReport` gate) — regardless of how the
  caller's phase was set.

    Phase 0  observe                      — governor-clean observations
                                             recorded to the ledger but NOT
                                             published (shadow / observe).
                                             DEFAULT.
    Phase 1  autonomous-publish-non-named — governor-clean, NON-named-party
                                             observations publish
                                             autonomously; named-party
                                             observations still escalate.
    Phase 2  council-gated-named-party    — the named-party Council gate is
                                             OPEN; an escalated observation
                                             that has actually been approved
                                             (`mikurabe.operation`'s
                                             `:request-approval` node) may
                                             publish.

  `publish-allowed?` governs ordinary (non-named-party) publication.
  `council-gate-open?` is the SEPARATE, additional lock `:request-approval`
  checks before honoring even an explicit approval — an approval granted
  while the gate is closed (phase < 2) still results in a hold. This is a
  PHASE gate, not a per-proposal NarrativeGovernor gate (see
  `mikurabe.governor` — the governor's HARD/SOFT gates are about the
  observation's *content*; this module is about *who is authorized to see it
  published and when*)."
  (:require [clojure.string :as str]))

(def phases
  {0 {:label "observe"                          :publish? false}
   1 {:label "autonomous-publish-non-named-party" :publish? true}
   2 {:label "council-gated-named-party"          :publish? true}})

(def default-phase
  "Conservative by design (ADR-2607197800 honesty ladder): R0/R1 never
  auto-publishes anything, named-party or not, unless a caller explicitly
  opts a run context into a higher phase."
  0)

(defn publish-allowed?
  "Whether a governor-clean, NON-escalated observation may publish at all."
  [phase]
  (:publish? (get phases phase (get phases default-phase))))

(defn council-gate-open?
  "Whether the named-party Council-ratification gate (phase 2) is open. An
  `:approved` resume at phase < 2 must still result in a hold — this is the
  second, independent lock `mikurabe.operation`'s `:request-approval` node
  applies on top of the approval itself."
  [phase]
  (>= (or phase default-phase) 2))

(defn- distinct-party-strings
  "Every non-blank `:outlet`/`:country` string mikurabe was actually HANDED
  in the input topic-cluster `items` — never an externally-authored
  denylist of country/outlet names. This keeps named-party detection
  self-derived from what the actor was given, matching its passive-only
  ingestion discipline (it cannot know about a party it wasn't shown)."
  [items]
  (->> items
       (mapcat (juxt :outlet :country))
       (filter string?)
       (map str/trim)
       (remove str/blank?)
       distinct))

(defn named-party?
  "True iff `text` names a specific outlet/country present among the input
  cluster `items`. This is the detector behind the named-party PHASE gate
  (ADR-2607197800 :named-party-gate) — publishing text that names a party is
  Council Lv6+ + SBT-vote gated (R2+); mikurabe's own generic per-technique
  descriptions never name a party by construction (see
  `mikurabe.advisor`), so this only trips when an advisor's proposal text
  actually composes a named comparison."
  [text items]
  (let [t (str/lower-case (or text ""))]
    (boolean (some #(str/includes? t (str/lower-case %))
                   (distinct-party-strings items)))))

(defn observations-name-party?
  "True iff ANY observation in `observations` names a party. A run's record
  aggregates all detected technique observations about ONE topic-cluster
  into a single published frameObservation — if any one of them names a
  party, the whole record needs the Council gate (favor caution over
  splitting a single comparison into a partially-gated, partially-open
  publish)."
  [observations items]
  (boolean (some #(named-party? (:description %) items) observations)))
