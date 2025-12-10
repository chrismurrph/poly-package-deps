(ns poly-metrics.metrics
  "JDepend-style metrics calculation for Polylith components."
  (:require [poly-metrics.workspace :as ws]
            [poly-metrics.graph :as graph]))

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
  "Calculate abstractness metric.
   New formula: A = interface-ns / (interface-ns + leaky-impl-ns)
   Only counts namespaces that are externally visible.
   Ranges from 0 (all external access is to impl) to 1 (all external access is via interface)."
  [interface-ns-count leaky-impl-ns-count]
  (let [total (+ interface-ns-count leaky-impl-ns-count)]
    (if (zero? total)
      1.0  ;; No external visibility = perfectly abstracted (or unused)
      (double (/ interface-ns-count total)))))

(defn distance
  "Calculate distance: D = (1 - A) * (1 - I).
   Ranges from 0 (ideal) to 1 (worst).

   This formula penalizes components that are both:
   - Leaky (low A): external code accesses implementation directly
   - Stable (low I): many other components depend on this one

   Entry-level components (high I) get low distance since there's
   nothing to leak to. Leaky stable components are the real problem."
  [abstractness-val instability-val]
  (* (- 1.0 abstractness-val) (- 1.0 instability-val)))

(defn brick-metrics
  "Calculate all metrics for a single brick.
   Returns a map with :brick-name, :brick-type, :ca, :ce, :instability, :abstractness, :distance,
   plus :interface-ns and :leaky-impl-ns counts."
  [workspace-root brick-type brick-name graph inverted-graph external-requires]
  (let [ca (graph/afferent-coupling inverted-graph brick-name)
        ce (graph/efferent-coupling graph brick-name)
        i (instability ca ce)
        abs-data (graph/brick-abstractness-data workspace-root brick-name external-requires)
        a (:abstractness abs-data)
        d (distance a i)]
    {:brick-name brick-name
     :brick-type brick-type
     :ca ca
     :ce ce
     :instability i
     :abstractness a
     :distance d
     :interface-ns (:interface-ns abs-data)
     :leaky-impl-ns (:leaky-impl-ns abs-data)}))

(defn all-metrics
  "Calculate metrics for all components in a workspace.
   Returns a sequence of metric maps, one per component.
   Excludes bases - they are entry points whose consumers are outside the system,
   so abstractness cannot be meaningfully measured for them."
  [workspace-root]
  (let [graph (graph/build-dependency-graph workspace-root)
        inverted (graph/invert-graph graph)
        external-requires (graph/collect-all-external-requires workspace-root)
        components (or (ws/find-components workspace-root) #{})]
    (for [comp components]
      (brick-metrics workspace-root :component comp graph inverted external-requires))))

(defn codebase-health
  "Calculate overall codebase health metrics.
   Returns a map with :brick-count, :mean-distance, :max-distance, :min-distance"
  [metrics]
  (let [distances (map :distance metrics)
        count-bricks (count metrics)]
    {:brick-count count-bricks
     :mean-distance (if (zero? count-bricks)
                      0.0
                      (/ (reduce + distances) count-bricks))
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
