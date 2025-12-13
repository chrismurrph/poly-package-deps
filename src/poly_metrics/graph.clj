(ns poly-metrics.graph
  "Dependency graph building and analysis for Clojure packages."
  (:require [poly-metrics.workspace :as ws]
            [poly-metrics.parse :as parse]
            [poly-metrics.discovery :as discovery]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

;; ============================================================
;; Package-based dependency graph (bottom-up discovery approach)
;; ============================================================

(defn package-namespaces
  "Get all namespaces defined in a package's files.
   Returns a sequence of namespace symbols."
  [root-dir package]
  (->> (:files package)
       (map #(io/file root-dir %))
       (keep parse/read-ns-decl)
       (map second)))

(defn build-ns-to-package-map
  "Build a map from namespace symbol to package name.
   Works from discovered packages."
  [root-dir packages]
  (into {}
        (for [pkg packages
              ns-sym (package-namespaces root-dir pkg)]
          [ns-sym (:name pkg)])))

(defn package-dependencies
  "Find packages this package depends on.
   Returns a set of package names."
  [root-dir package ns-to-package-map]
  (let [pkg-name (:name package)
        all-deps (->> (:files package)
                      (map #(io/file root-dir %))
                      (mapcat (fn [f]
                                (when-let [ns-decl (parse/read-ns-decl f)]
                                  (tns-parse/deps-from-ns-decl ns-decl))))
                      (into #{}))]
    ;; Resolve to package names, excluding self-references and external deps
    (->> all-deps
         (keep #(get ns-to-package-map %))
         (remove #(= % pkg-name))
         (into #{}))))

(defn build-package-dependency-graph
  "Build dependency graph from discovered packages.
   Returns a map of {package-name #{dependent-package-names}}."
  [root-dir packages]
  (let [ns-to-pkg (build-ns-to-package-map root-dir packages)]
    (into {}
          (for [pkg packages]
            [(:name pkg) (package-dependencies root-dir pkg ns-to-pkg)]))))

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

(defn internal-dependents
  "Return the set of internal dependents for a package.
   Internal dependents are packages that depend on this one, excluding bases.
   For Polylith: excludes bases (they are external consumers).
   For polylith-like: no bases exist, so returns all dependents."
  [inverted-graph pkg-name base-names]
  (let [all-deps (direct-dependents inverted-graph pkg-name)]
    (clojure.set/difference all-deps base-names)))

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
  "Collect all namespace dependencies from components only.
   Returns a map of {required-ns #{requiring-component-names}}.
   Only includes requires that cross brick boundaries.
   Excludes bases as requirers - we only count component-to-component access
   for abstractness calculation (bases are entry points, outside the system)."
  [workspace-root]
  (let [components (or (ws/find-components workspace-root) #{})
        ns-to-brick (ws/build-ns-to-brick-map workspace-root)

        collect-for-component (fn [comp-name]
                                (let [{:keys [src-dir]} (ws/brick-paths workspace-root :component comp-name)
                                      files (parse/find-clj-files src-dir)]
                                  (->> files
                                       (mapcat (fn [f]
                                                 (when-let [ns-decl (parse/read-ns-decl f)]
                                                   (tns-parse/deps-from-ns-decl ns-decl))))
                                       ;; Keep only workspace namespaces, not external libs
                                       (filter #(get ns-to-brick %))
                                       ;; Tag each with the requiring component
                                       (map (fn [req-ns] [req-ns comp-name])))))]

    ;; Collect all requires and group by required namespace
    ;; Only from components (not bases) - bases are entry points
    (reduce (fn [acc [req-ns requiring-comp]]
              (let [required-brick (:brick-name (get ns-to-brick req-ns))]
                ;; Only count if it crosses brick boundary
                (if (not= required-brick requiring-comp)
                  (update acc req-ns (fnil conj #{}) requiring-comp)
                  acc)))
            {}
            (mapcat collect-for-component components))))

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
  "Calculate abstractness data for a brick based on external access patterns.
   A = (interface-ns accessed externally) / (total-ns accessed externally)
   This measures whether other components use the interface or bypass it.
   Returns {:abstract-ns count, :total-ns count, :abstractness ratio}."
  [workspace-root brick-type brick-name external-requires]
  (let [visible-ns (externally-visible-namespaces workspace-root brick-name external-requires)
        abstract-ns (count (filter ws/interface-ns? visible-ns))
        total-ns (count visible-ns)]
    {:abstract-ns abstract-ns
     :total-ns total-ns
     :abstractness (if (zero? total-ns)
                     1.0  ;; No external access = fully abstract (nothing to leak)
                     (double (/ abstract-ns total-ns)))}))

;; ============================================================
;; Package-based abstractness calculation
;; ============================================================

(defn interface-ns-for-package?
  "Determine if a namespace is an 'interface' for the given package type.
   - Polylith component/base: namespace contains '.interface'
   - Polylith-like interface: ALL namespaces are interfaces (the whole package is an interface)
   - Polylith-like package: namespace contains '.interface' (same as Polylith)
   - Clojure package: no interface detection (returns false)"
  [ns-sym package-type]
  (case package-type
    :polylith-component (ws/interface-ns? ns-sym)
    :polylith-base (ws/interface-ns? ns-sym)
    :polylith-like-interface true  ;; Everything in interfaces/ is abstract
    :polylith-like-package (ws/interface-ns? ns-sym)
    :clojure-package false))  ;; No interface detection

(defn collect-package-external-requires
  "Collect all namespace dependencies across packages.
   Returns a map of {required-ns #{requiring-package-names}}.
   Only includes requires that cross package boundaries."
  [root-dir packages]
  (let [ns-to-pkg (build-ns-to-package-map root-dir packages)

        collect-for-package (fn [pkg]
                              (let [pkg-name (:name pkg)]
                                (->> (:files pkg)
                                     (map #(io/file root-dir %))
                                     (mapcat (fn [f]
                                               (when-let [ns-decl (parse/read-ns-decl f)]
                                                 (tns-parse/deps-from-ns-decl ns-decl))))
                                     ;; Keep only known package namespaces
                                     (filter #(get ns-to-pkg %))
                                     ;; Tag each with the requiring package
                                     (map (fn [req-ns] [req-ns pkg-name])))))]

    ;; Collect all requires, only count cross-package dependencies
    (reduce (fn [acc [req-ns requiring-pkg]]
              (let [required-pkg (get ns-to-pkg req-ns)]
                (if (not= required-pkg requiring-pkg)
                  (update acc req-ns (fnil conj #{}) requiring-pkg)
                  acc)))
            {}
            (mapcat collect-for-package packages))))

(defn package-externally-visible-namespaces
  "For a package, return the set of its namespaces that are required by other packages."
  [root-dir package external-requires ns-to-pkg]
  (let [pkg-name (:name package)]
    (->> external-requires
         (filter (fn [[req-ns _]]
                   (= pkg-name (get ns-to-pkg req-ns))))
         (map first)
         (into #{}))))

(defn package-abstractness-data
  "Calculate abstractness data for a package.
   For types with interface detection: A = interface-ns / total-ns (externally accessed)
   For :clojure-package: returns nil (no interface detection)."
  [root-dir package external-requires ns-to-pkg]
  (let [pkg-type (:type package)]
    (if (= pkg-type :clojure-package)
      ;; No interface detection for plain Clojure packages
      nil
      ;; Calculate abstractness for Polylith/Polylith-like types
      (let [visible-ns (package-externally-visible-namespaces root-dir package external-requires ns-to-pkg)
            abstract-ns (count (filter #(interface-ns-for-package? % pkg-type) visible-ns))
            total-ns (count visible-ns)]
        {:abstract-ns abstract-ns
         :total-ns total-ns
         :abstractness (if (zero? total-ns)
                         1.0  ;; No external access = fully abstract
                         (double (/ abstract-ns total-ns)))}))))
