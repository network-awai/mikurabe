(ns mikurabe.operation-test
  "End-to-end operation behavior beyond the per-gate contract tests: low-
  confidence still publishes (with a transparency tag), an empty/too-small
  cluster no-ops cleanly, and the two-lock named-party resume path (an
  :approved resume only actually publishes when the phase-2 Council gate is
  ALSO open — approval alone is not sufficient)."
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

(defn- ok-cites []
  [{:item-id "kawaraban:a1" :quote "e1"} {:item-id "kawaraban:a2" :quote "e2"}])

(defn- low-advisor []
  (reify advisor/Advisor
    (-frame [_ _ _] {:effect :assessment :confidence 0.1
                     :observations [{:technique :omission :description "x"
                                     :cites (ok-cites) :confidence 0.1}]
                     :summary "" :rationale ""})))

(deftest low-confidence-still-publishes-with-tag
  (testing "low confidence does NOT block publication (still governor-clean); it is tagged"
    (let [s (store/seed-db) pub (publisher/mock-publisher (atom []))
          a (op/build s {:advisor (low-advisor) :publisher pub})
          r (g/run* a {:request cluster :context {:actor-id "mikurabe" :phase 1}}
                    {:thread-id "c1"})]
      (is (= :commit (get-in r [:state :disposition])) "low confidence → still publishes")
      (is (= 1 (count @(:a pub))))
      (is (some #(= :low-confidence (:rule %))
                (-> (store/ledger s) last :warnings))))))

(deftest too-small-cluster-noops
  (testing "a cluster with < 2 items → :noop effect → governor :no-actuation → hold"
    (let [s (store/seed-db) pub (publisher/mock-publisher (atom []))
          a (op/build s {:publisher pub})
          r (g/run* a {:request (assoc cluster :items [(first (:items cluster))])
                       :context {:actor-id "mikurabe" :phase 1}} {:thread-id "e1"})]
      (is (= :hold (get-in r [:state :disposition])))
      (is (zero? (count @(:a pub)))))))

;; ───────────────────────── named-party escalate + two-lock resume ──────────

(defn- named-party-advisor []
  (reify advisor/Advisor
    (-frame [_ _ _] {:effect :assessment :confidence 0.8
                     :observations [{:technique :emphasis-divergence
                                     :description "Outlet-A frames the vote differently from Outlet-B"
                                     :cites (ok-cites) :confidence 0.8}]
                     :summary "" :rationale ""})))

(deftest named-party-resume-approved-with-gate-open-publishes
  (testing "an :approved resume, WITH the phase-2 Council gate open, publishes"
    (let [s (store/seed-db) pub (publisher/mock-publisher (atom []))
          a (op/build s {:advisor (named-party-advisor) :publisher pub})
          r1 (g/run* a {:request cluster :context {:actor-id "mikurabe" :phase 2}}
                     {:thread-id "np-open"})
          _ (is (= :interrupted (:status r1)))
          _ (is (= :escalate (get-in r1 [:state :disposition])))
          r2 (g/run* a {:approval {:status :approved :by "council-simulated"}}
                     {:thread-id "np-open" :resume? true})]
      (is (= :commit (get-in r2 [:state :disposition])))
      (is (= 1 (count @(:a pub))))
      (is (some? (store/observation s "c1"))))))

(deftest named-party-resume-approved-with-gate-closed-still-holds
  (testing "an :approved resume, but the phase-2 Council gate is CLOSED (phase 1), still holds — approval alone is not sufficient"
    (let [s (store/seed-db) pub (publisher/mock-publisher (atom []))
          a (op/build s {:advisor (named-party-advisor) :publisher pub})
          _ (g/run* a {:request cluster :context {:actor-id "mikurabe" :phase 1}}
                    {:thread-id "np-closed"})
          r2 (g/run* a {:approval {:status :approved :by "council-simulated"}}
                     {:thread-id "np-closed" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (zero? (count @(:a pub))))
      (is (nil? (store/observation s "c1")))
      (is (some #{:named-party-gate-closed} (-> (store/ledger s) last :basis))))))

(deftest named-party-resume-rejected-holds
  (testing "an explicitly :rejected resume holds, regardless of phase"
    (let [s (store/seed-db) pub (publisher/mock-publisher (atom []))
          a (op/build s {:advisor (named-party-advisor) :publisher pub})
          _ (g/run* a {:request cluster :context {:actor-id "mikurabe" :phase 2}}
                    {:thread-id "np-rejected"})
          r2 (g/run* a {:approval {:status :rejected :by "council-simulated"}}
                     {:thread-id "np-rejected" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (zero? (count @(:a pub))))
      (is (some #{:approver-rejected} (-> (store/ledger s) last :basis))))))
