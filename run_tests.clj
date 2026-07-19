(ns mikurabe.run-tests
  "Test runner for com-etzhayyim-mikurabe (new actors ship run_tests.clj,
  not .sh — per etzhayyim/root CLAUDE.md; mirrors tashikame's own
  run_tests.clj). Canonical path: `clojure -M:dev:test` (cognitect
  test-runner). This runner: `clojure -M -m mikurabe.run-tests`."
  (:require [clojure.test :refer [run-tests]]
            [mikurabe.governor-contract-test]
            [mikurabe.governor-poisoned-fixture-test]
            [mikurabe.store-contract-test]
            [mikurabe.operation-test]
            [mikurabe.advisor-test]
            [mikurabe.phase-test])
  (:gen-class))

(defn -main [& _args]
  (let [res (run-tests
             'mikurabe.governor-contract-test
             'mikurabe.governor-poisoned-fixture-test
             'mikurabe.store-contract-test
             'mikurabe.operation-test
             'mikurabe.advisor-test
             'mikurabe.phase-test)]
    (when (pos? (+ (:fail res 0) (:error res 0)))
      (System/exit 1))))
