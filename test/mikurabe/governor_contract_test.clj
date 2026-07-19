(ns mikurabe.governor-contract-test
  "The comparative-narrative publication contract as executable tests, run
  through the FULL StateGraph (mirrors tashikame.governor-contract-test).
  Invariant: mikurabe NEVER publishes an observation the NarrativeGovernor
  rejects; every published observation cites ≥2 of the compared items;
  catastrophe-veto / person-targeting / uncited-conclusive /
  malformed-citation / country-or-outlet-verdict / event-truth-verdict /
  no-actuation proposals are HELD (recorded as a hold, never published)."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [mikurabe.advisor :as advisor]
            [mikurabe.operation :as op]
            [mikurabe.publisher :as publisher]
            [mikurabe.store :as store]))

(def cluster
  {:op :cluster/compare :cluster-id "c1"
   :items [{:item-id "kawaraban:a1" :outlet "Outlet-A" :country "Country-A"
            :headline "h1" :excerpt "e1" :url "https://a1"}
           {:item-id "kawaraban:a2" :outlet "Outlet-B" :country "Country-B"
            :headline "h2" :excerpt "e2" :url "https://a2"}]})

(defn- fresh []
  (let [s (store/seed-db) pub (publisher/mock-publisher (atom []))]
    [s pub (op/build s {:publisher pub})]))

(defn- run [actor phase]
  (g/run* actor
          {:request cluster :context {:actor-id "mikurabe" :phase phase}}
          {:thread-id (:cluster-id cluster)}))

(defn- basis [s] (-> (store/ledger s) last :basis))

(defn- published-count [pub] (count @(:a pub)))

(defn- with-advisor [s pub adv]
  (op/build s {:advisor adv :publisher pub}))

(defn- bad-advisor [observations]
  (reify advisor/Advisor
    (-frame [_ _ _] {:effect :assessment :confidence 0.9
                     :observations observations :summary "" :rationale ""})))

(defn- obs [& kvs] (apply hash-map kvs))

(defn- ok-cites []
  [{:item-id "kawaraban:a1" :quote "e1"} {:item-id "kawaraban:a2" :quote "e2"}])

(deftest well-cited-non-named-observation-commits-and-publishes
  (testing "a governor-clean, non-named-party observation commits + publishes autonomously at phase 1"
    (let [[s pub _] (fresh)
          adv (bad-advisor [(obs :technique :omission :description "one account omits a detail"
                                  :cites (ok-cites) :confidence 0.8)])
          actor (with-advisor s pub adv)
          r (run actor 1)]
      (is (= :commit (get-in r [:state :disposition])))
      (is (= 1 (published-count pub))))))

(deftest uncited-conclusive-is-held
  (testing "an observation with < 2 distinct cites → HOLD (:uncited-conclusive)"
    (let [[s pub _] (fresh)
          adv (bad-advisor [(obs :technique :omission :description "x"
                                  :cites [{:item-id "kawaraban:a1" :quote "e1"}] :confidence 0.8)])
          r (run (with-advisor s pub adv) 1)]
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #{:uncited-conclusive} (basis s)))
      (is (zero? (published-count pub)) "never published")
      (is (nil? (store/observation s "c1")) "nothing recorded on hold"))))

(deftest malformed-citation-is-held
  (testing "a cite that is not a direct reference to an input cluster item → HOLD (:malformed-citation)"
    (let [[s pub _] (fresh)
          adv (bad-advisor [(obs :technique :omission :description "x"
                                  :cites [{:item-id "not-in-cluster" :quote "e1"}
                                          {:item-id "kawaraban:a2" :quote "e2"}]
                                  :confidence 0.8)])
          r (run (with-advisor s pub adv) 1)]
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #{:malformed-citation} (basis s)))
      (is (zero? (published-count pub))))))

(deftest country-or-outlet-verdict-is-held
  (testing "a proposal that attaches a standing country/outlet credibility field → HOLD"
    (let [[s pub _] (fresh)
          adv (bad-advisor [(obs :technique :omission :description "x" :cites (ok-cites)
                                  :confidence 0.8
                                  :country/propagandaRank :low)])
          r (run (with-advisor s pub adv) 1)]
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #{:country-or-outlet-verdict} (basis s)))
      (is (zero? (published-count pub))))))

(deftest event-truth-verdict-is-held
  (testing "a proposal that asserts what actually happened in the underlying event → HOLD"
    (let [[s pub _] (fresh)
          adv (bad-advisor [(obs :technique :omission :description "x" :cites (ok-cites)
                                  :confidence 0.8
                                  :event/verdict :bill-actually-passed)])
          r (run (with-advisor s pub adv) 1)]
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #{:event-truth-verdict} (basis s)))
      (is (zero? (published-count pub))))))

(deftest catastrophe-veto-is-held
  (testing "an observation hitting the Rider §2 catastrophe-veto → HOLD"
    (let [[s pub _] (fresh)
          adv (bad-advisor [(obs :technique :loaded-language :description "<CAT>" :cites (ok-cites)
                                  :confidence 0.8)])
          r (run (with-advisor s pub adv) 1)]
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #{:catastrophe-veto} (basis s)))
      (is (zero? (published-count pub))))))

(deftest person-targeting-is-held
  (testing "an observation that doxes a private person → HOLD (:person-targeting)"
    (let [[s pub _] (fresh)
          adv (bad-advisor [(obs :technique :loaded-language :description "<DOXING>" :cites (ok-cites)
                                  :confidence 0.8)])
          r (run (with-advisor s pub adv) 1)]
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #{:person-targeting} (basis s))))))

(deftest no-actuation-is-held
  (testing "a proposal that tries to actuate (not assess) → HOLD (:no-actuation)"
    (let [[s pub _] (fresh)
          bad (reify advisor/Advisor
                (-frame [_ _ _] {:effect :grant-attestation :confidence 0.9
                                 :observations [] :summary "" :rationale ""}))
          r (run (with-advisor s pub bad) 1)]
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #{:no-actuation} (basis s))))))

(deftest phase0-records-but-does-not-publish
  (testing "phase 0 (observe, DEFAULT): a clean, non-named observation is recorded but NOT published"
    (let [[s pub _] (fresh)
          adv (bad-advisor [(obs :technique :omission :description "x" :cites (ok-cites) :confidence 0.8)])
          r (run (with-advisor s pub adv) 0)]
      (is (= :commit (get-in r [:state :disposition])) "observation recorded")
      (is (some? (store/observation s "c1")))
      (is (zero? (published-count pub)) "phase 0 → shadow, no publish"))))

(deftest named-party-observation-escalates-not-published
  (testing "a governor-clean observation that NAMES a specific outlet/country → escalate, never auto-published"
    (let [[s pub _] (fresh)
          adv (bad-advisor [(obs :technique :emphasis-divergence
                                  :description "Outlet-A frames the vote differently from Outlet-B"
                                  :cites (ok-cites) :confidence 0.8)])
          r (run (with-advisor s pub adv) 1)]
      (is (= :escalate (get-in r [:state :disposition])))
      (is (= :interrupted (:status r)))
      (is (zero? (published-count pub)))
      (is (nil? (store/observation s "c1")) "nothing committed while escalated"))))
