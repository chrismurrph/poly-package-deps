(ns poly-metrics.workspace-test
  (:require [clojure.test :refer [deftest testing is]]
            [poly-metrics.workspace :as ws]
            [clojure.java.io :as io]))

;; Tests using the real polylith repo at /tmp/polylith-test

(def polylith-root "/tmp/polylith-test")

(deftest polylith-workspace?-test
  (testing "recognizes polylith workspace"
    (is (ws/polylith-workspace? polylith-root)))

  (testing "rejects non-polylith directory"
    (is (not (ws/polylith-workspace? "/tmp")))))

(deftest read-workspace-config-test
  (testing "reads workspace.edn"
    (let [config (ws/read-workspace-config polylith-root)]
      (is (some? config))
      (is (= "polylith.clj.core" (:top-namespace config)))))

  (testing "returns nil for non-existent workspace"
    (is (nil? (ws/read-workspace-config "/tmp/nonexistent")))))

(deftest find-components-test
  (testing "finds components in polylith repo"
    (let [components (ws/find-components polylith-root)]
      (is (set? components))
      (is (pos? (count components)))
      ;; Check for some known components
      (is (contains? components "util"))
      (is (contains? components "file"))
      (is (contains? components "git"))
      (is (contains? components "command")))))

(deftest find-bases-test
  (testing "finds bases in polylith repo"
    (let [bases (ws/find-bases polylith-root)]
      (is (set? bases))
      (is (pos? (count bases)))
      ;; Check for known bases
      (is (contains? bases "poly-cli")))))

(deftest find-bricks-test
  (testing "finds all bricks (components + bases)"
    (let [bricks (ws/find-bricks polylith-root)
          components (ws/find-components polylith-root)
          bases (ws/find-bases polylith-root)]
      (is (= (count bricks) (+ (count components) (count bases))))
      (is (contains? bricks "util"))
      (is (contains? bricks "poly-cli")))))

(deftest interface-ns?-test
  (testing "identifies interface namespaces"
    (is (ws/interface-ns? 'myapp.user.interface))
    (is (ws/interface-ns? 'myapp.user.interface.admin))
    (is (ws/interface-ns? 'polylith.clj.core.util.interface))
    (is (ws/interface-ns? 'polylith.clj.core.util.interface.str)))

  (testing "rejects non-interface namespaces"
    (is (not (ws/interface-ns? 'myapp.user.core)))
    (is (not (ws/interface-ns? 'myapp.user.impl)))
    (is (not (ws/interface-ns? 'polylith.clj.core.util.colorizer)))
    (is (not (ws/interface-ns? 'interface.something))))  ;; interface at start, not component boundary

  (testing "handles nil"
    (is (not (ws/interface-ns? nil)))))

(deftest interface-ns->component-test
  (testing "extracts component name with top-namespace"
    (is (= "util"
           (ws/interface-ns->component 'polylith.clj.core.util.interface "polylith.clj.core")))
    (is (= "util"
           (ws/interface-ns->component 'polylith.clj.core.util.interface.str "polylith.clj.core")))
    (is (= "file"
           (ws/interface-ns->component 'polylith.clj.core.file.interface "polylith.clj.core"))))

  (testing "extracts component name without top-namespace"
    (is (= "user"
           (ws/interface-ns->component 'myapp.user.interface "myapp")))
    (is (= "user"
           (ws/interface-ns->component 'myapp.user.interface.admin "myapp"))))

  (testing "handles missing top-namespace"
    (is (= "user"
           (ws/interface-ns->component 'user.interface nil)))
    (is (= "user"
           (ws/interface-ns->component 'user.interface ""))))

  (testing "returns nil for non-interface namespaces"
    (is (nil? (ws/interface-ns->component 'myapp.user.core "myapp")))))

(deftest component-paths-test
  (testing "returns correct paths"
    (let [paths (ws/component-paths polylith-root "util")]
      (is (= (io/file polylith-root "components" "util" "src")
             (:src-dir paths)))
      (is (= (io/file polylith-root "components" "util" "test")
             (:test-dir paths)))
      (is (= (io/file polylith-root "components" "util" "resources")
             (:resources-dir paths))))))

(deftest base-paths-test
  (testing "returns correct paths"
    (let [paths (ws/base-paths polylith-root "poly-cli")]
      (is (= (io/file polylith-root "bases" "poly-cli" "src")
             (:src-dir paths)))
      (is (= (io/file polylith-root "bases" "poly-cli" "test")
             (:test-dir paths))))))

(deftest component-namespaces-test
  (testing "finds namespaces in util component"
    (let [namespaces (ws/component-namespaces polylith-root "util")]
      (is (seq namespaces))
      ;; Should include both interface and implementation
      (is (some ws/interface-ns? namespaces))
      (is (some #(not (ws/interface-ns? %)) namespaces)))))

(deftest brick-interface-namespaces-test
  (testing "returns only interface namespaces"
    (let [interfaces (ws/brick-interface-namespaces polylith-root :component "util")]
      (is (seq interfaces))
      (is (every? ws/interface-ns? interfaces)))))

(deftest brick-implementation-namespaces-test
  (testing "returns only implementation namespaces"
    (let [impls (ws/brick-implementation-namespaces polylith-root :component "util")]
      (is (seq impls))
      (is (not-any? ws/interface-ns? impls)))))

;; Phase 3: Namespace to Component Mapping tests

(deftest build-ns-to-brick-map-test
  (testing "maps namespaces to their bricks"
    (let [ns-map (ws/build-ns-to-brick-map polylith-root)]
      (is (map? ns-map))
      (is (pos? (count ns-map)))
      ;; Check a known namespace from util component
      (let [util-interface-info (get ns-map 'polylith.clj.core.util.interface)]
        (is (some? util-interface-info))
        (is (= "util" (:brick-name util-interface-info)))
        (is (= :component (:brick-type util-interface-info))))
      ;; Check a known namespace from poly-cli base
      (let [poly-cli-info (get ns-map 'polylith.clj.core.poly-cli.core)]
        (is (some? poly-cli-info))
        (is (= "poly-cli" (:brick-name poly-cli-info)))
        (is (= :base (:brick-type poly-cli-info)))))))

(deftest dep-ns->brick-test
  (let [ns-map (ws/build-ns-to-brick-map polylith-root)
        top-ns "polylith.clj.core"]

    (testing "finds brick via direct map lookup"
      (is (= "util"
             (ws/dep-ns->brick 'polylith.clj.core.util.interface ns-map top-ns))))

    (testing "infers brick from interface pattern when not in map"
      ;; Create a fake interface ns that wouldn't be in the map
      (is (= "fake"
             (ws/dep-ns->brick 'polylith.clj.core.fake.interface {} top-ns))))

    (testing "returns nil for external dependencies"
      (is (nil? (ws/dep-ns->brick 'clojure.string ns-map top-ns)))
      (is (nil? (ws/dep-ns->brick 'some.external.lib ns-map top-ns))))))

(deftest resolve-dependencies-test
  (let [ns-map (ws/build-ns-to-brick-map polylith-root)
        top-ns "polylith.clj.core"]

    (testing "resolves dependencies to brick names"
      (let [deps #{'polylith.clj.core.util.interface
                   'polylith.clj.core.file.interface
                   'clojure.string}
            resolved (ws/resolve-dependencies deps ns-map top-ns "command")]
        (is (contains? resolved "util"))
        (is (contains? resolved "file"))
        (is (not (contains? resolved "clojure.string")))))

    (testing "excludes self-references"
      (let [deps #{'polylith.clj.core.util.interface
                   'polylith.clj.core.command.interface}
            resolved (ws/resolve-dependencies deps ns-map top-ns "command")]
        (is (contains? resolved "util"))
        (is (not (contains? resolved "command")))))

    (testing "handles empty dependencies"
      (is (= #{} (ws/resolve-dependencies #{} ns-map top-ns "util"))))))
