(ns poly-metrics.report-test
  (:require [clojure.test :refer [deftest testing is]]
            [poly-metrics.report :as report]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(def polylith-root "/tmp/polylith-test")

;; Sample metrics for testing
(def sample-metrics
  [{:brick-name "util"
    :brick-type :component
    :ca 5
    :ce 0
    :instability 0.0
    :abstractness 0.2
    :distance 0.8
    :used-by-bases #{"poly-cli"}
    :used-by-projects #{"dev" "poly"}}
   {:brick-name "command"
    :brick-type :component
    :ca 1
    :ce 10
    :instability 0.91
    :abstractness 0.1
    :distance 0.01
    :used-by-bases #{"poly-cli"}
    :used-by-projects #{}}
   {:brick-name "poly-cli"
    :brick-type :base
    :ca 0
    :ce 5
    :instability 1.0
    :abstractness 0.0
    :distance 0.0
    :used-by-bases #{}
    :used-by-projects #{}}])

(def sample-health
  {:brick-count 3
   :mean-distance 0.27
   :max-distance 0.8
   :min-distance 0.0})

(def sample-cycles
  [["a" "b"] ["c" "d" "e"]])

;; Unit tests

(deftest format-metric-test
  (testing "formats decimals correctly"
    (is (= "0.50" (report/format-metric 0.5 2)))
    (is (= "0.500" (report/format-metric 0.5 3)))
    (is (= "1.00" (report/format-metric 1.0 2)))
    (is (= "0.12" (report/format-metric 0.123 2)))))

(deftest metrics-table-row-test
  (testing "formats row correctly"
    (let [row (report/metrics-table-row (first sample-metrics))]
      (is (str/includes? row "util"))
      (is (str/includes? row "component"))
      (is (str/includes? row "0.00"))  ;; instability
      (is (str/includes? row "0.20"))  ;; abstractness
      (is (str/includes? row "0.80"))  ;; distance
      (is (str/includes? row "poly-cli"))  ;; bases
      (is (str/includes? row "dev,poly"))))) ;; projects (sorted)

(deftest metrics-table-header-test
  (testing "header contains column names"
    (let [header (report/metrics-table-header)]
      (is (str/includes? header "Brick"))
      (is (str/includes? header "Type"))
      (is (str/includes? header "Ca"))
      (is (str/includes? header "Ce"))
      (is (str/includes? header "I"))
      (is (str/includes? header "A"))
      (is (str/includes? header "D"))
      (is (str/includes? header "Bases"))
      (is (str/includes? header "Projects")))))

(deftest edn-report-test
  (testing "generates valid EDN structure"
    (let [report (report/edn-report sample-metrics sample-health [])]
      (is (map? report))
      (is (contains? report :metrics))
      (is (contains? report :health))
      (is (contains? report :cycles))
      (is (contains? report :healthy?))
      (is (vector? (:metrics report)))
      (is (vector? (:cycles report)))))

  (testing "metrics sorted by distance descending"
    (let [report (report/edn-report sample-metrics sample-health [])
          distances (map :distance (:metrics report))]
      (is (= distances (reverse (sort distances))))))

  (testing "healthy? is true when no cycles and low distance"
    (let [report (report/edn-report sample-metrics sample-health [])]
      (is (:healthy? report))))

  (testing "healthy? is false with cycles"
    (let [report (report/edn-report sample-metrics sample-health sample-cycles)]
      (is (not (:healthy? report)))))

  (testing "healthy? is false with high mean distance"
    (let [bad-health {:brick-count 1 :mean-distance 0.7 :max-distance 0.7 :min-distance 0.7}
          report (report/edn-report sample-metrics bad-health [])]
      (is (not (:healthy? report))))))

(deftest json-escape-test
  (testing "escapes special characters"
    (is (= "hello\\nworld" (report/json-escape "hello\nworld")))
    (is (= "say \\\"hi\\\"" (report/json-escape "say \"hi\"")))
    (is (= "back\\\\slash" (report/json-escape "back\\slash")))))

(deftest json-report-test
  (testing "generates valid JSON structure"
    (let [json (report/json-report sample-metrics sample-health [])]
      (is (string? json))
      (is (str/starts-with? json "{"))
      (is (str/ends-with? json "}"))
      (is (str/includes? json "\"metrics\""))
      (is (str/includes? json "\"health\""))
      (is (str/includes? json "\"cycles\""))
      (is (str/includes? json "\"healthy\""))))

  (testing "includes metric values"
    (let [json (report/json-report sample-metrics sample-health [])]
      (is (str/includes? json "\"util\""))
      (is (str/includes? json "\"component\""))
      (is (str/includes? json "\"base\""))))

  (testing "healthy is boolean"
    (let [json-healthy (report/json-report sample-metrics sample-health [])
          json-unhealthy (report/json-report sample-metrics sample-health sample-cycles)]
      (is (str/includes? json-healthy "\"healthy\":true"))
      (is (str/includes? json-unhealthy "\"healthy\":false")))))

(deftest print-report-test
  (testing "text output contains all sections"
    (let [output (with-out-str (report/print-report sample-metrics sample-health []))]
      (is (str/includes? output "Brick"))
      (is (str/includes? output "util"))
      (is (str/includes? output "Summary"))
      (is (str/includes? output "Mean distance"))
      (is (str/includes? output "healthy"))))

  (testing "shows cycles when present"
    (let [output (with-out-str (report/print-report sample-metrics sample-health sample-cycles))]
      (is (str/includes? output "Cycles detected"))
      (is (str/includes? output "a → b")))))

;; Integration test with real workspace

(deftest generate-report-integration-test
  (testing "generates text report for polylith"
    (let [text-report (report/generate-report polylith-root :format :text)]
      (is (string? text-report))
      (is (str/includes? text-report "Brick"))
      (is (str/includes? text-report "util"))))

  (testing "generates EDN report for polylith"
    (let [edn-report (report/generate-report polylith-root :format :edn)]
      (is (map? edn-report))
      (is (pos? (count (:metrics edn-report))))
      (is (some #(= "util" (:brick-name %)) (:metrics edn-report)))))

  (testing "generates JSON report for polylith"
    (let [json-report (report/generate-report polylith-root :format :json)]
      (is (string? json-report))
      (is (str/includes? json-report "\"util\""))
      (is (str/includes? json-report "\"metrics\"")))))
