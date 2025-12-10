(ns poly-metrics.workspace
  "Polylith workspace discovery - identifying components, bases, and their structure."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [poly-metrics.parse :as parse]))

(defn read-workspace-config
  "Read and parse workspace.edn from a Polylith workspace root.
   Returns nil if the file doesn't exist."
  [workspace-root]
  (let [ws-file (io/file workspace-root "workspace.edn")]
    (when (.exists ws-file)
      (edn/read-string (slurp ws-file)))))

(defn polylith-workspace?
  "Returns true if the directory is a Polylith workspace (has workspace.edn)."
  [dir]
  (.exists (io/file dir "workspace.edn")))

(defn find-components
  "Find all component names in a Polylith workspace.
   Returns a set of component name strings."
  [workspace-root]
  (let [components-dir (io/file workspace-root "components")]
    (when (.exists components-dir)
      (->> (.listFiles components-dir)
           (filter #(.isDirectory %))
           (filter #(.exists (io/file % "src")))
           (map #(.getName %))
           (into #{})))))

(defn find-bases
  "Find all base names in a Polylith workspace.
   Returns a set of base name strings."
  [workspace-root]
  (let [bases-dir (io/file workspace-root "bases")]
    (when (.exists bases-dir)
      (->> (.listFiles bases-dir)
           (filter #(.isDirectory %))
           (filter #(.exists (io/file % "src")))
           (map #(.getName %))
           (into #{})))))

(defn find-bricks
  "Find all bricks (components + bases) in a Polylith workspace.
   Returns a set of brick name strings."
  [workspace-root]
  (into (or (find-components workspace-root) #{})
        (or (find-bases workspace-root) #{})))

(defn brick-paths
  "Get the standard paths for a brick (component or base).
   brick-type should be :component or :base."
  [workspace-root brick-type brick-name]
  (let [type-dir (case brick-type
                   :component "components"
                   :base "bases")
        base (io/file workspace-root type-dir brick-name)]
    {:src-dir (io/file base "src")
     :test-dir (io/file base "test")
     :resources-dir (io/file base "resources")}))

(defn component-paths
  "Get the standard paths for a component."
  [workspace-root component-name]
  (brick-paths workspace-root :component component-name))

(defn base-paths
  "Get the standard paths for a base."
  [workspace-root base-name]
  (brick-paths workspace-root :base base-name))

(defn interface-ns?
  "Returns true if the namespace symbol represents an interface namespace.
   Interface namespaces end with '.interface' or contain '.interface.'."
  [ns-sym]
  (when ns-sym
    (let [ns-str (str ns-sym)]
      (or (str/ends-with? ns-str ".interface")
          (str/includes? ns-str ".interface.")))))

(defn interface-ns->component
  "Extract the component name from an interface namespace.
   E.g., 'myapp.user.interface -> \"user\"
         'myapp.user.interface.admin -> \"user\"
         'polylith.clj.core.util.interface -> \"util\"

   top-namespace is the workspace's :top-namespace config (e.g., \"polylith.clj.core\")."
  [ns-sym top-namespace]
  (when (interface-ns? ns-sym)
    (let [ns-str (str ns-sym)
          ;; Remove top-namespace prefix if present
          prefix (if top-namespace (str top-namespace ".") "")
          without-prefix (if (and (seq prefix) (str/starts-with? ns-str prefix))
                           (subs ns-str (count prefix))
                           ns-str)
          ;; Split and find the part before "interface"
          parts (str/split without-prefix #"\.")
          interface-idx (.indexOf parts "interface")]
      (when (pos? interface-idx)
        (get parts (dec interface-idx))))))

(defn brick-namespaces
  "Get all namespaces defined in a brick's src directory.
   Returns a sequence of namespace symbols."
  [workspace-root brick-type brick-name]
  (let [{:keys [src-dir]} (brick-paths workspace-root brick-type brick-name)]
    (when (.exists src-dir)
      (->> (parse/find-clj-files src-dir)
           (keep parse/read-ns-decl)
           (map second)))))

(defn component-namespaces
  "Get all namespaces defined in a component's src directory."
  [workspace-root component-name]
  (brick-namespaces workspace-root :component component-name))

(defn base-namespaces
  "Get all namespaces defined in a base's src directory."
  [workspace-root base-name]
  (brick-namespaces workspace-root :base base-name))

(defn brick-interface-namespaces
  "Get only the interface namespaces for a brick."
  [workspace-root brick-type brick-name]
  (filter interface-ns? (brick-namespaces workspace-root brick-type brick-name)))

(defn brick-implementation-namespaces
  "Get only the implementation (non-interface) namespaces for a brick."
  [workspace-root brick-type brick-name]
  (remove interface-ns? (brick-namespaces workspace-root brick-type brick-name)))

;; Phase 3: Namespace to Component Mapping

(defn build-ns-to-brick-map
  "Build a map from namespace symbol to {:brick-name :brick-type}.
   Scans all components and bases in the workspace."
  [workspace-root]
  (let [components (find-components workspace-root)
        bases (find-bases workspace-root)]
    (merge
     ;; Map component namespaces
     (into {}
           (for [comp components
                 ns-sym (brick-namespaces workspace-root :component comp)]
             [ns-sym {:brick-name comp :brick-type :component}]))
     ;; Map base namespaces
     (into {}
           (for [base bases
                 ns-sym (brick-namespaces workspace-root :base base)]
             [ns-sym {:brick-name base :brick-type :base}])))))

(defn dep-ns->brick
  "Given a dependency namespace (usually an interface), determine which brick it belongs to.

   First tries direct lookup in ns-to-brick-map.
   If not found and it's an interface ns, extracts component name from the namespace pattern.

   Returns brick name string, or nil if not a workspace dependency."
  [dep-ns ns-to-brick-map top-namespace]
  (if-let [brick-info (get ns-to-brick-map dep-ns)]
    (:brick-name brick-info)
    ;; Not in map - try to infer from interface pattern
    (when (interface-ns? dep-ns)
      (interface-ns->component dep-ns top-namespace))))

(defn resolve-dependencies
  "Given a set of dependency namespaces, resolve them to brick names.
   Filters out external dependencies (not part of workspace) and self-references.

   Returns a set of brick name strings."
  [dep-namespaces ns-to-brick-map top-namespace excluding-brick]
  (->> dep-namespaces
       (keep #(dep-ns->brick % ns-to-brick-map top-namespace))
       (remove #(= % excluding-brick))
       (into #{})))
