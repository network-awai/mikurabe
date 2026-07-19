(ns mikurabe.store-contract-test
  "MemStore ≡ DatomicStore contract — the same frameObservation record +
  ledger facts appear regardless of backend (in-process EAVT today;
  kotoba-server pod tomorrow). mikurabe's analog of
  tashikame.store-contract-test / yosoku's store parity test."
  (:require [clojure.test :refer [deftest is testing]]
            [mikurabe.store :as store]))

(deftest mem-and-datomic-agree-on-commit-and-ledger
  (testing "the same observation record + ledger fact land on both backends"
    (let [mem (store/seed-db)
          dat (store/datomic-store)]
      (doseq [s [mem dat]]
        (store/commit-observation! s "c1" {:cluster-id "c1"
                                           :observations [{:technique :omission :description "x"}]})
        (store/append-ledger! s {:t :committed :cluster-id "c1" :disposition :commit}))
      (is (= [{:technique :omission :description "x"}]
             (:observations (store/observation mem "c1"))))
      (is (= [{:technique :omission :description "x"}]
             (:observations (store/observation dat "c1"))))
      (is (= 1 (count (store/ledger mem))))
      (is (= 1 (count (store/ledger dat))))
      (is (= :committed (:t (last (store/ledger mem)))))
      (is (= :committed (:t (last (store/ledger dat))))))))

(deftest ledger-is-append-only
  (testing "ledger facts accumulate by seq, never overwritten"
    (let [s (store/seed-db)]
      (store/append-ledger! s {:t :committed :cluster-id "a"})
      (store/append-ledger! s {:t :hold      :cluster-id "b"})
      (store/append-ledger! s {:t :escalate  :cluster-id "c"})
      (is (= 3 (count (store/ledger s))))
      (is (= [:committed :hold :escalate] (map :t (store/ledger s)))))))

(deftest all-observations-sorted-by-cluster-id
  (testing "all-observations returns every committed record, sorted"
    (let [s (store/seed-db)]
      (store/commit-observation! s "b" {:cluster-id "b"})
      (store/commit-observation! s "a" {:cluster-id "a"})
      (is (= ["a" "b"] (map :cluster-id (store/all-observations s)))))))
