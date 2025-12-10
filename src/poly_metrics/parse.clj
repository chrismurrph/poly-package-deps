(ns poly-metrics.parse
  "Namespace declaration parsing for Clojure source files."
  (:require [clojure.tools.namespace.parse :as parse]
            [clojure.java.io :as io]))

(defn read-ns-decl
  "Read the namespace declaration from a Clojure source file.
   Returns the ns form, or nil if the file has no ns declaration."
  [file]
  (try
    (with-open [rdr (java.io.PushbackReader. (io/reader file))]
      (parse/read-ns-decl rdr))
    (catch Exception _
      nil)))

(defn clj-file?
  "Returns true if the file has a .clj or .cljc extension."
  [file]
  (let [name (.getName file)]
    (or (.endsWith name ".clj")
        (.endsWith name ".cljc"))))

(defn find-clj-files
  "Find all .clj and .cljc files in a directory (recursively)."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile %))
       (filter clj-file?)))

(defn ns-name-from-decl
  "Extract the namespace name (symbol) from an ns declaration form."
  [ns-decl]
  (when ns-decl
    (second ns-decl)))

(defn deps-from-file
  "Extract the set of namespace dependencies from a Clojure source file.
   Returns nil if the file has no ns declaration."
  [file]
  (when-let [ns-decl (read-ns-decl file)]
    (parse/deps-from-ns-decl ns-decl)))

(defn build-ns-deps-map
  "Scan a directory and return a map of {namespace #{dependencies}}.
   Only includes files that have ns declarations."
  [dir]
  (->> (find-clj-files dir)
       (keep (fn [file]
               (when-let [ns-decl (read-ns-decl file)]
                 [(ns-name-from-decl ns-decl)
                  (parse/deps-from-ns-decl ns-decl)])))
       (into {})))
