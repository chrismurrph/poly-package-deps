(ns poly-metrics.parse-test
  (:require [clojure.test :refer [deftest testing is]]
            [poly-metrics.parse :as parse]
            [clojure.java.io :as io]
            [clojure.tools.namespace.parse :as tns-parse]))

;; Test deps-from-ns-decl via tools.namespace directly
;; (our parse/deps-from-file delegates to it)

(deftest deps-from-ns-decl-test
  (testing "simple require"
    (is (= #{'bar.interface}
           (tns-parse/deps-from-ns-decl
            '(ns foo.core (:require [bar.interface :as bar]))))))

  (testing "require without alias"
    (is (= #{'bar.core}
           (tns-parse/deps-from-ns-decl
            '(ns foo.core (:require [bar.core]))))))

  (testing "require with refer"
    (is (= #{'bar.util}
           (tns-parse/deps-from-ns-decl
            '(ns foo.core (:require [bar.util :refer [helper]]))))))

  (testing "require with refer :all"
    (is (= #{'bar.macros}
           (tns-parse/deps-from-ns-decl
            '(ns foo.core (:require [bar.macros :refer :all]))))))

  (testing "multiple requires"
    (is (= #{'bar.interface 'baz.interface 'qux.core}
           (tns-parse/deps-from-ns-decl
            '(ns foo.core
               (:require [bar.interface :as bar]
                         [baz.interface :as baz]
                         [qux.core]))))))

  (testing "use clause (legacy)"
    (is (= #{'old.lib}
           (tns-parse/deps-from-ns-decl
            '(ns foo.core (:use old.lib))))))

  (testing "require-macros (ClojureScript)"
    (is (= #{'macros.core}
           (tns-parse/deps-from-ns-decl
            '(ns foo.core (:require-macros [macros.core :as m]))))))

  (testing "prefix list require"
    (is (= #{'foo.bar 'foo.baz}
           (tns-parse/deps-from-ns-decl
            '(ns my.core (:require [foo [bar :as b] [baz :as z]])))))))

(deftest clj-file?-test
  (testing "identifies .clj files"
    (is (parse/clj-file? (io/file "foo.clj")))
    (is (parse/clj-file? (io/file "path/to/bar.clj"))))

  (testing "identifies .cljc files"
    (is (parse/clj-file? (io/file "foo.cljc")))
    (is (parse/clj-file? (io/file "path/to/bar.cljc"))))

  (testing "rejects other extensions"
    (is (not (parse/clj-file? (io/file "foo.cljs"))))
    (is (not (parse/clj-file? (io/file "foo.edn"))))
    (is (not (parse/clj-file? (io/file "foo.txt"))))
    (is (not (parse/clj-file? (io/file "foo.java"))))))

(deftest ns-name-from-decl-test
  (testing "extracts namespace name"
    (is (= 'foo.core
           (parse/ns-name-from-decl '(ns foo.core (:require [bar]))))))

  (testing "returns nil for nil input"
    (is (nil? (parse/ns-name-from-decl nil)))))

;; Integration tests using temp files

(deftest read-ns-decl-test
  (testing "reads ns declaration from file"
    (let [temp-file (java.io.File/createTempFile "test" ".clj")]
      (try
        (spit temp-file "(ns my.test.ns (:require [clojure.string :as str]))")
        (let [ns-decl (parse/read-ns-decl temp-file)]
          (is (= 'my.test.ns (second ns-decl)))
          (is (= #{'clojure.string} (tns-parse/deps-from-ns-decl ns-decl))))
        (finally
          (.delete temp-file)))))

  (testing "returns nil for file without ns"
    (let [temp-file (java.io.File/createTempFile "test" ".clj")]
      (try
        (spit temp-file "(defn foo [] 42)")
        (is (nil? (parse/read-ns-decl temp-file)))
        (finally
          (.delete temp-file)))))

  (testing "returns nil for empty file"
    (let [temp-file (java.io.File/createTempFile "test" ".clj")]
      (try
        (spit temp-file "")
        (is (nil? (parse/read-ns-decl temp-file)))
        (finally
          (.delete temp-file))))))

(deftest find-clj-files-test
  (testing "finds .clj and .cljc files in directory"
    (let [temp-dir (java.io.File/createTempFile "testdir" "")
          _ (.delete temp-dir)
          _ (.mkdirs temp-dir)
          clj-file (io/file temp-dir "foo.clj")
          cljc-file (io/file temp-dir "bar.cljc")
          cljs-file (io/file temp-dir "baz.cljs")
          txt-file (io/file temp-dir "readme.txt")]
      (try
        (spit clj-file "(ns foo)")
        (spit cljc-file "(ns bar)")
        (spit cljs-file "(ns baz)")
        (spit txt-file "readme")
        (let [found (set (map #(.getName %) (parse/find-clj-files temp-dir)))]
          (is (contains? found "foo.clj"))
          (is (contains? found "bar.cljc"))
          (is (not (contains? found "baz.cljs")))
          (is (not (contains? found "readme.txt"))))
        (finally
          (.delete clj-file)
          (.delete cljc-file)
          (.delete cljs-file)
          (.delete txt-file)
          (.delete temp-dir))))))

(deftest build-ns-deps-map-test
  (testing "builds map of namespace to dependencies"
    (let [temp-dir (java.io.File/createTempFile "testdir" "")
          _ (.delete temp-dir)
          _ (.mkdirs temp-dir)
          file-a (io/file temp-dir "a.clj")
          file-b (io/file temp-dir "b.clj")
          file-no-ns (io/file temp-dir "no_ns.clj")]
      (try
        (spit file-a "(ns test.a (:require [test.b :as b] [clojure.string :as str]))")
        (spit file-b "(ns test.b)")
        (spit file-no-ns "(defn helper [] 42)")
        (let [deps-map (parse/build-ns-deps-map temp-dir)]
          (is (= #{'test.b 'clojure.string} (get deps-map 'test.a)))
          (is (= #{} (get deps-map 'test.b)))
          (is (not (contains? deps-map nil))))
        (finally
          (.delete file-a)
          (.delete file-b)
          (.delete file-no-ns)
          (.delete temp-dir))))))
