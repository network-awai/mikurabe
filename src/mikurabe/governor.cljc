(ns mikurabe.governor
  "NarrativeGovernor — the independent censor that earns a narrative-llm
  proposal the right to publish. SEPARATE system from narrative-llm (defense
  in depth: never trust the generator to have been the only gate). Mirrors
  tashikame.governor's FactGovernor shape (HARD → HOLD, no override; SOFT →
  recorded warning, still publishes) and continues its citation-grounded
  precedent, but is scoped MUCH narrower than a general verdict engine: per
  ADR-2607197800, mikurabe may only ever observe how differently ONE
  topic-cluster was framed, never render a standing opinion about a country,
  government, or outlet, and never assert what actually happened in the
  underlying event.

  HARD (never publish — recorded as a hold):
    :no-actuation             proposal :effect ≠ :assessment (mikurabe only
                               assesses framing, never actuates)
    :uncited-conclusive       an observation citing FEWER than 2 of the
                               compared items' own excerpts (mirrors
                               tashikame's uncited-conclusive gate — EVERY
                               published observation needs ≥2 distinct
                               citations, no exception)
    :malformed-citation       a cite that is not a direct reference to one
                               of the input topic-cluster's own items —
                               mikurabe never fetches independently
                               (danjo G3-style passive-only discipline), so
                               it structurally cannot cite anything it
                               wasn't handed as input
    :country-or-outlet-verdict  ANY field on the proposal shaped like a
                               standing per-country/per-outlet credibility
                               score, regime-type label, propaganda rank, or
                               trustworthiness rating — mirrors ooyake's G11
                               (\"do not rank governments or take a
                               political position — descriptive, neutral\")
                               EXACTLY, made concrete for narrative
                               comparison. THE MOST IMPORTANT GATE — see
                               `entity-verdict-keys` below and
                               `test/mikurabe/governor_poisoned_fixture_test.clj`
                               for the structural proof.
    :event-truth-verdict      ANY `:event/verdict`-shaped field — mikurabe
                               never asserts what actually happened in the
                               underlying event, only how differently it was
                               described (mirrors kawaraban G1 +
                               danjo G4). See `event-verdict-keys` below.
    :person-targeting         observation doxes/harasses a private person
                               (not institutional scrutiny — comparing
                               STATE BROADCASTERS' / GOVERNMENT PRESS
                               OFFICES' own communications is explicitly
                               in-bounds, tashikame precedent)
    :catastrophe-veto         Rider §2 catastrophe-veto scan hits on
                               observation text

  SOFT (publish with tag, not a block):
    :low-confidence           overall confidence < floor

  The named-party publication gate (Council Lv6+ + SBT-vote for R2+) is
  DELIBERATELY NOT a governor gate here — it is a PHASE gate
  (`mikurabe.phase`), because it is about WHO may see a governor-clean
  observation published and WHEN, not about whether the observation's
  content is safe to have generated at all."
  (:require [clojure.string :as str]))

(def confidence-floor 0.4)

(def technique-enum
  "The open, versioned :technique taxonomy a :frameObservation may cite
  (G6-style discipline, mirroring danjo's open methodNote convention). See
  `docs/methodNote.md` for the published, human-readable spec. New
  categories require the same open-method publication discipline before
  use — this def is the single source of truth the governor and lexicon
  both derive from."
  #{:omission :loaded-language :source-attribution-gap :emphasis-divergence
    :framing-order})

(def ^:private catastrophe-denylist
  "Rider §2 catastrophe-veto surface — illustrative denylist for R0 (same
  honesty note as tashikame's own R0 denylist: production wires the
  canonical `etzhayyim_organism.sensors.charter_rider.scan`). The markers
  below drive the contract test."
  ["<CAT>" "<CSAM>" "<FORCE>" "<SURVEIL>"])

(defn- obs-blob [observations ks]
  (->> observations (mapcat #(map % ks)) (filter string?) (str/join " ")))

(defn- catastrophe? [observations]
  (let [blob (obs-blob observations [:description :note])]
    (some #(str/includes? blob %) catastrophe-denylist)))

(defn- person-targeting? [observations]
  ;; R0 heuristic: an observation note that explicitly targets a named
  ;; private person with harassment. Institutional scrutiny (a state
  ;; broadcaster's or government press office's OWN communications) is NOT
  ;; targeting. The marker drives the test; production uses a richer NLP
  ;; pass (same honesty note as tashikame's own R0 heuristic).
  (str/includes? (obs-blob observations [:description :note]) "<DOXING>"))

(defn- distinct-cited-item-ids [observation]
  (->> observation :cites (map :item-id) (filter some?) distinct))

(defn- valid-cite? [cite item-ids]
  (and (map? cite)
       (contains? item-ids (:item-id cite))
       (string? (:quote cite))
       (not (str/blank? (:quote cite)))))

;; ───────────────────────── schema-level standing-entity-verdict scan ─────
;; THE MOST IMPORTANT GATE. Any field ANYWHERE in the proposal shaped like a
;; standing opinion about a country/government/outlet AS AN ENTITY (rather
;; than about ONE topic-cluster's framing) is refused. This is a structural
;; scan over every map key in the proposal, not a docstring promise — see
;; `test/mikurabe/governor_poisoned_fixture_test.clj` for the proof that a
;; deliberately-poisoned proposal (a fabricated country-ranking field) is
;; actually held.

(defn- collect-keys
  "Every map key found anywhere in `v`, recursively through maps and any
  other collection (vectors/lists/sets) — dependency-free (no
  `clojure.walk` needed) so this stays trivially portable to JVM / SCI /
  ClojureScript / WASM, matching this workspace's zero-dep .cljc ethos."
  [v]
  (cond
    (map? v)  (concat (keys v) (mapcat collect-keys (vals v)))
    (coll? v) (mapcat collect-keys v)
    :else     nil))

(defn- key->str [k]
  (when (keyword? k)
    (str (some-> (namespace k) (str "/")) (name k))))

(def ^:private standing-entity-terms
  "A key naming a country/government/outlet AS A STANDING ENTITY."
  #"(?i)country|nation|govern?ment|regime|outlet|broadcaster|\bmedia\b|\bsource\b|\bstate\b")

(def ^:private verdict-shape-terms
  "A key shaped like a score/rank/rating/label being attached to that
  entity."
  #"(?i)credibilit|propagand|trustworth|regime.?type|bias.?index|reliabilit|\brank\w*|\bscor\w*|\brating\b|\bgrade\b")

(defn- entity-verdict-key?
  "A key is poisoned iff it names a standing entity (country/government/
  outlet/…) AND is shaped like a score/rank/rating/label — e.g.
  `:outlet/credibilityScore`, `:country/propagandaRank`,
  `:government/regimeTypeLabel`, `:media/trustworthinessRating`. Neither
  term alone trips this (a plain `:outlet \"BBC\"` field, or a
  `:technique :source-attribution-gap` VALUE, is untouched) — only the
  conjunction of \"this is about a standing entity\" and \"this is a
  score/rank/label\" on the SAME key."
  [k]
  (when-let [s (key->str k)]
    (and (re-find standing-entity-terms s) (re-find verdict-shape-terms s))))

(defn entity-verdict-keys
  "Any proposal key shaped like a standing per-country/per-outlet
  credibility/rank/regime-type verdict — the ooyake-G11-mirroring
  schema-level ban, made a real, callable, testable function (not just a
  docstring). Exposed publicly so a poisoned-fixture test can assert it
  actually finds a planted field."
  [proposal]
  (->> proposal collect-keys distinct (filter entity-verdict-key?) vec))

;; ───────────────────────── schema-level :event/verdict scan ──────────────

(def ^:private event-verdict-terms #"(?i)event.?(verdict|truth)")

(defn- event-verdict-key? [k]
  (when-let [s (key->str k)] (re-find event-verdict-terms s)))

(defn event-verdict-keys
  "Any proposal key shaped like an underlying-event truth verdict (e.g.
  `:event/verdict`, `:event-truth`) — mikurabe never asserts what actually
  happened, only how differently it was described (mirrors kawaraban G1 +
  danjo G4)."
  [proposal]
  (->> proposal collect-keys distinct (filter event-verdict-key?) vec))

(defn check
  "Censors a narrative-llm proposal. Returns
  {:ok? :violations [hard] :warnings [soft] :confidence c}. :ok? is true
  iff there are no HARD violations. `request` carries the input
  topic-cluster (`:items`) so :malformed-citation can verify every cite is
  a direct reference to something mikurabe was actually handed."
  [request _context proposal]
  (let [effect       (:effect proposal)
        observations (:observations proposal)
        conf         (:confidence proposal 0.0)
        item-ids     (set (map :item-id (:items request)))
        entity-keys  (entity-verdict-keys proposal)
        event-keys   (event-verdict-keys proposal)
        hard (cond-> []
               (not= :assessment effect)
               (conj {:rule :no-actuation
                      :detail "mikurabe only assesses; :effect must be :assessment"})

               (seq entity-keys)
               (conj {:rule :country-or-outlet-verdict
                      :detail (str "proposal carries standing country/outlet "
                                   "verdict-shaped field(s), refused at the "
                                   "schema layer: " (pr-str entity-keys))})

               (seq event-keys)
               (conj {:rule :event-truth-verdict
                      :detail (str "proposal asserts an underlying-event "
                                   "verdict field(s): " (pr-str event-keys))})

               (catastrophe? observations)
               (conj {:rule :catastrophe-veto
                      :detail "Rider §2 catastrophe-veto scan hit — never published"})

               (person-targeting? observations)
               (conj {:rule :person-targeting
                      :detail "observation targets a private person — institutional scrutiny only"})

               (some #(< (count (distinct-cited-item-ids %)) 2) observations)
               (conj {:rule :uncited-conclusive
                      :detail "every observation needs ≥2 distinct compared-item citations"})

               (some #(some (fn [c] (not (valid-cite? c item-ids))) (:cites %)) observations)
               (conj {:rule :malformed-citation
                      :detail "every cite must reference one of the input cluster's own item-ids with a non-blank quote"}))
        soft (cond-> []
               (< conf confidence-floor)
               (conj {:rule :low-confidence
                      :detail (str "confidence " conf " < floor " confidence-floor)}))]
    {:ok? (empty? hard) :violations hard :warnings soft :confidence conf}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t :governor-hold :op (:op request) :cluster-id (:cluster-id request)
   :actor (:actor-id context) :disposition :hold
   :basis (mapv :rule (:violations verdict)) :violations (:violations verdict)})

(defn verdict->disposition
  "Map a NarrativeGovernor verdict to a base disposition. HARD → :hold,
  else :commit. (The separate named-party PHASE gate, applied downstream in
  `mikurabe.operation`, may still route a governor-:commit proposal to
  :escalate — that is not this function's concern.)"
  [verdict]
  (if (:ok? verdict) :commit :hold))
