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
