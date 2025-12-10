(ns poly-metrics.metrics-test
  (:require [clojure.test :refer [deftest testing is]]
            [poly-metrics.metrics :as metrics]
            [poly-metrics.graph :as graph]
            [poly-metrics.workspace :as ws]))

(def polylith-root "/tmp/polylith-test")

;; Unit tests for metric calculations

(deftest instability-test
  (testing "I=0 when Ce=0 (no outgoing deps = stable)"
    (is (= 0.0 (metrics/instability 5 0)))
    (is (= 0.0 (metrics/instability 10 0))))

  (testing "I=1 when Ca=0 (no incoming deps = unstable)"
    (is (= 1.0 (metrics/instability 0 5)))
    (is (= 1.0 (metrics/instability 0 10))))

  (testing "I=0.5 when Ca=Ce"
    (is (= 0.5 (metrics/instability 5 5)))
    (is (= 0.5 (metrics/instability 10 10))))

  (testing "isolated component (Ca=0, Ce=0) is stable"
    (is (= 0.0 (metrics/instability 0 0))))

  (testing "calculates ratio correctly"
    (is (= 0.25 (metrics/instability 3 1)))
    (is (= 0.75 (metrics/instability 1 3)))))

(deftest abstractness-test
  (testing "A=1 when no leaky impl namespaces (all via interface)"
    (is (= 1.0 (metrics/abstractness 5 0)))
    (is (= 1.0 (metrics/abstractness 1 0))))

  (testing "A=0 when no interface namespaces (all leaky)"
    (is (= 0.0 (metrics/abstractness 0 5))))

  (testing "A=1 when no external access at all"
    (is (= 1.0 (metrics/abstractness 0 0))))

  (testing "calculates ratio correctly"
    ;; 1 interface, 4 leaky impl = 1/5 = 0.2
    (is (= 0.2 (metrics/abstractness 1 4)))
    ;; 2 interface, 2 leaky impl = 2/4 = 0.5
    (is (= 0.5 (metrics/abstractness 2 2)))))

(deftest distance-test
  (testing "D=0 for ideal cases"
    ;; A=1: fully abstract (any instability)
    (is (= 0.0 (metrics/distance 1.0 0.0)))
    (is (= 0.0 (metrics/distance 1.0 0.5)))
    (is (= 0.0 (metrics/distance 1.0 1.0)))
    ;; I=1: fully unstable (any abstractness) - nothing depends, so leakage doesn't matter
    (is (= 0.0 (metrics/distance 0.0 1.0)))
    (is (= 0.0 (metrics/distance 0.5 1.0))))

  (testing "D=1 for worst case: concrete and stable (zone of pain)"
    ;; A=0, I=0: leaky abstraction that many depend on
    (is (= 1.0 (metrics/distance 0.0 0.0))))

  (testing "D proportional to (1-A) * (1-I)"
    ;; A=0, I=0.5: half unstable, fully leaky
    (is (= 0.5 (metrics/distance 0.0 0.5)))
    ;; A=0.5, I=0: fully stable, half leaky
    (is (= 0.5 (metrics/distance 0.5 0.0)))
    ;; A=0.5, I=0.5: half each
    (is (= 0.25 (metrics/distance 0.5 0.5)))))

(defn approx=
  "Check if two numbers are approximately equal within epsilon."
  [a b & [epsilon]]
  (< (Math/abs (- a b)) (or epsilon 1e-9)))

(deftest codebase-health-test
  (testing "calculates health from metrics"
    (let [metrics [{:distance 0.2}
                   {:distance 0.4}
                   {:distance 0.6}]
          health (metrics/codebase-health metrics)]
      (is (= 3 (:brick-count health)))
      (is (approx= 0.4 (:mean-distance health)))
      (is (approx= 0.6 (:max-distance health)))
      (is (approx= 0.2 (:min-distance health)))))

  (testing "handles empty metrics"
    (let [health (metrics/codebase-health [])]
      (is (= 0 (:brick-count health)))
      (is (= 0.0 (:mean-distance health)))
      (is (= 0.0 (:max-distance health)))
      (is (= 0.0 (:min-distance health))))))

(deftest problematic-bricks-test
  (testing "finds bricks above threshold"
    (let [metrics [{:brick-name "a" :distance 0.3}
                   {:brick-name "b" :distance 0.6}
                   {:brick-name "c" :distance 0.8}]
          problems (metrics/problematic-bricks metrics 0.5)]
      (is (= 2 (count problems)))
      (is (= "c" (:brick-name (first problems))))
      (is (= "b" (:brick-name (second problems))))))

  (testing "default threshold is 0.5"
    (let [metrics [{:brick-name "a" :distance 0.4}
                   {:brick-name "b" :distance 0.6}]
          problems (metrics/problematic-bricks metrics)]
      (is (= 1 (count problems)))
      (is (= "b" (:brick-name (first problems)))))))

;; Integration tests with real polylith workspace

(deftest brick-metrics-test
  (testing "calculates all metrics for a component"
    (let [graph (graph/build-dependency-graph polylith-root)
          inverted (graph/invert-graph graph)
          external-requires (graph/collect-all-external-requires polylith-root)
          m (metrics/brick-metrics polylith-root :component "util" graph inverted external-requires)]
      (is (= "util" (:brick-name m)))
      (is (= :component (:brick-type m)))
      (is (integer? (:ca m)))
      (is (integer? (:ce m)))
      (is (number? (:instability m)))
      (is (number? (:abstractness m)))
      (is (number? (:distance m)))
      (is (>= (:instability m) 0.0))
      (is (<= (:instability m) 1.0))
      (is (>= (:abstractness m) 0.0))
      (is (<= (:abstractness m) 1.0))
      (is (>= (:distance m) 0.0)))))

(deftest all-metrics-test
  (testing "calculates metrics for all components (excludes bases)"
    (let [metrics (metrics/all-metrics polylith-root)
          components (ws/find-components polylith-root)]
      ;; Only components, not bases
      (is (= (count components) (count metrics)))
      (is (every? #(= :component (:brick-type %)) metrics))
      (is (every? #(contains? % :brick-name) metrics))
      (is (every? #(contains? % :distance) metrics))
      ;; Check that util component is included
      (is (some #(= "util" (:brick-name %)) metrics)))))

(deftest zone-analysis-test
  (testing "zone functions return valid results"
    (let [metrics (metrics/all-metrics polylith-root)]
      ;; These may or may not find bricks depending on the codebase
      ;; Just verify they run without error and return sequences
      (is (sequential? (metrics/stable-abstractions metrics)))
      (is (sequential? (metrics/unstable-concretions metrics)))
      (is (sequential? (metrics/zone-of-pain metrics)))
      (is (sequential? (metrics/zone-of-uselessness metrics))))))
