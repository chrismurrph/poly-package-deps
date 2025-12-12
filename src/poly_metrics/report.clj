(ns poly-metrics.report
  "Output formatting for poly-metrics reports."
  (:require [clojure.string :as str]
            [poly-metrics.metrics :as metrics]
            [poly-metrics.graph :as graph]))

(defn format-metric
  "Format a single metric value to specified decimal places.
   Returns '-' for nil values."
  [value decimals]
  (if (nil? value)
    "-"
    (format (str "%." decimals "f") (double value))))

(defn format-usage
  "Format a set of names as a comma-separated string, or '-' if empty.
   If the result exceeds max-width, show count instead."
  [names max-width]
  (if (empty? names)
    "-"
    (let [formatted (str/join "," (sort names))]
      (if (<= (count formatted) max-width)
        formatted
        (str "(" (count names) ")")))))

(def ^:private col-pkg 28)

(defn type-display-name
  "Convert package type keyword to display string."
  [pkg-type]
  (case pkg-type
    :polylith-component "component"
    :polylith-base "base"
    :polylith-like-interface "interface"
    :polylith-like-package "package"
    :clojure-package "directory"
    (name pkg-type)))

(defn metrics-table-row
  "Format a single package's metrics as a table row."
  [m]
  (let [;; Support both old (:brick-name/:brick-type) and new (:name/:type) field names
        pkg-name (or (:name m) (:brick-name m))
        pkg-type (or (:type m) (:brick-type m))]
    (format (str "%-" col-pkg "s %-10s %3d %3d %5s %5s %5s")
            pkg-name
            (type-display-name pkg-type)
            (:ca m)
            (:ce m)
            (format-metric (:instability m) 2)
            (format-metric (:abstractness m) 2)
            (format-metric (:distance m) 2))))

(defn metrics-table-header
  "Return the table header string."
  []
  (let [total-width (+ col-pkg 10 4 4 6 6 6)]
    (str (format (str "%-" col-pkg "s %-10s %3s %3s %5s %5s %5s")
                 "Package" "Type" "Ca" "Ce" "I" "A" "D")
         "\n"
         (apply str (repeat total-width "-")))))

(defn print-metrics-table
  "Print metrics as a formatted table, sorted by distance descending.
   Packages with nil distance are sorted to the end."
  [metrics]
  (println (metrics-table-header))
  ;; Sort: non-nil distances descending, then nil distances at end (by name)
  (let [sorted (sort-by (fn [m]
                          [(if (:distance m) 0 1)  ; non-nil first
                           (- (or (:distance m) 0))  ; by distance desc
                           (or (:name m) (:brick-name m))])  ; then by name
                        metrics)]
    (doseq [m sorted]
      (println (metrics-table-row m)))))

(defn print-health-summary
  "Print codebase health summary."
  [health]
  (println)
  (println "Summary:")
  ;; Support both old (:brick-count) and new (:package-count) field names
  (let [pkg-count (or (:package-count health) (:brick-count health))
        with-distance (:packages-with-distance health)]
    (printf "  Package count:  %d%n" pkg-count)
    (when (and with-distance (not= with-distance pkg-count))
      (printf "  With metrics:   %d (others are plain Clojure packages)%n" with-distance))
    (printf "  Mean distance:  %s%n" (format-metric (:mean-distance health) 3))
    (printf "  Max distance:   %s%n" (format-metric (:max-distance health) 3))
    (printf "  Min distance:   %s%n" (format-metric (:min-distance health) 3))))

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

(defn set-to-json
  "Convert a set to a JSON array string."
  [s]
  (str "[" (str/join "," (map #(str "\"" (json-escape %) "\"") (sort s))) "]"))

(defn metric-to-json
  "Convert a metric map to JSON string."
  [m]
  (let [pkg-name (or (:name m) (:brick-name m))
        pkg-type (or (:type m) (:brick-type m))
        a (:abstractness m)
        d (:distance m)]
    (format "{\"name\":\"%s\",\"type\":\"%s\",\"ca\":%d,\"ce\":%d,\"instability\":%.4f,\"abstractness\":%s,\"distance\":%s}"
            (json-escape pkg-name)
            (type-display-name pkg-type)
            (:ca m)
            (:ce m)
            (double (:instability m))
            (if a (format "%.4f" (double a)) "null")
            (if d (format "%.4f" (double d)) "null"))))

(defn health-to-json
  "Convert health map to JSON string."
  [health]
  (let [pkg-count (or (:package-count health) (:brick-count health))]
    (format "{\"package_count\":%d,\"mean_distance\":%.4f,\"max_distance\":%.4f,\"min_distance\":%.4f}"
            pkg-count
            (double (:mean-distance health))
            (double (:max-distance health))
            (double (:min-distance health)))))

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
    "This component is isolated - nothing depends on it and it depends on nothing. Instability defaults to 0."

    (< i 0.2)
    (str (format "Very stable (I=%.2f). " i)
         (format "%d component(s) depend on this, but it only depends on %d. " ca ce)
         "You NEED to be stable because many depend on you. "
         "This is GOOD if you have a clean interface (high A) - dependents are protected from your internals. "
         "This is BAD if you expose implementation details (low A) - changes could break many dependents.")

    (<= i 0.4)
    (str (format "Mostly stable (I=%.2f). " i)
         (format "More components depend on this (%d) than it depends on (%d). " ca ce)
         "You need to be somewhat careful about changes. "
         "Check the Abstractness metric - stable components should have clean interfaces.")

    (<= i 0.6)
    (str (format "Balanced (I=%.2f). " i)
         (format "Similar number of dependents (%d) and dependencies (%d). " ca ce)
         "Middle-tier component with moderate change risk in both directions.")

    (<= i 0.8)
    (str (format "Mostly unstable (I=%.2f). " i)
         (format "Depends on %d component(s) but only %d depend on it. " ce ca)
         "You're fairly free to change since few depend on you. "
         "GOOD: Low-risk place for implementation details.")

    :else
    (str (format "Very unstable (I=%.2f). " i)
         (format "Depends on %d component(s) but only %d depend on it. " ce ca)
         "You're free to change - this is an entry-point with no downstream dependents. "
         "GOOD: The ideal place for concrete implementation details. "
         "Note: You're affected by upstream changes, but you don't affect other components.")))

(defn describe-abstractness
  "Explain abstractness in plain English.
   interface-count: number of interface namespaces accessed by other components
   total-count: total namespaces in this component accessed by other components
   (total - interface = implementation ns that are 'leaked' to other components)"
  [a interface-count total-count]
  (let [impl-count (- total-count interface-count)]
    (cond
      (zero? total-count)
      (str "No external access (A=1.00 by default). "
           "No other components require any namespaces from this component. "
           "This means either: (1) it's an entry-point used only by bases/projects, or (2) it's unused. "
           "GOOD: Nothing to leak means no abstraction problems. "
           "Check the Dependents section above to see if anything uses this.")

      (and (= a 1.0) (pos? interface-count))
      (str (format "Perfect abstraction (A=%.2f). " a)
           (format "All %d externally-accessed namespace(s) are interface namespaces. " interface-count)
           "GOOD: Other components access this through its public API only. "
           "The implementation is fully encapsulated - you can refactor internals freely.")

      (> a 0.8)
      (str (format "Highly abstract (A=%.2f). " a)
           (format "%d of %d externally-accessed namespaces are interfaces (%d implementation). " interface-count total-count impl-count)
           "GOOD: Most access goes through the interface. "
           (format "Minor issue: %d implementation namespace(s) are required directly by other components. " impl-count)
           "Consider routing that access through interface functions instead.")

      (> a 0.5)
      (str (format "Moderately abstract (A=%.2f). " a)
           (format "%d interface, %d implementation namespace(s) accessed externally. " interface-count impl-count)
           "MIXED: Some external access bypasses the interface. "
           "If this component is stable (low I), this is a concern - other components are coupled to your internals.")

      (> a 0.0)
      (str (format "Mostly concrete (A=%.2f). " a)
           (format "Only %d interface namespace(s), but %d implementation namespace(s) accessed externally. " interface-count impl-count)
           "BAD if stable: This is a leaky abstraction. Other components require your implementation namespaces directly. "
           "They're coupled to your internals, making this component hard to refactor safely. "
           "FIX: Add functions to the interface that wrap the implementation, then update callers.")

      :else
      (str (format "No abstraction (A=%.2f). " a)
           (format "All %d externally-accessed namespace(s) are implementation, not interface. " impl-count)
           "BAD if stable: Complete abstraction leak. Other components bypass the interface entirely. "
           "This tightly couples dependents to implementation details. "
           "FIX: Create or expand the interface namespace, expose needed functions there, update callers."))))

(defn describe-distance
  "Explain distance from main sequence in plain English.
   The main sequence is the line A + I = 1. Distance measures deviation from it.
   - Stable components (low I) should be abstract (high A) to protect dependents
   - Unstable components (high I) can be concrete (low A) since nothing depends on them"
  [d a i ca]
  (let [stable? (<= i 0.4)       ; stable if 40% or less of coupling is outgoing
        unstable? (> i 0.7)      ; clearly unstable
        balanced? (and (> i 0.4) (<= i 0.7))
        abstract? (>= a 0.8)     ; clean interface
        concrete? (< a 0.3)      ; leaky
        has-dependents? (pos? ca)]
    (cond
      ;; Perfect or near-perfect: on the main sequence
      (< d 0.1)
      (str (format "Excellent (D=%.2f). " d)
           "This component is on the 'main sequence' - the ideal balance of abstraction and stability. "
           (cond
             (and stable? abstract?)
             "It's stable AND abstract: a well-encapsulated component that others can safely depend on."
             (and unstable? concrete?)
             "It's unstable AND concrete: implementation details where they belong (near the entry-points)."
             abstract?
             "Clean interface - all external access goes through the API."
             :else
             "Good balance between abstraction and stability."))

      (< d 0.3)
      (str (format "Good (D=%.2f). " d)
           "Close to the main sequence. "
           (cond
             abstract? "Clean interface with all access through the API."
             stable? "This stable component has reasonable abstraction."
             :else "Acceptable balance of abstraction and stability."))

      ;; Zone of pain: stable + concrete (low I, low A) with dependents
      (and stable? concrete? has-dependents?)
      (str (format "Zone of Pain (D=%.2f). " d)
           "This component is STABLE (many depend on it) but CONCRETE (implementation exposed). "
           "BAD: This is the worst combination. Changes to implementation details risk breaking dependents. "
           "The component is rigid and hard to evolve safely. "
           "FIX: Route external access through interface namespaces to decouple dependents from internals.")

      ;; Stable but leaky (not quite zone of pain, but still concerning)
      (and stable? (not abstract?) has-dependents?)
      (str (format "Leaky abstraction (D=%.2f). " d)
           (format "Stable (I=%.2f) but low abstraction (A=%.2f). " i a)
           "Some external access bypasses the interface. "
           "CONCERN: Dependents may be coupled to your internals - refactoring could be risky. "
           "FIX: Route more access through interface functions.")

      ;; Zone of uselessness: truly unstable + abstract (high I, high A, few dependents)
      (and unstable? abstract? (< ca 2))
      (str (format "Zone of Uselessness (D=%.2f). " d)
           "This component is UNSTABLE (few depend on it) but ABSTRACT (clean interface). "
           "UNUSUAL: Why have a clean interface if nothing uses it? "
           "In Polylith this often means the component is used by bases/projects (not other components). "
           "This may be fine - check if it's an entry-point wiring things together.")

      ;; Abstract with some dependents - this is actually fine
      (and abstract? has-dependents?)
      (str (format "Good (D=%.2f). " d)
           "Clean interface - all external access goes through the API. "
           (if balanced?
             "Middle-tier component with good encapsulation."
             "Well-encapsulated component."))

      ;; Balanced instability with good abstraction
      (and balanced? (>= a 0.5))
      (str (format "Fair (D=%.2f). " d)
           "Middle-tier component. "
           "Abstraction is reasonable - most access goes through the interface.")

      (< d 0.5)
      (str (format "Fair (D=%.2f). " d)
           "Some deviation from the main sequence. "
           (cond
             stable? "Consider improving abstraction - route more access through interface namespaces."
             abstract? "Good encapsulation despite the distance score."
             :else "This is acceptable for a component at this stability level."))

      ;; High distance, various causes
      :else
      (str (format "Needs attention (D=%.2f). " d)
           "Significant deviation from the main sequence. "
           (cond
             (and stable? (not abstract?))
             "PROBLEM: Stable but concrete. Dependents are coupled to implementation details. Fix abstraction."
             (and unstable? abstract? (zero? ca))
             "UNUSUAL: Unstable and abstract with no dependents. Check if this component is actually needed."
             :else
             "Review the Abstractness and Instability metrics to understand why.")))))

(defn describe-overall-health
  "Give an overall assessment of the component."
  [m]
  (let [d (:distance m)
        a (:abstractness m)
        i (:instability m)
        ca (:ca m)
        ;; Field names from metrics.clj: :abstract-ns and :total-ns
        interface-ns (or (:abstract-ns m) 0)
        total-ns (or (:total-ns m) 0)
        impl-ns (- total-ns interface-ns)
        no-external-access? (zero? total-ns)
        clean-interface? (and (pos? interface-ns) (zero? impl-ns))
        leaky? (pos? impl-ns)
        stable? (< i 0.5)
        has-dependents? (pos? ca)]
    (cond
      ;; No external access from other components
      no-external-access?
      (cond
        (zero? ca)
        (str "ENTRY-POINT: No other components depend on this or require its namespaces. "
             "It's likely used only by bases/projects. "
             "This is fine - entry-points don't need abstraction.")

        (pos? ca)
        (str "UNUSUAL: " ca " component(s) list this as a dependency, but none require its namespaces directly. "
             "Check if the dependencies are actually used, or if access happens through re-exports."))

      ;; Perfect abstraction with dependents
      (and clean-interface? has-dependents?)
      (str "HEALTHY: All external access goes through the interface. "
           interface-ns " interface namespace(s) are used by other components. "
           "Implementation is fully encapsulated - safe to refactor internals.")

      ;; Perfect abstraction, no dependents
      clean-interface?
      (str "HEALTHY: Clean interface, though no other components depend on this. "
           "If this is intentional (entry-point component), that's fine.")

      ;; Zone of pain: stable + leaky
      (and stable? leaky? has-dependents?)
      (str "PROBLEM: Leaky abstraction in a stable component. "
           impl-ns " implementation namespace(s) are required directly by other components. "
           "Since " ca " component(s) depend on this, changes to those internals are risky. "
           "FIX: Expose needed functionality through interface namespaces, then update callers.")

      ;; Leaky but less critical (unstable)
      (and leaky? (not stable?))
      (str "MINOR ISSUE: " impl-ns " implementation namespace(s) are required externally. "
           "Since this component is unstable (few depend on it), this is less critical. "
           "Still, consider routing access through the interface for cleaner architecture.")

      ;; Leaky, somewhat stable
      leaky?
      (str "ISSUE: " impl-ns " implementation namespace(s) are required by other components. "
           "This couples dependents to your internals. "
           "Consider exposing needed functions through the interface.")

      :else
      "HEALTHY: This component has reasonable metrics.")))

(defn render-quadrant-diagram
  "Render the A/I quadrant diagram with a marker showing the package's position.
   I (instability) is x-axis: 1 on left, 0 on right
   A (abstractness) is y-axis: 1 on top, 0 on bottom"
  [instability abstractness]
  (let [;; Map I to column: I=1 -> col 0, I=0.5 -> col 1, I=0 -> col 2
        i-col (cond (> instability 0.5) 0   ; unstable (left)
                    (< instability 0.5) 2   ; stable (right)
                    :else 1)                ; middle
        ;; Map A to row: A=1 -> row 0, A=0.5 -> row 1, A=0 -> row 2
        a-row (cond (> abstractness 0.5) 0  ; abstract (top)
                    (< abstractness 0.5) 2  ; concrete (bottom)
                    :else 1)                ; middle
        ;; Grid positions for marker: [row col] -> [line-index char-position]
        marker-positions {[0 0] [3 4]   ; top-left (uselessness)
                          [0 1] [3 13]  ; top-middle
                          [0 2] [3 18]  ; top-right (ideal stable+abstract)
                          [1 0] [5 4]   ; middle-left
                          [1 1] [5 13]  ; center
                          [1 2] [5 18]  ; middle-right
                          [2 0] [7 4]   ; bottom-left (ideal unstable+concrete)
                          [2 1] [7 13]  ; bottom-middle
                          [2 2] [9 18]} ; bottom-right (pain)
        [marker-row marker-col] (get marker-positions [a-row i-col] [5 13])
        lines ["        A=1 (abstract)    "
               "             │            "
               "   Zone of   │   Ideal    "
               "  Uselessness│            "
               "             │            "
               "I=1 ─────────┼───────── I=0"
               "             │            "
               "    Ideal    │   Zone of  "
               "             │    Pain    "
               "             │            "
               "        A=0 (concrete)    "]]
    (println)
    (println "POSITION")
    (doseq [[idx line] (map-indexed vector lines)]
      (if (= idx marker-row)
        (println (str (subs line 0 marker-col) "*" (subs line (inc marker-col))))
        (println line)))
    (println)))

(defn print-component-detail
  "Print detailed explanation for a single package."
  [m deps dependents]
  (let [;; Support both old and new field names
        pkg-name (or (:name m) (:brick-name m))
        pkg-type (or (:type m) (:brick-type m))
        ;; Field names from metrics.clj
        interface-ns (or (:abstract-ns m) 0)
        total-ns (or (:total-ns m) 0)
        impl-ns (- total-ns interface-ns)
        has-abstractness? (some? (:abstractness m))]

    (println (str "Package: " pkg-name))
    (println (str "Type: " (type-display-name pkg-type)))
    (println (apply str (repeat 70 "-")))
    (println)

    ;; Dependencies
    (println "DEPENDENCIES (Ce)")
    (if (empty? deps)
      (println "  This package has no dependencies on other packages.")
      (do
        (println (format "  Depends on %d package(s):" (count deps)))
        (doseq [dep (sort deps)]
          (println (str "    - " dep)))))
    (println)

    ;; Dependents
    (println "DEPENDENTS (Ca)")
    (if (empty? dependents)
      (println "  No other packages depend on this one.")
      (do
        (println (format "  %d package(s) depend on this:" (count dependents)))
        (doseq [dep (sort dependents)]
          (println (str "    - " dep)))))
    (println)

    ;; Metrics with explanations
    (println "METRICS")
    (println (apply str (repeat 70 "-")))

    ;; Ca
    (println)
    (println (format "Afferent Coupling (Ca): %d" (:ca m)))
    (println "  'Who depends on me?' - Number of other packages that depend on this one.")
    (println "  High Ca = heavily depended-on. Ca=0 = entry-point (nothing depends on it).")
    (println)

    ;; Ce
    (println (format "Efferent Coupling (Ce): %d" (:ce m)))
    (println "  'Who do I depend on?' - Number of packages this one depends on.")
    (println "  High Ce = depends on many. Ce=0 = dead-end (depends on nothing).")
    (println)

    ;; Instability
    (println (format "Instability (I): %.2f  [Formula: Ce / (Ca + Ce)]" (:instability m)))
    (println "  I=0 (stable) means many depend on you, so changes are risky.")
    (println "  I=1 (unstable) means few depend on you, so you're free to change.")
    (println)
    (println (str "  " (describe-instability (:instability m) (:ce m) (:ca m))))
    (println)

    ;; Abstractness (only for packages with interface detection)
    (if has-abstractness?
      (do
        (println (format "Abstractness (A): %.2f  [Formula: interface-ns / total-ns externally accessed]" (:abstractness m)))
        (println (format "  External access breakdown: %d interface ns, %d implementation ns" interface-ns impl-ns))
        (println)
        (println (str "  " (describe-abstractness (:abstractness m) interface-ns total-ns)))
        (println)

        ;; Distance
        (println (format "Distance (D): %.2f  [Formula: |A + I - 1|]" (:distance m)))
        (println)
        (println (str "  " (describe-distance (:distance m) (:abstractness m) (:instability m) (:ca m))))
        (println)

        ;; Quadrant diagram
        (render-quadrant-diagram (:instability m) (:abstractness m))

        ;; Overall assessment
        (println "ASSESSMENT")
        (println (describe-overall-health m)))

      ;; No interface detection for this package type
      (do
        (println "Abstractness (A): -")
        (println "  This package type has no interface detection.")
        (println "  Abstractness and Distance metrics are not applicable.")
        (println)
        (println "Distance (D): -")
        (println "  N/A - requires interface detection.")
        (println)
        (println "ASSESSMENT")
        (println (str "This is a plain Clojure package. Only Ca/Ce/I metrics apply. "
                      "To get full metrics, organize code using Polylith conventions "
                      "(components/, bases/) or Polylith-like conventions (interfaces/, packages/)."))))))
