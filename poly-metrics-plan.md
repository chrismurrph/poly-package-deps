# poly-metrics: JDepend-style Metrics for Polylith Clojure

## Overview

A tool that calculates maintainability metrics for Polylith Clojure codebases, inspired by JDepend's approach but working at the source level. Produces a single "distance from main sequence" number per component, and an overall codebase health score.

**This tool is specifically for Polylith workspaces.** Polylith provides the well-defined package boundaries (components) that make JDepend-style analysis meaningful. Arbitrary Clojure codebases lack these explicit boundaries.

## Core Concepts

### JDepend Metrics Refresher

- **Ca (Afferent Coupling)**: Number of packages that depend on this package (incoming)
- **Ce (Efferent Coupling)**: Number of packages this package depends on (outgoing)
- **I (Instability)**: Ce / (Ca + Ce) — ranges 0 (stable) to 1 (unstable)
- **A (Abstractness)**: Ratio of abstract to total classes in package
- **D (Distance)**: |A + I - 1| — distance from the "main sequence" ideal

### Polylith Mapping

| JDepend Concept | Polylith Equivalent |
|-----------------|---------------------|
| Package | Component |
| Package's classes | Component's implementation namespaces (`components/foo/src/**`) |
| Abstract classes/interfaces | Interface namespaces (`interface.clj`, `interface/*.clj`) |
| Package dependency | Implementation namespace requiring another component's interface |

### How Dependencies Are Counted

A component's **efferent coupling (Ce)** comes from its implementation code only:

```
components/cart/src/myapp/cart/
├── core.clj        # requires [myapp.product.interface :as product]
│                   #          [myapp.pricing.interface :as pricing]  
└── validation.clj  # requires [myapp.customer.interface :as customer]
```

This component has Ce = 3 (depends on product, pricing, customer).

A component's **afferent coupling (Ca)** is how many other components' implementations require its interface.

Interface namespaces are not counted for dependency purposes — they're the abstraction boundary, not the dependent code.

### Abstractness in Polylith Context

Abstractness measures how much of a component is "interface surface" vs "implementation bulk":

```
A = (interface namespace count) / (total namespace count in component)
```

A utility component with a small interface and lots of implementation: low A (concrete).
A component that's mostly protocols/specs with minimal implementation: high A (abstract).

The ideal: concrete components should be unstable (easy to change), abstract components should be stable (hard to change). Distance measures deviation from this.

## Development Phases

### Phase 1: Project Setup & Namespace Parsing

**Goal**: Parse `:require` clauses from Clojure source files.

**REPL-driven steps**:

1. Create project:
   ```clojure
   ;; deps.edn
   {:paths ["src" "test"]
    :deps {org.clojure/clojure {:mvn/version "1.12.0"}
           org.clojure/tools.namespace {:mvn/version "1.5.0"}}
    :aliases {:test {:extra-paths ["test"]
                     :extra-deps {io.github.cognitect-labs/test-runner 
                                  {:git/tag "v0.5.1" :git/sha "dfb30dd"}}
                     :main-opts ["-m" "cognitect.test-runner"]}}}
   ```

2. Explore `tools.namespace` at REPL:
   ```clojure
   (require '[clojure.tools.namespace.parse :as parse])
   (require '[clojure.java.io :as io])
   
   ;; Read ns decl from a file
   (defn read-ns-decl [file]
     (with-open [rdr (java.io.PushbackReader. (io/reader file))]
       (parse/read-ns-decl rdr)))
   
   ;; Try it
   (read-ns-decl (io/file "src/poly_metrics/core.clj"))
   
   ;; Get dependencies from ns decl
   (parse/deps-from-ns-decl '(ns foo.core (:require [bar.interface :as bar])))
   ;; => #{bar.interface}
   ```

3. Walk directory for all .clj/.cljc files:
   ```clojure
   (defn find-clj-files [dir]
     (->> (file-seq (io/file dir))
          (filter #(.isFile %))
          (filter #(re-matches #".*\.cljc?$" (.getName %)))))
   ```

4. Combine into function that returns `{namespace #{dep1 dep2}}` map

**Tests to capture**:
- `read-ns-decl` handles `:require`, `:require-macros`, `:use`
- Handles various require forms: `[foo.bar]`, `[foo.bar :as fb]`, `[foo.bar :refer [x]]`
- Returns nil for files without ns form
- `find-clj-files` finds .clj and .cljc, ignores others

### Phase 2: Polylith Workspace Discovery

**Goal**: Identify components and their structure in a Polylith workspace.

**REPL-driven steps**:

1. Clone test workspaces:
   ```bash
   git clone https://github.com/polyfy/polylith /tmp/polylith-test
   ```

2. Read workspace.edn for configuration:
   ```clojure
   (defn read-workspace-config [workspace-root]
     (let [ws-file (io/file workspace-root "workspace.edn")]
       (when (.exists ws-file)
         (clojure.edn/read-string (slurp ws-file)))))
   
   ;; Key config: :top-namespace
   ;; e.g. "se.example" means interfaces are se.example.foo.interface
   (def ws (read-workspace-config "/tmp/polylith-test"))
   (:top-namespace ws)
   ```

3. Discover components:
   ```clojure
   (defn find-components [workspace-root]
     (let [components-dir (io/file workspace-root "components")]
       (when (.exists components-dir)
         (->> (.listFiles components-dir)
              (filter #(.isDirectory %))
              (filter #(.exists (io/file % "src")))
              (map #(.getName %))
              (into #{})))))
   
   ;; Try it
   (find-components "/tmp/polylith-test")
   ```

4. For each component, find implementation and interface dirs:
   ```clojure
   (defn component-paths [workspace-root component-name]
     (let [base (io/file workspace-root "components" component-name)]
       {:src-dir (io/file base "src")
        :test-dir (io/file base "test")
        :resources-dir (io/file base "resources")}))
   ```

5. Identify interface vs implementation namespaces:
   ```clojure
   (defn interface-ns? [ns-sym]
     (let [ns-str (str ns-sym)]
       (or (clojure.string/ends-with? ns-str ".interface")
           (clojure.string/includes? ns-str ".interface."))))
   
   ;; Test
   (interface-ns? 'myapp.user.interface)        ;; => true
   (interface-ns? 'myapp.user.interface.admin)  ;; => true  
   (interface-ns? 'myapp.user.core)             ;; => false
   ```

**Tests to capture**:
- Finds all components in polylith repo
- Handles workspaces with/without top-namespace config
- `interface-ns?` correctly identifies interface namespaces
- Handles bases (similar to components, under `bases/` directory)

### Phase 3: Map Namespaces to Components

**Goal**: Given a namespace like `myapp.user.interface`, determine it belongs to the `user` component.

**REPL-driven steps**:

1. Build namespace -> component mapping:
   ```clojure
   (defn build-ns-to-component-map [workspace-root]
     ;; Scan all components, collect their namespaces
     ;; Returns {ns-symbol component-name}
     (let [components (find-components workspace-root)]
       (into {}
         (for [comp components
               :let [src-dir (:src-dir (component-paths workspace-root comp))]
               file (find-clj-files src-dir)
               :let [ns-decl (read-ns-decl file)]
               :when ns-decl
               :let [ns-name (second ns-decl)]]
           [ns-name comp]))))
   ```

2. Resolve a dependency to its component:
   ```clojure
   (defn dep->component [dep-ns ns-to-component-map]
     ;; dep-ns is something like 'myapp.user.interface
     ;; Look it up, or infer from interface pattern
     (get ns-to-component-map dep-ns))
   ```

3. Handle the interface convention — when implementation requires `foo.interface`, that's a dep on component `foo`:
   ```clojure
   (defn interface-ns->component [ns-sym top-namespace]
     ;; 'myapp.user.interface -> "user"
     ;; 'myapp.user.interface.admin -> "user"
     (let [ns-str (str ns-sym)
           prefix (if top-namespace (str top-namespace ".") "")
           without-prefix (if (clojure.string/starts-with? ns-str prefix)
                            (subs ns-str (count prefix))
                            ns-str)]
       (first (clojure.string/split without-prefix #"\."))))
   ```

**Tests to capture**:
- Maps namespaces to correct components
- Handles top-namespace prefix correctly
- Interface namespace maps to owning component
- Returns nil for external dependencies (not part of workspace)

### Phase 4: Build Component Dependency Graph

**Goal**: Create graph of component -> #{dependent-components}.

**REPL-driven steps**:

1. For a single component, find its dependencies:
   ```clojure
   (defn component-dependencies [workspace-root component-name ns-to-component top-ns]
     ;; Only look at implementation namespaces (not interface)
     ;; Collect all requires that point to other components' interfaces
     (let [{:keys [src-dir]} (component-paths workspace-root component-name)
           impl-files (find-clj-files src-dir)]
       (->> impl-files
            (mapcat (fn [f]
                      (when-let [ns-decl (read-ns-decl f)]
                        (let [ns-name (second ns-decl)]
                          (when-not (interface-ns? ns-name)
                            (parse/deps-from-ns-decl ns-decl))))))
            (keep #(interface-ns->component % top-ns))
            (remove #(= % component-name))  ;; ignore self-references
            (into #{}))))
   ```

2. Build full graph:
   ```clojure
   (defn build-dependency-graph [workspace-root]
     (let [config (read-workspace-config workspace-root)
           top-ns (:top-namespace config)
           components (find-components workspace-root)
           ns-to-comp (build-ns-to-component-map workspace-root)]
       (into {}
         (for [comp components]
           [comp (component-dependencies workspace-root comp ns-to-comp top-ns)]))))
   
   ;; Try on polylith repo
   (def graph (build-dependency-graph "/tmp/polylith-test"))
   (clojure.pprint/pprint graph)
   ```

3. Invert graph for afferent coupling:
   ```clojure
   (defn invert-graph [graph]
     (reduce
       (fn [acc [component deps]]
         (reduce
           (fn [acc dep]
             (update acc dep (fnil conj #{}) component))
           acc
           deps))
       {}
       graph))
   
   ;; Test it
   (invert-graph {:a #{:b :c} :b #{:c}})
   ;; => {:b #{:a}, :c #{:a :b}}
   ```

**Tests to capture**:
- Graph includes all components (even those with no deps)
- Self-references filtered out
- Inverted graph correctly shows who depends on whom
- External deps (outside workspace) ignored

### Phase 5: Metrics Calculation

**Goal**: Calculate Ca, Ce, I, A, D for each component.

**REPL-driven steps**:

1. Basic coupling metrics:
   ```clojure
   (defn efferent-coupling [graph component]
     (count (get graph component #{})))
   
   (defn afferent-coupling [inverted-graph component]
     (count (get inverted-graph component #{})))
   
   (defn instability [ca ce]
     (if (zero? (+ ca ce))
       0.0  ;; Convention: isolated component is stable
       (double (/ ce (+ ca ce)))))
   ```

2. Abstractness — count interface vs implementation namespaces:
   ```clojure
   (defn component-abstractness [workspace-root component-name]
     (let [{:keys [src-dir]} (component-paths workspace-root component-name)
           all-ns (->> (find-clj-files src-dir)
                       (keep read-ns-decl)
                       (map second))
           total (count all-ns)
           interface-count (count (filter interface-ns? all-ns))]
       (if (zero? total)
         0.0
         (double (/ interface-count total)))))
   ```

3. Distance from main sequence:
   ```clojure
   (defn distance [abstractness instability]
     (Math/abs (double (+ abstractness instability -1.0))))
   ```

4. Assemble metrics for one component:
   ```clojure
   (defn component-metrics [workspace-root component graph inverted-graph]
     (let [ca (afferent-coupling inverted-graph component)
           ce (efferent-coupling graph component)
           i (instability ca ce)
           a (component-abstractness workspace-root component)
           d (distance a i)]
       {:component component
        :ca ca
        :ce ce
        :instability i
        :abstractness a
        :distance d}))
   ```

5. Test with hand-calculated examples at REPL

**Tests to capture**:
- I=0 when Ce=0 (no outgoing deps = stable)
- I=1 when Ca=0 (no incoming deps = unstable)
- D=0 for ideal cases: (A=1, I=0) and (A=0, I=1)
- D≈0.71 for worst case: (A=0, I=0) — concrete and stable, hard to change
- Handles components with no namespaces

### Phase 6: Cycle Detection

**Goal**: Detect circular dependencies between components.

**REPL-driven steps**:

1. DFS-based cycle detection:
   ```clojure
   (defn find-cycles [graph]
     (let [nodes (keys graph)]
       (loop [to-visit nodes
              visited #{}
              path []
              cycles []]
         ;; Standard DFS cycle detection
         ;; Return list of cycles found
         ...)))
   ```

2. Simpler approach — check if any component appears in its own transitive deps:
   ```clojure
   (defn transitive-deps [graph component]
     (loop [frontier #{component}
            visited #{}]
       (if (empty? frontier)
         visited
         (let [current (first frontier)
               deps (get graph current #{})]
           (recur (into (disj frontier current) 
                        (remove visited deps))
                  (conj visited current))))))
   
   (defn has-cycle? [graph component]
     (let [deps (get graph component #{})
           transitive (transitive-deps graph component)]
       (contains? transitive component)))
   ```

3. Test on constructed graphs with known cycles

**Tests to capture**:
- Detects A->B->A
- Detects A->B->C->A
- No false positives on acyclic graphs
- Handles disconnected subgraphs

### Phase 7: Reporting

**Goal**: Produce useful output.

**REPL-driven steps**:

1. Gather all metrics:
   ```clojure
   (defn all-metrics [workspace-root]
     (let [graph (build-dependency-graph workspace-root)
           inverted (invert-graph graph)
           components (find-components workspace-root)]
       (mapv #(component-metrics workspace-root % graph inverted)
             components)))
   ```

2. Codebase health summary:
   ```clojure
   (defn codebase-health [metrics cycles]
     (let [distances (map :distance metrics)]
       {:component-count (count metrics)
        :mean-distance (/ (reduce + distances) (max 1 (count distances)))
        :max-distance (apply max 0 distances)
        :cycle-count (count cycles)}))
   ```

3. Text table output:
   ```clojure
   (defn print-report [metrics health cycles]
     (println "Component                Ca  Ce     I     A     D")
     (println "------------------------ --- --- ----- ----- -----")
     (doseq [m (sort-by :distance > metrics)]
       (printf "%-24s %3d %3d %5.2f %5.2f %5.2f%n"
               (:component m) (:ca m) (:ce m)
               (:instability m) (:abstractness m) (:distance m)))
     (println)
     (printf "Mean distance: %.3f%n" (:mean-distance health))
     (printf "Max distance:  %.3f%n" (:max-distance health))
     (when (seq cycles)
       (println)
       (println "⚠ Cycles detected:")
       (doseq [c cycles]
         (println " " (clojure.string/join " -> " c)))))
   ```

4. EDN output for tooling:
   ```clojure
   (defn edn-report [metrics health cycles]
     {:metrics metrics
      :health health
      :cycles cycles})
   ```

**Tests to capture**:
- Report includes all components
- Sorted by distance descending (worst first)
- EDN output is valid and complete

### Phase 8: CLI

**Goal**: Runnable from command line.

**Steps**:

1. Add CLI deps and entry point:
   ```clojure
   ;; deps.edn addition
   :aliases {:run {:main-opts ["-m" "poly-metrics.core"]}
             :uberjar {...}}
   ```

2. Main function:
   ```clojure
   (defn -main [& args]
     (let [workspace-root (or (first args) ".")
           output-format (or (second args) "text")]
       (when-not (.exists (io/file workspace-root "workspace.edn"))
         (println "Error: Not a Polylith workspace (no workspace.edn)")
         (System/exit 1))
       (let [metrics (all-metrics workspace-root)
             cycles (find-all-cycles (build-dependency-graph workspace-root))
             health (codebase-health metrics cycles)]
         (case output-format
           "text" (print-report metrics health cycles)
           "edn" (prn (edn-report metrics health cycles))
           "json" (println (json/write-str (edn-report metrics health cycles))))
         (System/exit (if (and (< (:mean-distance health) 0.5)
                               (zero? (:cycle-count health)))
                        0 1)))))
   ```

3. Usage: `clj -M:run /path/to/polylith-workspace`

**Tests to capture**:
- Exits with error for non-Polylith directory
- Exit 0 for healthy codebase, 1 for unhealthy
- All output formats work

## File Structure

```
poly-metrics/
├── deps.edn
├── src/
│   └── poly_metrics/
│       ├── core.clj          ;; CLI entry point, main
│       ├── workspace.clj     ;; Polylith structure discovery
│       ├── parse.clj         ;; Namespace declaration parsing
│       ├── graph.clj         ;; Dependency graph & cycle detection
│       ├── metrics.clj       ;; Ca, Ce, I, A, D calculations
│       └── report.clj        ;; Output formatting
├── test/
│   └── poly_metrics/
│       ├── workspace_test.clj
│       ├── parse_test.clj
│       ├── graph_test.clj
│       └── metrics_test.clj
└── README.md
```

## Test Workspaces

1. **polyfy/polylith** — Reference implementation, should show healthy metrics
2. **clj-kondo** — Large real-world Polylith workspace
3. **Your own (POSES, PRODS)** — Familiar, can validate against intuition
4. **Synthetic unhealthy workspace** — Create small workspace with intentional problems:
   - Component with high distance (concrete + stable)
   - Circular dependency
   - Verify tool catches these

## Definition of Done

- [ ] Runs on polyfy/polylith, produces sensible output
- [ ] Runs on clj-kondo without errors
- [ ] Detects intentionally introduced cycle
- [ ] Flags intentionally bad component (high distance)
- [ ] All functions have tests capturing REPL discoveries
- [ ] Single command: `clj -M:run /path/to/workspace`
- [ ] Exit code: 0=healthy, 1=needs attention
- [ ] README with usage instructions

## Future Enhancements

- Track metrics over time (store in file, show delta)
- Git integration (metric per commit)
- Configurable thresholds
- Visualisation (dependency graph with metrics overlay)
- Include bases in analysis (similar to components)

## References

- [JDepend](https://github.com/clarkware/jdepend)
- [Polylith](https://polylith.gitbook.io/)
- [Robert Martin's Stability Metrics](https://www.objectmentor.com/resources/articles/stability.pdf)
