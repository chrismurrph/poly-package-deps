(ns poly-metrics.discovery
  "Bottom-up package discovery and classification.
   Walks the source tree to find all packages, then classifies each
   based on its location (Polylith, Polylith-like, or plain Clojure)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn clj-file?
  "Returns true if file is a Clojure source file (.clj or .cljc)."
  [file]
  (let [name (.getName file)]
    (or (str/ends-with? name ".clj")
        (str/ends-with? name ".cljc"))))

(defn find-all-clj-files
  "Recursively find all .clj/.cljc files under a directory."
  [dir]
  (let [root (io/file dir)]
    (when (.exists root)
      (->> (file-seq root)
           (filter #(.isFile %))
           (filter clj-file?)))))

(defn path-relative-to
  "Get the path of file relative to root."
  [root file]
  (let [root-path (.toPath (io/file root))
        file-path (.toPath (io/file file))]
    (str (.relativize root-path file-path))))

(defn path->dotted-name
  "Convert a path like 'restaurant/menu_items/mutations' to 'restaurant.menu-items.mutations'.
   Converts underscores to hyphens (Clojure convention)."
  [path-parts]
  (str/join "." (map #(str/replace % "_" "-") path-parts)))

(defn find-marker-index
  "Find the index of a marker directory (components, bases, interfaces, packages) in path parts.
   Returns [marker-type index] or nil if not found."
  [parts]
  (let [markers {"components" :polylith-component
                 "bases" :polylith-base
                 "interfaces" :polylith-like-interface
                 "packages" :polylith-like-package}]
    (first
     (for [[idx part] (map-indexed vector parts)
           :let [marker-type (get markers part)]
           :when (and marker-type
                      ;; Must have at least one more part after marker for the name
                      (< (inc idx) (count parts)))]
       [marker-type idx]))))

(defn classify-by-path
  "Classify a file path into package type based on directory patterns.
   Returns {:type :polylith-component|:polylith-base|:polylith-like-interface|:polylith-like-package|:clojure-package
            :name \"package-name\"
            :src-dir \"path/to/root\"}
   Returns nil for paths that should be ignored.

   Looks for markers (components/, bases/, interfaces/, packages/) anywhere in path,
   not just at root. This handles nested Polylith workspaces like examples/doc-example/components/user/."
  [relative-path]
  (let [parts (str/split relative-path #"/")
        ;; Remove filename to get directory parts
        dir-parts (butlast parts)
        ;; Check for marker directories anywhere in path
        marker-match (find-marker-index parts)]
    (cond
      ;; Found a Polylith/Polylith-like marker anywhere in path
      marker-match
      (let [[marker-type marker-idx] marker-match
            pkg-name (nth parts (inc marker-idx))
            ;; src-dir is everything up to and including the package name
            src-dir (str/join "/" (take (+ marker-idx 2) parts))]
        {:type marker-type
         :name pkg-name
         :src-dir src-dir})

      ;; Plain Clojure: any other source directory
      ;; Package is the parent directory of the file as a dotted namespace
      ;; e.g., src/restaurant/menu_items/mutations/clipboard.clj -> restaurant.menu-items.mutations
      ;; e.g., src-dev/user.clj -> top-level (in src-dev)
      ;; Ignore hidden directories (starting with .) and target/
      (and (seq dir-parts)
           (not (str/starts-with? (first dir-parts) "."))
           (not= "target" (first dir-parts)))
      (let [;; First part is the source root (src, src-dev, etc.)
            src-root (first dir-parts)
            ns-parts (rest dir-parts)]
        (if (empty? ns-parts)
          {:type :clojure-package
           :name (str "top-level." src-root)
           :src-dir src-root}
          {:type :clojure-package
           :name (path->dotted-name ns-parts)
           :src-dir (str/join "/" dir-parts)}))

      ;; Ignore hidden directories and other non-source files
      :else
      nil)))

(defn discover-packages
  "Walk the source tree and discover all packages with their types.
   Returns a sequence of package maps:
   [{:name \"util\" :type :polylith-component :src-dirs [\"components/util/src\"] :files [...]} ...]

   Packages with the same name from different source roots are merged.
   Raises an exception if the same filename appears in multiple source roots for the same package."
  [root-dir]
  (let [all-files (find-all-clj-files root-dir)
        ;; Classify each file and keep track of the filename
        classified (->> all-files
                        (map (fn [f]
                               (let [rel-path (path-relative-to root-dir f)
                                     classification (classify-by-path rel-path)]
                                 (when classification
                                   (assoc classification
                                          :file rel-path
                                          :filename (.getName f))))))
                        (filter some?))]
    ;; Group by name+type, merge src-dirs, check for file clashes
    (->> classified
         (group-by (juxt :name :type))
         (map (fn [[[pkg-name pkg-type] entries]]
                (let [src-dirs (distinct (map :src-dir entries))
                      files (map :file entries)
                      ;; For clojure-packages, check for filename clashes across source roots
                      ;; (same filename in different src roots = clash)
                      ;; For Polylith types, files are already in one src-dir, no clash possible
                      _ (when (and (= pkg-type :clojure-package)
                                   (> (count src-dirs) 1))
                          (let [filenames (map :filename entries)
                                dupes (->> filenames
                                           frequencies
                                           (filter #(> (val %) 1))
                                           (map key))]
                            (when (seq dupes)
                              (throw (ex-info (str "Filename clash in package '" pkg-name "': "
                                                   (str/join ", " dupes)
                                                   " appears in multiple source roots")
                                              {:package pkg-name
                                               :type pkg-type
                                               :duplicates dupes
                                               :files files})))))]
                  {:name pkg-name
                   :type pkg-type
                   :src-dirs (vec src-dirs)
                   :files (vec files)})))
         (sort-by :name))))

(defn packages-by-type
  "Group discovered packages by their type."
  [packages]
  (group-by :type packages))

(defn has-interface-detection?
  "Returns true if this package type supports interface detection."
  [package-type]
  (contains? #{:polylith-component :polylith-base
               :polylith-like-interface :polylith-like-package}
             package-type))
