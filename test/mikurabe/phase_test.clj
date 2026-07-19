(ns mikurabe.phase-test
  "Phase 0→1→2 semantics + named-party detection, in isolation from the
  full StateGraph (mirrors the isolated-governor-test convention yosoku's
  README describes)."
  (:require [clojure.test :refer [deftest is testing]]
            [mikurabe.phase :as phase]))

(def items
  [{:item-id "a1" :outlet "Outlet-A" :country "Country-A"}
   {:item-id "a2" :outlet "Outlet-B" :country "Country-B"}])

(deftest default-phase-is-observe
  (testing "the conservative default is 0 (observe) — never 1, unlike tashikame"
    (is (= 0 phase/default-phase))
    (is (false? (phase/publish-allowed? phase/default-phase)))))

(deftest publish-allowed-by-phase
  (testing "phase 0 never publishes; phase 1 and 2 do"
    (is (false? (phase/publish-allowed? 0)))
    (is (true? (phase/publish-allowed? 1)))
    (is (true? (phase/publish-allowed? 2)))))

(deftest council-gate-open-only-at-phase-2-plus
  (testing "the named-party Council gate is closed below phase 2"
    (is (false? (phase/council-gate-open? 0)))
    (is (false? (phase/council-gate-open? 1)))
    (is (true?  (phase/council-gate-open? 2)))))

(deftest unknown-phase-falls-back-to-default
  (testing "an unrecognized phase number behaves like the default (observe, no publish)"
    (is (false? (phase/publish-allowed? 99)))))

(deftest named-party-true-when-text-mentions-a-cluster-outlet
  (testing "naming an outlet that IS in the input cluster trips detection"
    (is (true? (phase/named-party? "Outlet-A leads differently from Outlet-B" items)))))

(deftest named-party-true-when-text-mentions-a-cluster-country
  (testing "naming a country that IS in the input cluster trips detection"
    (is (true? (phase/named-party? "Country-A's account differs" items)))))

(deftest named-party-false-for-generic-description
  (testing "a generic, party-free description does not trip detection"
    (is (false? (phase/named-party? "the compared accounts lead with a different fact" items)))))

(deftest named-party-false-for-a-name-not-in-the-cluster
  (testing "mikurabe only checks against what it was ACTUALLY handed — an unrelated
           name (even a real country) never trips this (no external denylist)"
    (is (false? (phase/named-party? "Some Other Country's press differs" items)))))

(deftest observations-name-party-true-if-any-one-observation-does
  (testing "aggregation: one named observation among several is enough to gate the whole record"
    (let [obs [{:description "generic, no party named"}
               {:description "Outlet-A frames it differently"}]]
      (is (true? (phase/observations-name-party? obs items))))))

(deftest observations-name-party-false-when-none-do
  (let [obs [{:description "generic one"} {:description "also generic"}]]
    (is (false? (phase/observations-name-party? obs items)))))
