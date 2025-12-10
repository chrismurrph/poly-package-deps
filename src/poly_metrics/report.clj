(ns poly-metrics.report
  "Output formatting for poly-metrics reports."
  (:require [clojure.string :as str]
            [poly-metrics.metrics :as metrics]
            [poly-metrics.graph :as graph]))

(defn format-metric
  "Format a single metric value to specified decimal places."
  [value decimals]
  (format (str "%." decimals "f") (double value)))

(defn metrics-table-row
  "Format a single brick's metrics as a table row."
  [m]
  (format "%-28s %-10s %3d %3d %5s %5s %5s"
          (:brick-name m)
          (name (:brick-type m))
          (:ca m)
          (:ce m)
          (format-metric (:instability m) 2)
          (format-metric (:abstractness m) 2)
          (format-metric (:distance m) 2)))

(defn metrics-table-header
  "Return the table header string."
  []
  (str (format "%-28s %-10s %3s %3s %5s %5s %5s"
               "Brick" "Type" "Ca" "Ce" "I" "A" "D")
       "\n"
       (apply str (repeat 68 "-"))))

(defn print-metrics-table
  "Print metrics as a formatted table, sorted by distance descending."
  [metrics]
  (println (metrics-table-header))
  (doseq [m (sort-by :distance > metrics)]
    (println (metrics-table-row m))))

(defn print-health-summary
  "Print codebase health summary."
  [health]
  (println)
  (println "Summary:")
  (printf "  Brick count:    %d%n" (:brick-count health))
  (printf "  Mean distance:  %s%n" (format-metric (:mean-distance health) 3))
  (printf "  Max distance:   %s%n" (format-metric (:max-distance health) 3))
  (printf "  Min distance:   %s%n" (format-metric (:min-distance health) 3)))

(defn print-cycles
  "Print cycle information."
  [cycles]
  (when (seq cycles)
    (println)
    (println "⚠ Cycles detected:")
    (doseq [cycle cycles]
      (println " " (str/join " → " cycle) "→" (first cycle)))))

(defn print-report
  "Print a complete text report."
  [metrics health cycles]
  (print-metrics-table metrics)
  (print-health-summary health)
  (print-cycles cycles)
  (println)
  (if (and (< (:mean-distance health) 0.5)
           (zero? (count cycles)))
    (println "✓ Codebase is healthy")
    (println "⚠ Codebase needs attention")))

(defn edn-report
  "Generate report data as EDN."
  [metrics health cycles]
  {:metrics (vec (sort-by :distance > metrics))
   :health health
   :cycles cycles
   :healthy? (and (< (:mean-distance health) 0.5)
                  (zero? (count cycles)))})

(defn json-escape
  "Escape a string for JSON output."
  [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")
      (str/replace "\t" "\\t")))

(defn metric-to-json
  "Convert a metric map to JSON string."
  [m]
  (format "{\"brick_name\":\"%s\",\"brick_type\":\"%s\",\"ca\":%d,\"ce\":%d,\"instability\":%.4f,\"abstractness\":%.4f,\"distance\":%.4f}"
          (json-escape (:brick-name m))
          (name (:brick-type m))
          (:ca m)
          (:ce m)
          (double (:instability m))
          (double (:abstractness m))
          (double (:distance m))))

(defn health-to-json
  "Convert health map to JSON string."
  [health]
  (format "{\"brick_count\":%d,\"mean_distance\":%.4f,\"max_distance\":%.4f,\"min_distance\":%.4f}"
          (:brick-count health)
          (double (:mean-distance health))
          (double (:max-distance health))
          (double (:min-distance health))))

(defn cycle-to-json
  "Convert a cycle (vector of strings) to JSON array string."
  [cycle]
  (str "[" (str/join "," (map #(str "\"" (json-escape %) "\"") cycle)) "]"))

(defn json-report
  "Generate report as JSON string."
  [metrics health cycles]
  (let [metrics-json (str "[" (str/join "," (map metric-to-json (sort-by :distance > metrics))) "]")
        cycles-json (str "[" (str/join "," (map cycle-to-json cycles)) "]")
        healthy (and (< (:mean-distance health) 0.5)
                     (zero? (count cycles)))]
    (format "{\"metrics\":%s,\"health\":%s,\"cycles\":%s,\"healthy\":%s}"
            metrics-json
            (health-to-json health)
            cycles-json
            (if healthy "true" "false"))))

(defn generate-report
  "Generate a complete report for a workspace.
   format can be :text, :edn, or :json"
  [workspace-root & {:keys [format] :or {format :text}}]
  (let [all-metrics (metrics/all-metrics workspace-root)
        health (metrics/codebase-health all-metrics)
        dep-graph (graph/build-dependency-graph workspace-root)
        cycles (graph/find-all-cycles dep-graph)]
    (case format
      :text (with-out-str (print-report all-metrics health cycles))
      :edn (edn-report all-metrics health cycles)
      :json (json-report all-metrics health cycles))))

;; Component detail explanations

(defn describe-instability
  "Explain instability in plain English."
  [i ce ca]
  (cond
    (zero? (+ ca ce))
    "This component is isolated - nothing depends on it and it depends on nothing."

    (< i 0.2)
    (format "Very stable (I=%.2f). %d other components depend on this, but it only depends on %d others. Changes here could break many things, so be careful."
            i ca ce)

    (< i 0.4)
    (format "Mostly stable (I=%.2f). More components depend on this (%d) than it depends on (%d). Treat changes with care."
            i ca ce)

    (< i 0.6)
    (format "Balanced (I=%.2f). Similar number of incoming (%d) and outgoing (%d) dependencies."
            i ca ce)

    (< i 0.8)
    (format "Mostly unstable (I=%.2f). Depends on %d components but only %d depend on it. Easier to change safely."
            i ce ca)

    :else
    (format "Very unstable (I=%.2f). Depends on %d components but only %d depend on it. This is a leaf component - easy to change without breaking others."
            i ce ca)))

(defn describe-abstractness
  "Explain abstractness in plain English."
  [a interface-count total-count]
  (cond
    (zero? total-count)
    "No namespaces found in this component."

    (> a 0.8)
    (format "Highly abstract (A=%.2f). %d of %d namespaces are interface definitions. This component is mostly API surface."
            a interface-count total-count)

    (> a 0.5)
    (format "Moderately abstract (A=%.2f). %d of %d namespaces are interfaces. Good balance of API and implementation."
            a interface-count total-count)

    (> a 0.2)
    (format "Mostly concrete (A=%.2f). Only %d of %d namespaces are interfaces. Lots of implementation detail."
            a interface-count total-count)

    :else
    (format "Very concrete (A=%.2f). Only %d of %d namespaces are interfaces. This is implementation-heavy."
            a interface-count total-count)))

(defn describe-distance
  "Explain distance from main sequence in plain English.
   With new abstractness: A=1.0 means all access via interface, A=0 means all access to impl."
  [d a i ce]
  (cond
    ;; Perfect or near-perfect abstraction (all access through interface)
    (>= a 0.99)
    (format "Excellent (D=%.2f). All external access goes through the interface."
            d)

    (< d 0.1)
    (format "Excellent (D=%.2f). This component is well-balanced."
            d)

    (< d 0.3)
    (format "Good (D=%.2f). Close to the ideal balance."
            d)

    ;; Stable with good abstraction
    (and (< i 0.3) (>= a 0.8))
    (format "Good (D=%.2f). This stable component has clean abstraction - most access is through the interface."
            d)

    (< d 0.5)
    (format "Fair (D=%.2f). Some room for improvement."
            d)

    ;; Leaky abstraction: stable but external code accesses implementation
    (and (< i 0.5) (< a 0.5))
    (format "Poor (D=%.2f). Leaky abstraction - other components are requiring implementation namespaces directly instead of going through the interface. This couples them to your internals."
            d)

    ;; No interface at all
    (< a 0.1)
    (format "Poor (D=%.2f). Other components require implementation namespaces directly. There's no interface protecting your internals."
            d)

    ;; Unstable with high abstraction (zone of uselessness)
    (and (> i 0.7) (> a 0.7))
    (format "Unusual (D=%.2f). Highly abstract but unstable. The interface is clean, but this component depends on many others."
            d)

    :else
    (format "Needs attention (D=%.2f). Consider whether external access should go through interface namespaces."
            d)))

(defn describe-overall-health
  "Give an overall assessment of the component."
  [m]
  (let [d (:distance m)
        a (:abstractness m)
        i (:instability m)
        interface-ns (or (:interface-ns m) 0)
        leaky-impl-ns (or (:leaky-impl-ns m) 0)
        no-external-access? (and (zero? interface-ns) (zero? leaky-impl-ns))
        clean-interface? (and (pos? interface-ns) (zero? leaky-impl-ns))
        leaky? (pos? leaky-impl-ns)
        stable? (< i 0.3)]
    (cond
      ;; No external access - could be unused or a leaf
      no-external-access?
      (if (> i 0.7)
        "This is a leaf component - nothing else depends on it."
        "This component's namespaces aren't required by other components.")

      ;; Perfect abstraction
      clean-interface?
      "This component is well-designed. All external access goes through the interface."

      ;; Stable with leaky abstraction - the real problem
      (and stable? leaky?)
      (format "Issues: leaky abstraction. %d implementation namespace(s) are required directly by other components. Consider routing access through interface namespaces."
              leaky-impl-ns)

      ;; Unstable with leaky abstraction - less critical since it's easy to change
      leaky?
      (format "Minor issue: %d implementation namespace(s) are required externally. Since this component is unstable, it's less critical."
              leaky-impl-ns)

      :else
      "This component is reasonably healthy.")))

(defn print-component-detail
  "Print detailed explanation for a single component."
  [m deps dependents]
  (println (str "Component: " (:brick-name m)))
  (println (str "Type: " (name (:brick-type m))))
  (println (apply str (repeat 50 "-")))
  (println)

  ;; Dependencies
  (println "DEPENDENCIES")
  (if (empty? deps)
    (println "  This component has no dependencies on other components.")
    (do
      (println (format "  Depends on %d component(s):" (count deps)))
      (doseq [dep (sort deps)]
        (println (str "    - " dep)))))
  (println)

  ;; Dependents
  (println "DEPENDENTS")
  (if (empty? dependents)
    (println "  No other components depend on this one.")
    (do
      (println (format "  %d component(s) depend on this:" (count dependents)))
      (doseq [dep (sort dependents)]
        (println (str "    - " dep)))))
  (println)

  ;; Metrics with explanations
  (println "METRICS")
  (println)

  (println (format "  Afferent Coupling (Ca): %d" (:ca m)))
  (println "    Number of components that depend on this one.")
  (println)

  (println (format "  Efferent Coupling (Ce): %d" (:ce m)))
  (println "    Number of components this one depends on.")
  (println)

  (println (format "  Instability (I): %.2f" (:instability m)))
  (println (str "    " (describe-instability (:instability m) (:ce m) (:ca m))))
  (println)

  (let [interface-ns (or (:interface-ns m) 0)
        leaky-impl-ns (or (:leaky-impl-ns m) 0)]
    (println (format "  Abstractness (A): %.2f" (:abstractness m)))
    (println (format "    External access: %d interface ns, %d implementation ns" interface-ns leaky-impl-ns))
    (cond
      (and (zero? interface-ns) (zero? leaky-impl-ns))
      (println "    No other components require this one's namespaces directly.")

      (zero? leaky-impl-ns)
      (println "    All external access goes through the interface. Clean!")

      (zero? interface-ns)
      (println "    All external access is to implementation namespaces. No interface!")

      :else
      (println (format "    %.0f%% of external access goes through the interface."
                       (* 100 (:abstractness m))))))
  (println)

  (println (format "  Distance (D): %.2f" (:distance m)))
  (println (str "    " (describe-distance (:distance m) (:abstractness m) (:instability m) (:ce m))))
  (println)

  (println "ASSESSMENT")
  (println (str "  " (describe-overall-health m))))
