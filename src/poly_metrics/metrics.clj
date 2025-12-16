(ns poly-metrics.metrics
  "JDepend-style metrics calculation for Clojure packages."
  (:require [poly-metrics.workspace :as ws]
            [poly-metrics.graph :as graph]
            [poly-metrics.discovery :as discovery]))

(defn instability
  "Calculate instability metric I = Ce / (Ca + Ce).
   Ranges from 0 (stable) to 1 (unstable).
   Convention: isolated component (Ca=0, Ce=0) is considered stable (returns 0)."
  [ca ce]
  (let [total (+ ca ce)]
    (if (zero? total)
      0.0
      (double (/ ce total)))))

(defn abstractness
  "Calculate abstractness metric: A = interface-ns / total-ns (externally accessed).
   Measures what proportion of external access goes through interface namespaces.
   Ranges from 0 (all access to impl) to 1 (all access via interface)."
  [interface-ns-count total-ns-count]
  (if (zero? total-ns-count)
    1.0  ;; No external access = fully abstract (nothing exposed)
    (double (/ interface-ns-count total-ns-count))))

(defn distance
  "Calculate distance from main sequence: D = |A + I - 1|.
   Ranges from 0 (ideal) to 1 (worst).

   The 'main sequence' is the line where A + I = 1.
   - Stable components (low I) should be abstract (high A)
   - Unstable components (high I) should be concrete (low A)

   Zone of pain: A=0, I=0 (concrete and stable) -> D=1
   Zone of uselessness: A=1, I=1 (abstract and unstable) -> D=1"
  [abstractness-val instability-val]
  (Math/abs (+ abstractness-val instability-val -1.0)))

(defn brick-metrics
  "Calculate all metrics for a single brick.
   Returns a map with :brick-name, :brick-type, :ca, :ce, :instability, :abstractness, :distance,
   plus :abstract-ns and :total-ns counts."
  [workspace-root brick-type brick-name graph inverted-graph external-requires]
  (let [ca (graph/afferent-coupling inverted-graph brick-name)
        ce (graph/efferent-coupling graph brick-name)
        i (instability ca ce)
        abs-data (graph/brick-abstractness-data workspace-root brick-type brick-name external-requires)
        a (:abstractness abs-data)
        d (distance a i)]
    {:brick-name brick-name
     :brick-type brick-type
     :ca ca
     :ce ce
     :instability i
     :abstractness a
     :distance d
     :abstract-ns (:abstract-ns abs-data)
     :total-ns (:total-ns abs-data)}))

(defn all-metrics
  "Calculate metrics for all components in a workspace.
   Returns a sequence of metric maps, one per component.
   Excludes bases - they are entry points whose consumers are outside the system."
  [workspace-root]
  (let [graph (graph/build-dependency-graph workspace-root)
        inverted (graph/invert-graph graph)
        external-requires (graph/collect-all-external-requires workspace-root)
        components (or (ws/find-components workspace-root) #{})]
    (for [comp components]
      (brick-metrics workspace-root :component comp graph inverted external-requires))))

;; ============================================================
;; Package-based metrics (bottom-up discovery approach)
;; ============================================================

(defn entry-point?
  "Returns true if this package is an entry point.
   For Polylith components: Ca=0 from other components, but used by at least one base.
   For polylith-like packages: Ca=0 (no internal dependents).
   Entry points are meant to be consumed externally, so A/D metrics are not meaningful."
  [pkg-type ca base-dependents]
  (case pkg-type
    ;; Polylith component: entry point if no component depends on it, but a base does
    :polylith-component (and (zero? ca) (seq base-dependents))
    ;; Polylith-like: entry point if nothing depends on it internally
    :polylith-like-package (zero? ca)
    ;; Bases are always entry points by definition
    :polylith-base true
    ;; Default: not an entry point
    false))

(defn package-metrics
  "Calculate all metrics for a single package.
   Returns a map with :name, :type, :ca, :ce, :instability, and optionally :abstractness, :distance.
   For :clojure-package type, A/D are nil (no interface detection).
   For entry points, A/D are nil (no internal dependents to measure against).

   base-dependents: set of base names that depend on this package (for entry-point detection)"
  [root-dir package dep-graph inverted-graph external-requires ns-to-pkg base-names]
  (let [pkg-name (:name package)
        pkg-type (:type package)
        ;; Internal dependents = all dependents minus bases
        internal-deps (graph/internal-dependents inverted-graph pkg-name base-names)
        ;; Base dependents needed for entry-point detection in Polylith
        all-dependents (graph/direct-dependents inverted-graph pkg-name)
        base-dependents (clojure.set/intersection all-dependents base-names)
        ;; Ca counts only internal dependents
        ca (count internal-deps)
        ce (graph/efferent-coupling dep-graph pkg-name)
        i (instability ca ce)
        abs-data (graph/package-abstractness-data root-dir package external-requires ns-to-pkg)
        is-entry-point? (entry-point? pkg-type ca base-dependents)]
    (cond
      ;; Entry point - A/D not meaningful
      is-entry-point?
      {:name pkg-name
       :type pkg-type
       :ca ca
       :ce ce
       :instability i
       :abstractness nil
       :distance nil
       :abstract-ns nil
       :total-ns nil
       :entry-point? true}

      ;; Has interface detection - include A/D
      abs-data
      (let [a (:abstractness abs-data)
            d (distance a i)]
        {:name pkg-name
         :type pkg-type
         :ca ca
         :ce ce
         :instability i
         :abstractness a
         :distance d
         :abstract-ns (:abstract-ns abs-data)
         :total-ns (:total-ns abs-data)
         :impl-ns-details (:impl-ns-details abs-data)
         :interface-ns-details (:interface-ns-details abs-data)
         :entry-point? false})

      ;; No interface detection - skip A/D
      :else
      {:name pkg-name
       :type pkg-type
       :ca ca
       :ce ce
       :instability i
       :abstractness nil
       :distance nil
       :abstract-ns nil
       :total-ns nil
       :entry-point? false})))

(defn all-package-metrics
  "Calculate metrics for all discovered packages.
   Returns a sequence of metric maps, one per package.

   Uses ALL packages (including Clojure directories) for dependency graph,
   so Ca/Ce/I/A metrics reflect the full dependency landscape.
   But only reports on polylith/polylith-like packages.

   Clojure directories accessing a package count as implementation access
   for abstractness calculation (they are implementation by definition)."
  [root-dir]
  (let [all-packages (discovery/discover-packages root-dir)
        ;; Use all packages (except interfaces) for the dependency graph
        graph-packages (remove #(= :polylith-like-interface (:type %)) all-packages)
        ;; Only report on polylith components and polylith-like packages (not bases, interfaces, or clojure-packages)
        reportable-packages (remove #(#{:clojure-package :polylith-like-interface :polylith-base} (:type %)) all-packages)
        ;; Identify base names for entry-point detection
        base-names (->> all-packages
                        (filter #(= :polylith-base (:type %)))
                        (map :name)
                        (into #{}))
        dep-graph (graph/build-package-dependency-graph root-dir graph-packages)
        inverted (graph/invert-graph dep-graph)
        external-requires (graph/collect-package-external-requires root-dir graph-packages)
        ns-to-pkg (graph/build-ns-to-package-map root-dir graph-packages)]
    (for [pkg reportable-packages]
      (package-metrics root-dir pkg dep-graph inverted external-requires ns-to-pkg base-names))))

(defn codebase-health
  "Calculate overall codebase health metrics.
   Returns a map with :package-count, :mean-distance, :max-distance, :min-distance.
   Only includes packages with distance values (excludes :clojure-package types)."
  [metrics]
  (let [;; Filter out packages without distance (clojure-packages)
        distances (keep :distance metrics)
        count-with-distance (count distances)
        count-packages (count metrics)]
    {:package-count count-packages
     :packages-with-distance count-with-distance
     :mean-distance (if (zero? count-with-distance)
                      0.0
                      (/ (reduce + distances) count-with-distance))
     :max-distance (if (empty? distances) 0.0 (apply max distances))
     :min-distance (if (empty? distances) 0.0 (apply min distances))}))

(defn problematic-bricks
  "Find bricks with high distance from main sequence.
   Default threshold is 0.5."
  ([metrics] (problematic-bricks metrics 0.5))
  ([metrics threshold]
   (->> metrics
        (filter #(> (:distance %) threshold))
        (sort-by :distance >))))

(defn stable-abstractions
  "Find abstract components that are stable (ideal).
   These are components with high abstractness and low instability."
  [metrics]
  (->> metrics
       (filter #(and (> (:abstractness %) 0.5)
                     (< (:instability %) 0.5)))))

(defn unstable-concretions
  "Find concrete components that are unstable (ideal).
   These are components with low abstractness and high instability."
  [metrics]
  (->> metrics
       (filter #(and (< (:abstractness %) 0.5)
                     (> (:instability %) 0.5)))))

(defn zone-of-pain
  "Find components in the 'zone of pain' - concrete and stable.
   These are hard to change because many things depend on them,
   but they have lots of implementation details."
  [metrics]
  (->> metrics
       (filter #(and (< (:abstractness %) 0.3)
                     (< (:instability %) 0.3)))))

(defn zone-of-uselessness
  "Find components in the 'zone of uselessness' - abstract and unstable.
   These have lots of abstraction but nothing depends on them."
  [metrics]
  (->> metrics
       (filter #(and (> (:abstractness %) 0.7)
                     (> (:instability %) 0.7)))))
