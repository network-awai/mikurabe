(ns mikurabe.advisor
  "narrative-llm — the *contained intelligence node* for mikurabe. It takes a
  topic-cluster (N already-public kawaraban :mirror articles that kawaraban's
  own mention-edge/section-route graph grouped as the same story, OR N
  kouhou briefings on the same official announcement) and returns a
  PROPOSAL: a set of citation-grounded framing-technique observations. It
  NEVER returns a committed record and NEVER decides publication — the
  NarrativeGovernor censors every proposal downstream, and only :commit
  writes the SSoT + publishes (subject also to the separate named-party
  PHASE gate). Mirrors the `Advisor` protocol shape used by
  tashikame.advisor / yosoku.advisor.

  Sealed by construction: the default `mock-advisor` is deterministic (no
  non-deterministic free-write) — it is the ONLY advisor shipped at R0,
  matching yosoku's and tashikame's own precedent (a real advisor is a
  documented follow-up, not faked here). It NEVER fetches anything itself —
  it only reads the `:items` it was handed in the request (danjo G3-style
  passive-only discipline structurally continues all the way down: even the
  mock advisor cannot invent a citation to something outside its input).

  Proposal shape:
    {:summary     str
     :rationale   str
     :observations [{:technique  kw (see governor/technique-enum)
                      :description str  ; DELIBERATELY generic/neutral —
                                        ; never names a specific outlet or
                                        ; country by default (see
                                        ; `mikurabe.phase/named-party?`);
                                        ; the structured :cites carry the
                                        ; item-level provenance instead
                      :cites [{:item-id str :quote str} …]  ; ≥2 distinct
                      :confidence 0..1}]
     :effect      :assessment   ; mikurabe only ever assesses, never actuates
     :confidence  0..1}"
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(defprotocol Advisor
  (-frame [advisor store request] "store + request (topic-cluster) → proposal map"))

;; ───────────────────────── mock heuristics (no NLP, keyword/text-diff only) ─

(defn- tokenize [s]
  (->> (str/split (str/lower-case (or s "")) #"[^a-z0-9\p{L}]+")
       (remove str/blank?)
       set))

(def ^:private loaded-terms
  "Illustrative R0 marker set for the :loaded-language heuristic — a
  keyword-presence check, not real NLP (same honesty scope as every other
  R0 mock advisor in this workspace)."
  #{"regime" "puppet" "sham" "brutal" "heroic" "liberators" "aggressors"
    "propaganda" "illegal" "invasion" "genocide" "terrorist" "thugs"})

(def ^:private attribution-markers
  ["according to" "sources say" "officials said" "said officials"
   "reported by" "witnesses said"])

(defn- has-attribution? [excerpt]
  (let [e (str/lower-case (or excerpt ""))]
    (boolean (some #(str/includes? e %) attribution-markers))))

(defn- loaded-words-in [excerpt]
  (set/intersection (tokenize excerpt) loaded-terms))

(defn- clip
  "Truncate `s` to at most `n` chars — the cite :quote is always drawn from
  the ITEM'S OWN excerpt text (never invented), just bounded in length."
  [s n]
  (let [s (or s "")]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- mk-cite [item]
  {:item-id (:item-id item) :quote (clip (:excerpt item) 120)})

(defn- omission-candidates
  "Words present in ALL-BUT-ONE items' excerpts and missing from exactly
  one — a pure keyword-presence text-diff, not real NLP."
  [items]
  (let [n (count items)
        tok-by-item (map (fn [it] [it (tokenize (:excerpt it))]) items)
        all-words (apply set/union (map second tok-by-item))]
    (for [w all-words
          :let [present-in (filter (fn [[_ toks]] (contains? toks w)) tok-by-item)
                missing-in (remove (fn [[_ toks]] (contains? toks w)) tok-by-item)]
          :when (and (>= n 2)
                     (= (count present-in) (dec n))
                     (= (count missing-in) 1)
                     (> (count w) 3))] ; skip trivially short tokens
      {:word w :missing (ffirst missing-in) :present (map first present-in)})))

(defn- emphasis-divergence-obs
  "Two accounts of the SAME cluster leading with a different headline is,
  by construction, an emphasis/lead-fact divergence worth noting — cites
  the two headline items, never names either outlet in the description."
  [items]
  (when (>= (count items) 2)
    (let [[a b] (take 2 items)]
      (when (and (:headline a) (:headline b)
                 (not= (str/lower-case (:headline a)) (str/lower-case (:headline b))))
        {:technique :emphasis-divergence
         :description "the compared accounts lead with a different fact/frame in the headline for the same underlying story"
         :cites [(mk-cite a) (mk-cite b)]
         :confidence 0.6}))))

(defn- loaded-language-obs
  "The first item whose excerpt carries a marker-listed charged term, paired
  against a plainer comparison item."
  [items]
  (some (fn [it]
          (let [lw (loaded-words-in (:excerpt it))]
            (when (seq lw)
              (when-let [other (first (remove #(= (:item-id %) (:item-id it)) items))]
                {:technique :loaded-language
                 :description "one account's excerpt uses charged/loaded terms not present in a compared account's excerpt for the same story"
                 :cites [(mk-cite it) (mk-cite other)]
                 :confidence 0.65}))))
        items))

(defn- omission-obs
  "A fact-word present in every-item-but-one, missing from that one."
  [items]
  (when-let [c (first (omission-candidates items))]
    (let [missing (:missing c) present (first (:present c))]
      {:technique :omission
       :description "one account's excerpt omits a detail present in the majority of the compared accounts' excerpts"
       :cites [(mk-cite present) (mk-cite missing)]
       :confidence 0.55})))

(defn- source-attribution-gap-obs
  "At least one item's excerpt carries no attribution marker while another
  compared item's excerpt does."
  [items]
  (let [with-attr (filter #(has-attribution? (:excerpt %)) items)
        without-attr (remove #(has-attribution? (:excerpt %)) items)]
    (when (and (seq with-attr) (seq without-attr))
      (let [a (first without-attr) b (first with-attr)]
        {:technique :source-attribution-gap
         :description "one account's excerpt carries a claim with no cited source while a compared account's excerpt attributes its account"
         :cites [(mk-cite a) (mk-cite b)]
         :confidence 0.5}))))

(defn- assess* [{:keys [items]}]
  (cond
    (< (count (or items [])) 2)
    {:summary "topic-cluster too small to compare" :rationale "need ≥2 items"
     :observations [] :effect :noop :confidence 0.0}

    :else
    (let [obs (->> [(emphasis-divergence-obs items)
                    (loaded-language-obs items)
                    (omission-obs items)
                    (source-attribution-gap-obs items)]
                   (remove nil?)
                   vec)]
      (if (empty? obs)
        {:summary "no framing divergence detected by mock heuristics"
         :rationale "cluster items too similar under keyword/text-diff heuristics"
         :observations [] :effect :assessment :confidence 0.3}
        {:summary (str (count obs) " framing technique(s) observed")
         :rationale "mock advisor: keyword/text-diff heuristics only, no real NLP"
         :observations obs
         :effect :assessment
         :confidence (/ (reduce + (map :confidence obs)) (count obs))}))))

(defn mock-advisor
  "The deterministic advisor (default and ONLY advisor shipped at R0 — no
  non-deterministic LLM free-write, no live web fetch, no real NLP). A real
  advisor wiring `langchain.model` against the Murakumo fleet is a
  documented follow-up (see README \"Follow-ups\"), same precedent as
  yosoku/tashikame — never faked here."
  []
  (reify Advisor (-frame [_ _store req] (assess* req))))

(defn trace
  "Decision-grounded audit record (evaluation appeals, publish audits)."
  [request proposal]
  {:t          :narrative-llm-proposal
   :op         (:op request)
   :cluster-id (:cluster-id request)
   :summary    (:summary proposal)
   :observations (:observations proposal)
   :confidence (:confidence proposal)})
