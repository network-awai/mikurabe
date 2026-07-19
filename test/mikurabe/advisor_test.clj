(ns mikurabe.advisor-test
  "mock-advisor proposal-shape tests. mikurabe's only shipped advisor at R0
  (matching yosoku's/tashikame's precedent) must, by construction, ALWAYS
  produce governor-clean citations (≥2 distinct item-ids per observation,
  every technique in the published enum, never a poisoned entity-verdict
  field) and must never invent a citation to something outside its input."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [mikurabe.advisor :as advisor]
            [mikurabe.governor :as governor]))

(def two-item-cluster
  {:op :cluster/compare :cluster-id "c1"
   :items [{:item-id "kawaraban:a1" :outlet "Outlet-A" :country "Country-A"
            :headline "Parliament passes new energy bill"
            :excerpt "Lawmakers approved the bill 210-190 after months of debate over the regime funding formula."
            :url "https://a1"}
           {:item-id "kawaraban:a2" :outlet "Outlet-B" :country "Country-B"
            :headline "Energy bill clears final vote amid opposition walkout"
            :excerpt "The vote passed 210-190; opposition lawmakers walked out before the final tally."
            :url "https://a2"}]})

(deftest too-small-cluster-noops
  (testing "< 2 items → :noop effect, no observations invented"
    (let [p (advisor/-frame (advisor/mock-advisor) nil (assoc two-item-cluster :items []))]
      (is (= :noop (:effect p)))
      (is (empty? (:observations p))))))

(deftest proposal-effect-is-always-assessment-or-noop
  (testing "the mock advisor never actuates"
    (let [p (advisor/-frame (advisor/mock-advisor) nil two-item-cluster)]
      (is (contains? #{:assessment :noop} (:effect p))))))

(deftest every-observation-technique-is-in-the-published-enum
  (testing "every emitted :technique is one of the open, versioned methodNote categories"
    (let [p (advisor/-frame (advisor/mock-advisor) nil two-item-cluster)]
      (doseq [o (:observations p)]
        (is (contains? governor/technique-enum (:technique o)))))))

(deftest every-observation-cites-at-least-2-distinct-input-items
  (testing "the mock advisor is, by construction, always governor-clean on citation discipline"
    (let [p (advisor/-frame (advisor/mock-advisor) nil two-item-cluster)]
      (is (seq (:observations p)) "this fixture pair is designed to trip ≥1 heuristic")
      (doseq [o (:observations p)]
        (is (>= (count (distinct (map :item-id (:cites o)))) 2))))))

(deftest every-cite-references-an-actual-input-item-id
  (testing "the advisor cannot cite anything it wasn't handed as input (passive-only discipline)"
    (let [p (advisor/-frame (advisor/mock-advisor) nil two-item-cluster)
          item-ids (set (map :item-id (:items two-item-cluster)))]
      (doseq [o (:observations p) c (:cites o)]
        (is (contains? item-ids (:item-id c)))))))

(deftest observations-never-name-a-party-by-default
  (testing "mock-advisor descriptions are deliberately generic — never name a specific outlet/country"
    (let [p (advisor/-frame (advisor/mock-advisor) nil two-item-cluster)
          party-strings ["Outlet-A" "Outlet-B" "Country-A" "Country-B"]]
      (doseq [o (:observations p)]
        (is (not (some #(str/includes?
                         (str/lower-case (:description o))
                         (str/lower-case %))
                       party-strings)))))))

(deftest mock-advisor-proposal-passes-the-governor-clean-when-2-items
  (testing "end-to-end: what the mock advisor proposes for this fixture is governor :ok?"
    (let [p (advisor/-frame (advisor/mock-advisor) nil two-item-cluster)
          v (governor/check two-item-cluster nil p)]
      (is (true? (:ok? v))))))

(deftest identical-excerpts-yield-no-observations
  (testing "when the two accounts are word-for-word identical, no divergence is claimed"
    (let [same {:op :cluster/compare :cluster-id "c2"
                :items [{:item-id "x1" :outlet "Outlet-A" :headline "same headline"
                         :excerpt "identical excerpt text here" :url "https://x1"}
                        {:item-id "x2" :outlet "Outlet-B" :headline "same headline"
                         :excerpt "identical excerpt text here" :url "https://x2"}]}
          p (advisor/-frame (advisor/mock-advisor) nil same)]
      (is (= :assessment (:effect p)))
      (is (empty? (:observations p))))))
