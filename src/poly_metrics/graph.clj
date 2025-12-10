(ns poly-metrics.graph
  "Dependency graph building and analysis for Polylith workspaces."
  (:require [poly-metrics.workspace :as ws]
            [poly-metrics.parse :as parse]
            [clojure.tools.namespace.parse :as tns-parse]))

(defn brick-dependencies
  "Find the brick dependencies for a single brick.
   Only looks at implementation namespaces (not interface).
   Returns a set of brick name strings that this brick depends on."
  [workspace-root brick-type brick-name ns-to-brick-map top-namespace]
  (let [{:keys [src-dir]} (ws/brick-paths workspace-root brick-type brick-name)
        impl-files (parse/find-clj-files src-dir)]
    (let [all-deps (->> impl-files
                        (mapcat (fn [f]
                                  (when-let [ns-decl (parse/read-ns-decl f)]
                                    (let [ns-name (second ns-decl)]
                                      ;; Only process implementation namespaces
                                      (when-not (ws/interface-ns? ns-name)
                                        (tns-parse/deps-from-ns-decl ns-decl))))))
                        (into #{}))]
      (ws/resolve-dependencies all-deps ns-to-brick-map top-namespace brick-name))))

(defn build-dependency-graph
  "Build the complete dependency graph for a Polylith workspace.
   Returns a map of {brick-name #{dependent-brick-names}}.

   Includes all bricks (components and bases), even those with no dependencies."
  [workspace-root]
  (let [config (ws/read-workspace-config workspace-root)
        top-ns (:top-namespace config)
        components (or (ws/find-components workspace-root) #{})
        bases (or (ws/find-bases workspace-root) #{})
        ns-to-brick (ws/build-ns-to-brick-map workspace-root)]
    (merge
     ;; Components
     (into {}
           (for [comp components]
             [comp (brick-dependencies workspace-root :component comp ns-to-brick top-ns)]))
     ;; Bases
     (into {}
           (for [base bases]
             [base (brick-dependencies workspace-root :base base ns-to-brick top-ns)])))))

(defn invert-graph
  "Invert a dependency graph.
   Input: {brick #{bricks-it-depends-on}}
   Output: {brick #{bricks-that-depend-on-it}}

   Note: Bricks with no dependents won't appear as keys in the output."
  [graph]
  (reduce
   (fn [acc [brick deps]]
     (reduce
      (fn [acc dep]
        (update acc dep (fnil conj #{}) brick))
      acc
      deps))
   {}
   graph))

(defn all-bricks
  "Get all brick names from a dependency graph."
  [graph]
  (into #{} (keys graph)))

(defn efferent-coupling
  "Count of bricks this brick depends on (outgoing dependencies).
   Ce in JDepend terminology."
  [graph brick]
  (count (get graph brick #{})))

(defn afferent-coupling
  "Count of bricks that depend on this brick (incoming dependencies).
   Ca in JDepend terminology."
  [inverted-graph brick]
  (count (get inverted-graph brick #{})))

(defn direct-dependencies
  "Get the set of bricks this brick directly depends on."
  [graph brick]
  (get graph brick #{}))

(defn direct-dependents
  "Get the set of bricks that directly depend on this brick."
  [inverted-graph brick]
  (get inverted-graph brick #{}))

;; Phase 6: Cycle Detection

(defn transitive-deps
  "Find all transitive dependencies of a brick (including itself).
   Returns a set of brick names."
  [graph brick]
  (loop [frontier #{brick}
         visited #{}]
    (if (empty? frontier)
      visited
      (let [current (first frontier)
            deps (get graph current #{})]
        (recur (into (disj frontier current)
                     (remove visited deps))
               (conj visited current))))))

(defn has-cycle-from?
  "Check if starting from 'brick' we can reach 'brick' again through dependencies."
  [graph brick]
  (let [deps (get graph brick #{})]
    (when (seq deps)
      (let [reachable (transitive-deps graph brick)]
        ;; If any direct dependency can reach back to brick, there's a cycle
        (some #(contains? (transitive-deps graph %) brick) deps)))))

(defn find-cycle-from
  "Find a cycle starting from 'brick' if one exists.
   Returns a vector representing the cycle path, or nil if no cycle."
  [graph brick]
  (letfn [(dfs [current path visited]
            (cond
              ;; Found cycle back to start
              (and (= current brick) (> (count path) 1))
              (conj path brick)

              ;; Already visited in this path (but not the start)
              (contains? visited current)
              nil

              ;; Continue DFS
              :else
              (let [deps (get graph current #{})
                    new-visited (conj visited current)
                    new-path (conj path current)]
                (some #(dfs % new-path new-visited) deps))))]
    ;; Start DFS from each direct dependency
    (let [deps (get graph brick #{})]
      (some #(dfs % [brick] #{}) deps))))

(defn find-all-cycles
  "Find all unique cycles in the graph.
   Returns a vector of cycle paths, where each path is a vector of brick names.
   Each cycle is reported once (normalized to start with the lexicographically smallest brick)."
  [graph]
  (let [bricks (keys graph)
        raw-cycles (keep #(find-cycle-from graph %) bricks)]
    ;; Normalize and deduplicate cycles
    (->> raw-cycles
         (map (fn [cycle]
                ;; Remove the duplicate end element and normalize
                (let [path (vec (butlast cycle))
                      min-brick (first (sort path))
                      idx (.indexOf path min-brick)
                      normalized (vec (concat (drop idx path) (take idx path)))]
                  normalized)))
         (distinct)
         (vec))))

(defn acyclic?
  "Returns true if the graph has no cycles."
  [graph]
  (empty? (find-all-cycles graph)))

(defn cyclic-bricks
  "Find all bricks that participate in at least one cycle."
  [graph]
  (->> (find-all-cycles graph)
       (mapcat identity)
       (into #{})))

;; Namespace-level dependency analysis for abstractness calculation

(defn collect-all-external-requires
  "Collect all namespace dependencies from all bricks.
   Returns a map of {required-ns #{requiring-brick-names}}.
   Only includes requires that cross brick boundaries."
  [workspace-root]
  (let [config (ws/read-workspace-config workspace-root)
        top-ns (:top-namespace config)
        components (or (ws/find-components workspace-root) #{})
        bases (or (ws/find-bases workspace-root) #{})
        ns-to-brick (ws/build-ns-to-brick-map workspace-root)

        collect-for-brick (fn [brick-type brick-name]
                            (let [{:keys [src-dir]} (ws/brick-paths workspace-root brick-type brick-name)
                                  files (parse/find-clj-files src-dir)]
                              (->> files
                                   (mapcat (fn [f]
                                             (when-let [ns-decl (parse/read-ns-decl f)]
                                               (tns-parse/deps-from-ns-decl ns-decl))))
                                   ;; Keep only workspace namespaces, not external libs
                                   (filter #(get ns-to-brick %))
                                   ;; Tag each with the requiring brick
                                   (map (fn [req-ns] [req-ns brick-name])))))]

    ;; Collect all requires and group by required namespace
    (reduce (fn [acc [req-ns requiring-brick]]
              (let [required-brick (:brick-name (get ns-to-brick req-ns))]
                ;; Only count if it crosses brick boundary
                (if (not= required-brick requiring-brick)
                  (update acc req-ns (fnil conj #{}) requiring-brick)
                  acc)))
            {}
            (concat
             (mapcat #(collect-for-brick :component %) components)
             (mapcat #(collect-for-brick :base %) bases)))))

(defn externally-visible-namespaces
  "For a brick, return the set of its namespaces that are required by other bricks."
  [workspace-root brick-name external-requires]
  (let [ns-to-brick (ws/build-ns-to-brick-map workspace-root)]
    (->> external-requires
         (filter (fn [[req-ns _]]
                   (= brick-name (:brick-name (get ns-to-brick req-ns)))))
         (map first)
         (into #{}))))

(defn brick-external-interface-ns
  "Count interface namespaces of a brick that are used externally."
  [workspace-root brick-name external-requires]
  (let [visible-ns (externally-visible-namespaces workspace-root brick-name external-requires)]
    (count (filter ws/interface-ns? visible-ns))))

(defn brick-external-impl-ns
  "Count implementation namespaces of a brick that are used externally (leaky abstraction)."
  [workspace-root brick-name external-requires]
  (let [visible-ns (externally-visible-namespaces workspace-root brick-name external-requires)]
    (count (remove ws/interface-ns? visible-ns))))

(defn brick-abstractness-data
  "Calculate abstractness data for a brick.
   Returns {:interface-ns count, :leaky-impl-ns count, :abstractness ratio}."
  [workspace-root brick-name external-requires]
  (let [ext-interface (brick-external-interface-ns workspace-root brick-name external-requires)
        ext-impl (brick-external-impl-ns workspace-root brick-name external-requires)
        total-external (+ ext-interface ext-impl)]
    {:interface-ns ext-interface
     :leaky-impl-ns ext-impl
     :abstractness (if (zero? total-external)
                     1.0  ;; No external visibility = perfectly abstracted (or unused)
                     (double (/ ext-interface total-external)))}))
