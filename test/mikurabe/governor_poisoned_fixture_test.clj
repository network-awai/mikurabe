(ns mikurabe.governor-poisoned-fixture-test
  "Structural proof, not a docstring promise. Mirrors danjo's own
  poisoned-fixture regression discipline (`no-danjo-adjudication.test.mjs`
  feeds the lint a fixture with a planted verdict token and asserts a
  non-zero exit) — here, translated into this actor's own toolchain
  (clj-kondo/clojure.test instead of node/mjs, since mikurabe's whole stack
  is Clojure), against `mikurabe.governor/check` directly, bypassing the
  StateGraph so the assertion is about the GOVERNOR FUNCTION itself, not
  merely an end-to-end outcome.

  This is THE proof for the single most important gate in this actor:
  :country-or-outlet-verdict. If this test ever goes green for a proposal
  that plants a per-country/per-outlet credibility/rank/regime-type field,
  the ooyake-G11-mirroring guarantee this whole actor exists to keep has
  silently regressed."
  (:require [clojure.test :refer [deftest is testing]]
            [mikurabe.governor :as governor]))

(def ^:private cluster-request
  {:op :cluster/compare :cluster-id "c1"
   :items [{:item-id "kawaraban:a1" :outlet "Outlet-A" :country "Country-A"}
           {:item-id "kawaraban:a2" :outlet "Outlet-B" :country "Country-B"}]})

(defn- ok-cites []
  [{:item-id "kawaraban:a1" :quote "e1"} {:item-id "kawaraban:a2" :quote "e2"}])

(defn- clean-proposal []
  {:effect :assessment :confidence 0.8
   :observations [{:technique :omission :description "one account omits a detail"
                    :cites (ok-cites) :confidence 0.8}]})

;; ───────────────────────── :country-or-outlet-verdict poisoned fixtures ────

(deftest clean-proposal-carries-no-entity-verdict-keys
  (testing "sanity: an ordinary, honest proposal is NOT flagged"
    (is (empty? (governor/entity-verdict-keys (clean-proposal))))))

(deftest poisoned-outlet-credibility-score-is-detected-and-refused
  (testing "a fabricated :outlet/credibilityScore field is caught by the scanner AND refused by check"
    (let [poisoned (assoc-in (clean-proposal) [:observations 0 :outlet/credibilityScore] 0.2)]
      (is (= [:outlet/credibilityScore] (governor/entity-verdict-keys poisoned)))
      (let [verdict (governor/check cluster-request nil poisoned)]
        (is (false? (:ok? verdict)))
        (is (some #{:country-or-outlet-verdict} (mapv :rule (:violations verdict))))))))

(deftest poisoned-country-propaganda-rank-is-detected-and-refused
  (testing "a fabricated :country/propagandaRank field is caught by the scanner AND refused by check"
    (let [poisoned (assoc-in (clean-proposal) [:observations 0 :country/propagandaRank] :low)]
      (is (= [:country/propagandaRank] (governor/entity-verdict-keys poisoned)))
      (let [verdict (governor/check cluster-request nil poisoned)]
        (is (false? (:ok? verdict)))
        (is (some #{:country-or-outlet-verdict} (mapv :rule (:violations verdict))))))))

(deftest poisoned-regime-type-label-is-detected-and-refused
  (testing "a fabricated :government/regimeTypeLabel field is caught by the scanner AND refused"
    (let [poisoned (assoc (clean-proposal) :government/regimeTypeLabel "authoritarian")]
      (is (= [:government/regimeTypeLabel] (governor/entity-verdict-keys poisoned)))
      (let [verdict (governor/check cluster-request nil poisoned)]
        (is (false? (:ok? verdict)))
        (is (some #{:country-or-outlet-verdict} (mapv :rule (:violations verdict))))))))

(deftest poisoned-media-trustworthiness-rating-is-detected-and-refused
  (testing "a fabricated :media/trustworthinessRating field, nested inside a cite, is still caught"
    (let [poisoned (update-in (clean-proposal) [:observations 0 :cites 0]
                              assoc :media/trustworthinessRating 1)]
      (is (= [:media/trustworthinessRating] (governor/entity-verdict-keys poisoned)))
      (let [verdict (governor/check cluster-request nil poisoned)]
        (is (false? (:ok? verdict)))
        (is (some #{:country-or-outlet-verdict} (mapv :rule (:violations verdict))))))))

(deftest legit-outlet-field-alone-is-not-poisoned
  (testing "a plain :outlet field (just naming the outlet, no score attached) is NOT flagged — the
           entity-term alone is insufficient without a verdict-shape term on the SAME key"
    (let [benign (assoc (clean-proposal) :outlet "BBC")]
      (is (empty? (governor/entity-verdict-keys benign)))
      (is (true? (:ok? (governor/check cluster-request nil benign)))))))

;; ───────────────────────── :event-truth-verdict poisoned fixture ───────────

(deftest poisoned-event-verdict-is-detected-and-refused
  (testing "a fabricated :event/verdict field (what actually happened) is caught AND refused"
    (let [poisoned (assoc (clean-proposal) :event/verdict :bill-actually-passed)]
      (is (= [:event/verdict] (governor/event-verdict-keys poisoned)))
      (let [verdict (governor/check cluster-request nil poisoned)]
        (is (false? (:ok? verdict)))
        (is (some #{:event-truth-verdict} (mapv :rule (:violations verdict))))))))
