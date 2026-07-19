(ns mikurabe.sim
  "Offline demo: drive five representative topic-cluster proposals through
  the mikurabe actor on a MemStore + mock advisor + mock publisher (no
  network). `clojure -M:dev:run`.

  (a) well-cited, non-named-party technique observation → commit
  (b) under-cited observation (< 2 cites)                → HARD hold :uncited-conclusive
  (c) a proposal that tries to attach a country/outlet
      credibility-score-shaped field                     → HARD hold :country-or-outlet-verdict
  (d) a proposal targeting a private individual           → HARD hold :person-targeting
  (e) a proposal that names a specific outlet in its
      observation text                                    → :escalate (Council gate), never
                                                              auto-published at R0/R1

  Plus a resume demo proving the two-lock design for (e): an :approved
  resume at phase 2 (Council gate open) DOES publish; the same approved
  resume at phase 1 (gate still closed) still holds."
  (:require [langgraph.graph :as g]
            [mikurabe.advisor :as advisor]
            [mikurabe.operation :as op]
            [mikurabe.publisher :as publisher]
            [mikurabe.store :as store])
  (:gen-class))

(def cluster-1
  "Two already-public accounts of the SAME story (fixture data only — no
  live kawaraban/kouhou read at R0)."
  {:op :cluster/compare :cluster-id "cl-1"
   :items [{:item-id "kawaraban:a1" :source :kawaraban :outlet "Outlet-A" :country "Country-A"
            :headline "Parliament passes new energy bill"
            :excerpt "Lawmakers approved the bill 210-190 after months of debate over the regime funding formula."
            :url "https://example.org/a1"}
           {:item-id "kawaraban:a2" :source :kawaraban :outlet "Outlet-B" :country "Country-B"
            :headline "Energy bill clears final vote amid opposition walkout"
            :excerpt "The vote passed 210-190; opposition lawmakers walked out before the final tally."
            :url "https://example.org/a2"}]})

(defn- under-cited-advisor []
  (reify advisor/Advisor
    (-frame [_ _store _request]
      {:effect :assessment :confidence 0.7
       :observations [{:technique :omission
                        :description "one account omits a detail present in another"
                        :cites [{:item-id "kawaraban:a1" :quote "x"}] ; only 1 distinct cite
                        :confidence 0.7}]
       :summary "under-cited fixture" :rationale "proof case (b)"})))

(defn- poisoned-advisor
  "Proof case (c): a proposal that tries to smuggle in a standing
  country/outlet credibility-score-shaped field. This is exactly the shape
  `test/mikurabe/governor_poisoned_fixture_test.clj` also proves against
  `mikurabe.governor/check` directly — the SAME governor function is what
  refuses it here, end-to-end through the whole StateGraph."
  []
  (reify advisor/Advisor
    (-frame [_ _store _request]
      {:effect :assessment :confidence 0.8
       :observations [{:technique :omission
                        :description "one account omits a detail present in another"
                        :cites [{:item-id "kawaraban:a1" :quote "x"} {:item-id "kawaraban:a2" :quote "y"}]
                        :confidence 0.8
                        ;; POISONED — a standing per-country propaganda rank.
                        ;; Must never be representable; the governor HARD-holds it.
                        :country/propagandaRank :low}]
       :summary "poisoned fixture" :rationale "proof case (c)"})))

(defn- doxing-advisor []
  (reify advisor/Advisor
    (-frame [_ _store _request]
      {:effect :assessment :confidence 0.8
       :observations [{:technique :loaded-language
                        :description "<DOXING> targets a named private individual, not an institution"
                        :cites [{:item-id "kawaraban:a1" :quote "x"} {:item-id "kawaraban:a2" :quote "y"}]
                        :confidence 0.8}]
       :summary "doxing fixture" :rationale "proof case (d)"})))

(defn- named-party-advisor
  "Proof case (e): a proposal that names a specific outlet from the input
  cluster in its observation text. Governor-clean (well-cited, no poisoned
  fields) — the ONLY reason this cannot commit outright is the separate
  named-party PHASE gate."
  []
  (reify advisor/Advisor
    (-frame [_ _store _request]
      {:effect :assessment :confidence 0.8
       :observations [{:technique :emphasis-divergence
                        :description "Outlet-A's headline frames the vote differently from Outlet-B's account"
                        :cites [{:item-id "kawaraban:a1" :quote "x"} {:item-id "kawaraban:a2" :quote "y"}]
                        :confidence 0.8}]
       :summary "named-party fixture" :rationale "proof case (e)"})))

(defn- run-case [label adv thread-id phase]
  (let [s (store/seed-db) pub (publisher/mock-publisher)
        actor (op/build s {:advisor adv :publisher pub})
        r (g/run* actor {:request cluster-1 :context {:actor-id "mikurabe" :phase phase}}
                  {:thread-id thread-id})]
    (println "---" label "---")
    (println "disposition:" (get-in r [:state :disposition])
             "| graph status:" (:status r)
             "| published?" (boolean (get-in r [:state :published])))
    (println "ledger tail:" (pr-str (last (store/ledger s))))))

(defn -main [& _args]
  (run-case "(a) well-cited, non-named-party technique observation"
            (advisor/mock-advisor) "cl-1-a" 1)
  (run-case "(b) under-cited (< 2 distinct cites)"
            (under-cited-advisor) "cl-1-b" 1)
  (run-case "(c) poisoned country/outlet-verdict field"
            (poisoned-advisor) "cl-1-c" 1)
  (run-case "(d) person-targeting (doxing marker)"
            (doxing-advisor) "cl-1-d" 1)
  (run-case "(e) names a specific outlet -- Council-gated escalate"
            (named-party-advisor) "cl-1-e" 1)

  (println "\n--- resume demo: (e) approved by a simulated Council reviewer, phase 2 (gate OPEN) ---")
  (let [s (store/seed-db) pub (publisher/mock-publisher)
        actor (op/build s {:advisor (named-party-advisor) :publisher pub})
        r1 (g/run* actor {:request cluster-1 :context {:actor-id "mikurabe" :phase 2}}
                   {:thread-id "cl-1-resume-open"})
        _ (println "after escalate: graph status" (:status r1)
                    "disposition" (get-in r1 [:state :disposition]))
        r2 (g/run* actor {:approval {:status :approved :by "council-simulated"}}
                   {:thread-id "cl-1-resume-open" :resume? true})]
    (println "after resume+approve @ phase 2 (gate open): disposition"
             (get-in r2 [:state :disposition])
             "| published?" (boolean (get-in r2 [:state :published]))))

  (println "\n--- resume demo: same proposal, approved, but phase 1 (gate CLOSED) ---")
  (let [s (store/seed-db) pub (publisher/mock-publisher)
        actor (op/build s {:advisor (named-party-advisor) :publisher pub})
        _ (g/run* actor {:request cluster-1 :context {:actor-id "mikurabe" :phase 1}}
                  {:thread-id "cl-1-resume-closed"})
        r2 (g/run* actor {:approval {:status :approved :by "council-simulated"}}
                   {:thread-id "cl-1-resume-closed" :resume? true})]
    (println "after resume+approve @ phase 1 (gate closed): disposition"
             (get-in r2 [:state :disposition])
             "| published?" (boolean (get-in r2 [:state :published]))
             "(must be false — approval alone is not enough, the phase-2 Council gate must ALSO be open)")))
