(ns poly-metrics.core
  "CLI entry point for poly-metrics."
  (:require [poly-metrics.workspace :as ws]
            [poly-metrics.graph :as graph]
            [poly-metrics.metrics :as metrics]
            [poly-metrics.report :as report]
            [clojure.java.io :as io]))

(defn usage []
  (println "poly-metrics: JDepend-style metrics for Polylith workspaces")
  (println)
  (println "Usage:")
  (println "  clj -M:run [workspace-path] [format]      Show all components")
  (println "  clj -M:run [workspace-path] component NAME  Show details for one component")
  (println)
  (println "Arguments:")
  (println "  workspace-path  Path to Polylith workspace (default: current directory)")
  (println "  format          Output format: text, edn, json (default: text)")
  (println "  NAME            Component or base name to inspect")
  (println)
  (println "Examples:")
  (println "  clj -M:run /path/to/workspace              # Full report")
  (println "  clj -M:run /path/to/workspace json         # Full report as JSON")
  (println "  clj -M:run /path/to/workspace component util  # Details for 'util'")
  (println)
  (println "Exit codes:")
  (println "  0  Codebase is healthy (mean distance < 0.5, no cycles)")
  (println "  1  Codebase needs attention")
  (println "  2  Error (not a Polylith workspace, component not found)"))

(defn show-component-detail
  "Show detailed metrics for a single component."
  [workspace-root component-name]
  (let [all-metrics (metrics/all-metrics workspace-root)
        dep-graph (graph/build-dependency-graph workspace-root)
        inverted (graph/invert-graph dep-graph)
        m (first (filter #(= component-name (:brick-name %)) all-metrics))]
    (if m
      (let [deps (graph/direct-dependencies dep-graph component-name)
            dependents (graph/direct-dependents inverted component-name)]
        (report/print-component-detail m deps dependents)
        0)  ;; success
      (do
        (binding [*out* *err*]
          (println (str "Error: Component '" component-name "' not found."))
          (println)
          (println "Available components:")
          (doseq [name (->> all-metrics (map :brick-name) sort)]
            (println (str "  " name))))
        2))))  ;; error

(defn -main [& args]
  (let [workspace-root (or (first args) ".")
        second-arg (second args)
        third-arg (nth args 2 nil)]

    ;; Handle help flag
    (when (contains? #{"-h" "--help" "help"} workspace-root)
      (usage)
      (System/exit 0))

    ;; Validate workspace
    (when-not (.exists (io/file workspace-root "workspace.edn"))
      (binding [*out* *err*]
        (println "Error: Not a Polylith workspace (no workspace.edn found)")
        (println "Path:" (.getAbsolutePath (io/file workspace-root))))
      (System/exit 2))

    ;; Check for component detail mode
    (when (= "component" second-arg)
      (if third-arg
        (System/exit (show-component-detail workspace-root third-arg))
        (do
          (binding [*out* *err*]
            (println "Error: Please specify a component name.")
            (println "Usage: clj -M:run [workspace-path] component NAME"))
          (System/exit 2))))

    ;; Regular report mode
    (let [output-format (or second-arg "text")]
      ;; Validate format
      (when-not (contains? #{"text" "edn" "json"} output-format)
        (binding [*out* *err*]
          (println "Error: Invalid format. Must be one of: text, edn, json"))
        (System/exit 2))

      ;; Generate report
      (let [all-metrics (metrics/all-metrics workspace-root)
            health (metrics/codebase-health all-metrics)
            dep-graph (graph/build-dependency-graph workspace-root)
            cycles (graph/find-all-cycles dep-graph)
            healthy? (and (< (:mean-distance health) 0.5)
                          (zero? (count cycles)))]

        ;; Output in requested format
        (case output-format
          "text" (report/print-report all-metrics health cycles)
          "edn"  (prn (report/edn-report all-metrics health cycles))
          "json" (println (report/json-report all-metrics health cycles)))

        ;; Exit with appropriate code
        (System/exit (if healthy? 0 1))))))
