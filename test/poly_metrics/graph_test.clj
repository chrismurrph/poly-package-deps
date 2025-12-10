(ns poly-metrics.graph-test
  (:require [clojure.test :refer [deftest testing is]]
            [poly-metrics.graph :as graph]
            [poly-metrics.workspace :as ws]))

(def polylith-root "/tmp/polylith-test")

;; Unit tests with synthetic graphs

(deftest invert-graph-test
  (testing "inverts simple graph"
    (is (= {:b #{"a"}, :c #{"a" "b"}}
           (graph/invert-graph {"a" #{:b :c} "b" #{:c}}))))

  (testing "handles empty graph"
    (is (= {} (graph/invert-graph {}))))

  (testing "handles graph with no dependencies"
    (is (= {} (graph/invert-graph {"a" #{} "b" #{}})))))

(deftest efferent-coupling-test
  (let [g {"a" #{"b" "c" "d"}
           "b" #{"c"}
           "c" #{}
           "d" #{"c"}}]

    (testing "counts outgoing dependencies"
      (is (= 3 (graph/efferent-coupling g "a")))
      (is (= 1 (graph/efferent-coupling g "b")))
      (is (= 0 (graph/efferent-coupling g "c")))
      (is (= 1 (graph/efferent-coupling g "d"))))

    (testing "returns 0 for unknown brick"
      (is (= 0 (graph/efferent-coupling g "unknown"))))))

(deftest afferent-coupling-test
  (let [g {"a" #{"b" "c"}
           "b" #{"c"}
           "c" #{}}
        inverted (graph/invert-graph g)]

    (testing "counts incoming dependencies"
      (is (= 0 (graph/afferent-coupling inverted "a")))
      (is (= 1 (graph/afferent-coupling inverted "b")))
      (is (= 2 (graph/afferent-coupling inverted "c"))))

    (testing "returns 0 for unknown brick"
      (is (= 0 (graph/afferent-coupling inverted "unknown"))))))

(deftest direct-dependencies-test
  (let [g {"a" #{"b" "c"} "b" #{"c"} "c" #{}}]
    (testing "returns direct dependencies"
      (is (= #{"b" "c"} (graph/direct-dependencies g "a")))
      (is (= #{"c"} (graph/direct-dependencies g "b")))
      (is (= #{} (graph/direct-dependencies g "c"))))))

(deftest direct-dependents-test
  (let [g {"a" #{"b" "c"} "b" #{"c"} "c" #{}}
        inverted (graph/invert-graph g)]
    (testing "returns direct dependents"
      (is (= #{} (graph/direct-dependents inverted "a")))
      (is (= #{"a"} (graph/direct-dependents inverted "b")))
      (is (= #{"a" "b"} (graph/direct-dependents inverted "c"))))))

;; Integration tests with real polylith workspace

(deftest build-dependency-graph-test
  (testing "builds graph from polylith workspace"
    (let [graph (graph/build-dependency-graph polylith-root)
          components (ws/find-components polylith-root)
          bases (ws/find-bases polylith-root)]

      (testing "includes all bricks"
        (is (= (count graph) (+ (count components) (count bases))))
        (is (contains? graph "util"))
        (is (contains? graph "file"))
        (is (contains? graph "poly-cli")))

      (testing "values are sets"
        (is (every? set? (vals graph))))

      (testing "dependencies are valid brick names"
        (let [all-bricks (graph/all-bricks graph)]
          (doseq [[brick deps] graph
                  dep deps]
            (is (contains? all-bricks dep)
                (str brick " depends on unknown brick: " dep)))))

      (testing "no self-references"
        (doseq [[brick deps] graph]
          (is (not (contains? deps brick))
              (str brick " has self-reference")))))))

(deftest brick-dependencies-test
  (let [ns-map (ws/build-ns-to-brick-map polylith-root)
        top-ns "polylith.clj.core"]

    (testing "finds dependencies for a component"
      (let [deps (graph/brick-dependencies polylith-root :component "command" ns-map top-ns)]
        (is (set? deps))
        ;; command should have some dependencies
        (is (pos? (count deps)))))

    (testing "util has minimal dependencies"
      (let [deps (graph/brick-dependencies polylith-root :component "util" ns-map top-ns)]
        ;; util is typically a low-level component with few/no deps
        (is (set? deps))))))

(deftest graph-inversion-consistency-test
  (testing "inverted graph is consistent with original"
    (let [graph (graph/build-dependency-graph polylith-root)
          inverted (graph/invert-graph graph)]

      ;; For each edge A->B in original, B should have A as dependent in inverted
      (doseq [[brick deps] graph
              dep deps]
        (is (contains? (get inverted dep #{}) brick)
            (str "Missing inverse edge: " dep " should have " brick " as dependent"))))))

;; Phase 6: Cycle Detection tests

(deftest transitive-deps-test
  (let [g {"a" #{"b" "c"}
           "b" #{"d"}
           "c" #{}
           "d" #{"e"}
           "e" #{}}]

    (testing "finds all transitive dependencies"
      (is (= #{"a" "b" "c" "d" "e"} (graph/transitive-deps g "a")))
      (is (= #{"b" "d" "e"} (graph/transitive-deps g "b")))
      (is (= #{"c"} (graph/transitive-deps g "c")))
      (is (= #{"e"} (graph/transitive-deps g "e"))))

    (testing "handles unknown brick"
      (is (= #{"unknown"} (graph/transitive-deps g "unknown"))))))

(deftest find-cycle-from-test
  (testing "detects A->B->A cycle"
    (let [g {"a" #{"b"}
             "b" #{"a"}}
          cycle (graph/find-cycle-from g "a")]
      (is (some? cycle))
      (is (= "a" (first cycle)))
      (is (= "a" (last cycle)))))

  (testing "detects A->B->C->A cycle"
    (let [g {"a" #{"b"}
             "b" #{"c"}
             "c" #{"a"}}
          cycle (graph/find-cycle-from g "a")]
      (is (some? cycle))
      (is (= "a" (first cycle)))
      (is (= "a" (last cycle)))
      (is (= 4 (count cycle)))))

  (testing "returns nil for acyclic graph"
    (let [g {"a" #{"b"}
             "b" #{"c"}
             "c" #{}}]
      (is (nil? (graph/find-cycle-from g "a")))
      (is (nil? (graph/find-cycle-from g "b")))
      (is (nil? (graph/find-cycle-from g "c")))))

  (testing "returns nil for brick with no deps"
    (let [g {"a" #{}}]
      (is (nil? (graph/find-cycle-from g "a"))))))

(deftest find-all-cycles-test
  (testing "finds single cycle"
    (let [g {"a" #{"b"}
             "b" #{"a"}
             "c" #{}}
          cycles (graph/find-all-cycles g)]
      (is (= 1 (count cycles)))
      (is (= #{"a" "b"} (set (first cycles))))))

  (testing "finds multiple cycles"
    (let [g {"a" #{"b"}
             "b" #{"a"}
             "c" #{"d"}
             "d" #{"c"}}
          cycles (graph/find-all-cycles g)]
      (is (= 2 (count cycles)))))

  (testing "returns empty for acyclic graph"
    (let [g {"a" #{"b" "c"}
             "b" #{"c"}
             "c" #{}}]
      (is (empty? (graph/find-all-cycles g)))))

  (testing "handles empty graph"
    (is (empty? (graph/find-all-cycles {})))))

(deftest acyclic?-test
  (testing "returns true for acyclic graph"
    (let [g {"a" #{"b"} "b" #{"c"} "c" #{}}]
      (is (graph/acyclic? g))))

  (testing "returns false for cyclic graph"
    (let [g {"a" #{"b"} "b" #{"a"}}]
      (is (not (graph/acyclic? g)))))

  (testing "returns true for empty graph"
    (is (graph/acyclic? {}))))

(deftest cyclic-bricks-test
  (testing "finds bricks in cycles"
    (let [g {"a" #{"b"}
             "b" #{"a"}
             "c" #{"d"}
             "d" #{}}
          cyclic (graph/cyclic-bricks g)]
      (is (contains? cyclic "a"))
      (is (contains? cyclic "b"))
      (is (not (contains? cyclic "c")))
      (is (not (contains? cyclic "d")))))

  (testing "returns empty set for acyclic graph"
    (let [g {"a" #{"b"} "b" #{}}]
      (is (empty? (graph/cyclic-bricks g))))))

;; Integration test with real polylith workspace

(deftest polylith-cycles-test
  (testing "polylith workspace should be acyclic"
    (let [graph (graph/build-dependency-graph polylith-root)
          cycles (graph/find-all-cycles graph)]
      ;; The polylith project itself should be well-designed with no cycles
      (is (empty? cycles)
          (str "Found unexpected cycles: " cycles)))))
