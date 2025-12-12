(ns poly-metrics.core
  "CLI entry point for poly-metrics."
  (:require [poly-metrics.discovery :as discovery]
            [poly-metrics.graph :as graph]
            [poly-metrics.metrics :as metrics]
            [poly-metrics.report :as report]
            [clojure.java.io :as io]))

(defn usage []
  (println "poly-metrics: JDepend-style metrics for Clojure projects")
  (println)
  (println "Usage:")
  (println "  clj -M:run [project-path] [format]        Show all packages")
  (println "  clj -M:run [project-path] package NAME    Show details for one package")
  (println)
  (println "Arguments:")
  (println "  project-path  Path to Clojure project (default: current directory)")
  (println "  format        Output format: text, edn, json (default: text)")
  (println "  NAME          Package name to inspect")
  (println)
  (println "Supported project types:")
  (println "  - Polylith (components/, bases/)")
  (println "  - Polylith-like (interfaces/, packages/)")
  (println "  - Plain Clojure (any directory with .clj files)")
  (println)
  (println "Examples:")
  (println "  clj -M:run /path/to/project              # Full report")
  (println "  clj -M:run /path/to/project json         # Full report as JSON")
  (println "  clj -M:run /path/to/project package util # Details for 'util'")
  (println)
  (println "Exit codes:")
  (println "  0  Codebase is healthy (mean distance < 0.5, no cycles)")
  (println "  1  Codebase needs attention")
  (println "  2  Error (no Clojure files found, package not found)"))

(defn show-package-detail
  "Show detailed metrics for a single package."
  [root-dir package-name]
  (let [all-metrics (metrics/all-package-metrics root-dir)
        packages (discovery/discover-packages root-dir)
        relevant-packages (remove #(= :clojure-package (:type %)) packages)
        dep-graph (graph/build-package-dependency-graph root-dir relevant-packages)
        inverted (graph/invert-graph dep-graph)
        m (first (filter #(= package-name (:name %)) all-metrics))]
    (if m
      (let [deps (graph/direct-dependencies dep-graph package-name)
            dependents (graph/direct-dependents inverted package-name)]
        (report/print-component-detail m deps dependents)
        0)  ;; success
      (do
        (binding [*out* *err*]
          (println (str "Error: Package '" package-name "' not found."))
          (println)
          (println "Available packages:")
          (doseq [name (->> all-metrics (map :name) sort)]
            (println (str "  " name))))
        2))))  ;; error

(defn -main [& args]
  (let [root-dir (or (first args) ".")
        second-arg (second args)
        third-arg (nth args 2 nil)]

    ;; Handle help flag
    (when (contains? #{"-h" "--help" "help"} root-dir)
      (usage)
      (System/exit 0))

    ;; Validate directory exists
    (when-not (.isDirectory (io/file root-dir))
      (binding [*out* *err*]
        (println "Error: Directory not found")
        (println "Path:" (.getAbsolutePath (io/file root-dir))))
      (System/exit 2))

    ;; Check for package detail mode
    (when (= "package" second-arg)
      (if third-arg
        (System/exit (show-package-detail root-dir third-arg))
        (do
          (binding [*out* *err*]
            (println "Error: Please specify a package name.")
            (println "Usage: clj -M:run [project-path] package NAME"))
          (System/exit 2))))

    ;; Regular report mode
    (let [output-format (or second-arg "text")]
      ;; Validate format
      (when-not (contains? #{"text" "edn" "json"} output-format)
        (binding [*out* *err*]
          (println "Error: Invalid format. Must be one of: text, edn, json"))
        (System/exit 2))

      ;; Discover packages and generate report
      (let [packages (discovery/discover-packages root-dir)
            relevant-packages (remove #(= :clojure-package (:type %)) packages)
            _ (when (empty? relevant-packages)
                (binding [*out* *err*]
                  (println "Error: No Polylith or Polylith-like packages found")
                  (println "Path:" (.getAbsolutePath (io/file root-dir))))
                (System/exit 2))
            all-metrics (metrics/all-package-metrics root-dir)
            health (metrics/codebase-health all-metrics)
            dep-graph (graph/build-package-dependency-graph root-dir relevant-packages)
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
