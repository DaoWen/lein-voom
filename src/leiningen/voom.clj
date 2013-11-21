(ns leiningen.voom
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as s]
            [clojure.pprint :refer [pprint]]
            [clojure.java.io :as io]
            [leiningen.core.project :as project]
            [leiningen.core.main :as lmain]
            [org.satta.glob :refer [glob]]
            [robert.hooke :as hooke])
  (:import [java.util Date]
           [java.io File]
           [java.util.logging Logger Handler Level]
           [org.sonatype.aether.transfer ArtifactNotFoundException]))

(set! *warn-on-reflection* true)

(defn git
  [{:keys [^File gitdir]} & subcmd]
  ;; We won't handle bare repos or displaced worktrees
  (let [dir-args (if (nil? gitdir)
                   []
                   [(str "--git-dir=" (.getPath gitdir))
                    (str "--work-tree=" (.getParent gitdir))])
        all-args (concat dir-args subcmd)
        rtn (apply sh "git" all-args)]
    (when-not (zero? (:exit rtn))
      (throw (ex-info "git error" (assoc rtn :git all-args))))
    (assoc rtn :lines (when (not= "\n" (:out rtn))
                        (re-seq #"(?m)^.*$" (:out rtn))))))

;; === git sha-based versions ===

(def timestamp-fmt "yyyyMMdd_hhmmss")

(defn formatted-timestamp
  [^String fmt t]
  (.format (doto (java.text.SimpleDateFormat. fmt java.util.Locale/US)
             (.setTimeZone (java.util.SimpleTimeZone. 0 "GMT")))
           t))

(defn get-voom-version
  [path & {:keys [long-sha? gitdir]}]
  (let [shafmt (if long-sha? "%H" "%h")
        fmt (str "--pretty=" shafmt ",%cd")
        {:keys [out exit err]} (git {:gitdir gitdir} "log" "-1" fmt path)
        [sha, datestr] (-> out s/trim (s/split #"," 2))
        ctime (Date. ^String datestr)]
    {:ctime ctime :sha sha}))

(defn format-voom-ver
  [gver fmt]
  (let [{:keys [ctime sha]} gver]
    (str "-" (formatted-timestamp fmt ctime) "-g" sha)))


(defn update-proj-version
  [project long-sha?]
  (let [gver (-> project :root (get-voom-version :long-sha? long-sha?))
        qual (format-voom-ver gver timestamp-fmt)
        upfn #(str (s/replace % #"-SNAPSHOT" "") qual)
        nproj (update-in project [:version] upfn)
        nmeta (update-in (meta project) [:without-profiles :version] upfn)
        nnproj (with-meta nproj nmeta)]
    nnproj))

(defn ver-parse
  "Parses jar-path-like-string or artifact-version-string to find ctime and sha.
   Can handle cases in the range of:
     1.2.3-20120219223112-abc123f
     1.2.3-20120219_223112-gabc123f
     foo-1.2.3-20120219223112-gabc123f
     foo-1.2.3-20120219_223112-abc123f
     /path/to/foo-1.2.3-20120219_223112-gabc123f19ea8d29b13.jar"
  [ver-str]
  (let [[_ ctime sha] (re-matches #".*-?([0-9]{8}_?[0-9]{6})-g?([a-f0-9]{4,40})(?:\.jar)?$" ver-str)]
    (when (and ctime sha)
      {:ctime (s/replace ctime #"_" "") :sha sha})))

(defn dirty-wc?
  [path]
  (let [{:keys [out err exit]} (git {} "status" "--short" path)]
    (not (empty? out))))


;; === manage REPOS_HOME directory ===

(def task-dir "/.voom")

(def repos-home (or (System/getenv "REPOS_HOME")
                    (str (System/getProperty "user.home")
                         (str "/.voom-repos"))))

(defn with-log-level [level f]
  (let [handlers (doall (.getHandlers (Logger/getLogger "")))
        old-levels (doall (map #(.getLevel ^Handler %) handlers))
        _ (doseq [h handlers] (.setLevel ^Handler h level))
        result (f)]
    (dorun (map #(.setLevel ^Handler %1 %2) handlers old-levels))
    result))

(defn find-project-files
  [^File d]
  (let [{:keys [lines]} (git {:gitdir d} "ls-files" "project.clj" "**/project.clj")]
    (map #(str (.getParent d) "/" %) lines)))

(defn contains-sha? [d sha]
  (prn "contains-sha?" d sha)
  (->>
   (git {:gitdir d} "rev-parse" "--verify" "--quiet" sha)
   :exit
   zero?))

(defn locate-sha
  [dirs sha]
  (seq (for [d dirs
             :when (contains-sha? d sha)]
         d)))

(defn all-repos-dirs []
  (glob (str (s/replace repos-home #"/$" "") "/*/.git")))

(defn fetch-all
  [dirs]
  (doseq [^File d dirs]
    (println "Fetching:" (.getPath d))
    (git {:gitdir d} "fetch")))

(defn find-project
  [pgroup pname candidate]
  (prn "find-project" pgroup pname candidate)
  (for [p (find-project-files candidate)
        :let [{:keys [group name] :as prj} (project/read p)]
        :when (and (= group pgroup)
                   (= name pname))]
    prj))

(defn find-matching-projects
  "[project {groupId name ctime sha}] [project coordinate-string]"
  [repos-dir pspec]
  (prn "find-matching-projects" repos-dir pspec)
  (let [{:keys [sha artifactId groupId]} pspec
        dirs (all-repos-dirs)
        sha-candidates (locate-sha dirs sha)
        sha-candidates (or sha-candidates
                           (do (fetch-all dirs)
                               (locate-sha dirs sha)))]
    (prn "sha-candidates" sha-candidates)
    (mapcat (fn [c]
              (git {:gitdir c} "checkout" sha)
              (find-project groupId artifactId c))
            sha-candidates)))

(defn install-versioned-artifact
  [proot]
  (let [install-cmd ["lein" "voom" "install" :dir proot]
        _ (apply println "install-versioned-artifact:" install-cmd)
        rtn (sh "lein" "voom" "install" :dir proot)]
    (when-not (zero? (:exit rtn))
      (throw (ex-info "lein voom install error" (assoc rtn :cmd install-cmd))))
    rtn))

(defn missing-artifacts-from-exception
  "Returns a sequence of artifacts indicated as missing anywhere in
  any ArtifactNotFoundException that appears in the cause chain of e"
  [e]
  (for [^Exception cause (iterate #(.getCause ^Exception %) e)
        :while cause
        :when (instance? ArtifactNotFoundException cause)]
    (let [art (.getArtifact ^ArtifactNotFoundException cause)]
      (select-keys (bean art) [:groupId :artifactId :version]))))


;; === build-deps ===

(defn resolve-artifact
  "Build and install given artifact using git. Return value is
  undefined. Throws an exception with detailed message if artifact
  cannot be resolved."
  [old-exception {:keys [artifactId version] :as art}]

  (if-let [vmap (ver-parse version)]
    (let [prjs (find-matching-projects repos-home (merge vmap art))]
      (when (empty? prjs)
        (throw (ex-info (str "No project found for " artifactId " " version
                             " (Hint: might need to clone a new repo into "
                             repos-home ")")
                        {:artifact art :vmap vmap} old-exception)))
      (when (< 1 (count prjs))
        (println "WARNING: multiple projects match" artifactId ":" )
        (doseq [prj prjs]
          (println "->" (:root prj))))
      (doseq [prj prjs]
        (install-versioned-artifact (:root prj))))
    (throw (ex-info (str "Not parseable as voom-version: " version) {:artifact art} old-exception))))

(def null-writer
  "Like /dev/null, but for Java!"
  (proxy [java.io.Writer] []
    (write ([_]) ([_ _ _]))
    (flush [])
    (close [])))

(defn try-once-resolve-voom-version [project]
  (try
    (with-log-level Level/OFF
      #(binding [*err* null-writer]
         (leiningen.core.classpath/resolve-dependencies :dependencies project)))
    :ok
    (catch Exception e
      ;; lein resolve-dependencies wraps a
      ;; DependencyResolutionException in an ex-info, so if we want
      ;; the real cause of failure we have to dig for it:
      (if-let [arts (seq (missing-artifacts-from-exception e))]
        (doseq [art arts]
          (resolve-artifact e art))
        (throw e)))))

(defn build-deps
  "Resolves project dependencies like 'lein deps', but also uses REPOS_HOME"
  [project & args]
  (try
    (loop []
      (when-not (= :ok (try-once-resolve-voom-version project))
        (recur)))
    (catch clojure.lang.ExceptionInfo e
      (println "Failed to resolve dependency:" (.getMessage e))
      (pprint {:exception-data (ex-data e)}))))


;; === freshen ===

(defn fetch-checkout-all
  "This is slower than necessary for most real use cases."
  [dirs]
  (doseq [^File d dirs]
    (println "Checking out latest:" (.getPath d))
    (git {:gitdir d} "fetch")
    (git {:gitdir d} "checkout" "origin/HEAD")))

(defn read-project [gitdir sha prj-path]
  (let [tmp-file (File/createTempFile ".project-" ".clj")
        _ (spit tmp-file
                (:out (git {:gitdir gitdir} "show" (str sha ":" prj-path))))
        prj (try (assoc (project/read (str tmp-file))
                   :root (or (.getParent (io/file prj-path)) ""))
                 (catch Throwable t
                   (throw (ex-info "Error reading project file"
                                   {:project-file prj-path
                                    :git-sha sha
                                    :git-dir gitdir}
                                   t))))]
    (.delete tmp-file)
    prj))


(defn patch-fn
  [f default-val]
  (fn [filename & args]
    (if (re-find #"\.lein|project[^/]*\.clj$" filename)
      (apply f filename args)
      default-val)))

(defn robust-read-project
  [gitdir sha prj-path]
  (try
    ;; Hack to work around our crazy project.clj files
    (with-redefs [slurp (patch-fn slurp "{}")
                  load-file (patch-fn load-file {})]
      (read-project gitdir sha prj-path))
    (catch Exception e
      ;; 128 means git complained about
      ;; something. Probably a non-existant
      ;; project.clj at this sha.
      (when-not (= 128 (:exit (ex-data e)))
        (println "Skipping error:" (pr-str e)))
      nil)))

(defn re-quote [s]
  (s/replace s #"\." "\\\\."))

;; This is not right, at least when asking for the latest version
(defn find-ver-part-projs
  [gitdir prj-name ver-part]
  (let [ver-ptn (re-pattern (str (re-quote ver-part) #"[-.].*"))
        qprj (re-quote prj-name)
        prj-ptn (if (= (name prj-name) (namespace prj-name))
                  (str "(" (re-quote (name prj-name)) "|" qprj ")")
                  qprj)
        git-ptn (str #"\(defproject\s+" prj-ptn #"\s+\"" ver-ptn \")
        _ (prn :git-ptn git-ptn)
        {:keys [lines]} (git {:gitdir gitdir} "log" "--name-only" "--all" "--pretty=format:%H"
                             "-G" git-ptn "--" "project.clj" "**/project.clj")]
    (->> lines
         (reductions (fn [[sha v] line]
                       (cond
                        (empty? line) [nil nil]
                        sha [sha {:sha sha :file line}]
                        :else [line nil]))
                     [nil nil])
         (keep second)
         (keep
          (fn [{:keys [sha file]}]
            (let [{:keys [group name version]} (read-project gitdir sha file)]
              (prn group name version)
              (when (and (re-matches ver-ptn version)
                         (= prj-name (symbol group name)))
                {:sha sha
                 :v version}))))
         first)))

(defn likely-project-shas
  "Return lazy seq of maps with :sha and :file keys, each representing
  a sha of the given gitdir that has a change to the project.clj named
  in the map. Ordered by newest to oldest."
  [gitdir]
  (let [r (git {:gitdir gitdir} "log" "--name-only" "--all"
               "--pretty=format:%H" "--" "project.clj" "**/project.clj")]
    (->> (:lines r)
         (reductions (fn [[sha v] line]
                       (cond
                        (empty? line) [nil nil]
                        sha [sha {:sha sha :file line}]
                        :else [line nil]))
                     [nil nil])
         (keep second))))

(defn report-progress
  "Eagerly consume xs, but return a lazy seq that reports progress
  every half second as the returned seq is consumed."
  [msg xs]
  (concat
   (let [last-report (clojure.lang.Box. 0)
         c (count xs)
         digits (inc (long (quot (Math/log c) (Math/log 10))))]
     (map-indexed
      (fn [i x]
        (let [now (System/currentTimeMillis)]
          (when (< 500 (- now (.val last-report)))
            (set! (.val last-report) now)
            (printf (str "\r%s %" digits "d/%d ...") msg i c)
            (flush)))
        x)
      xs))
   (lazy-seq (println "done"))))

(defn update-ver-cache
  [gitdir]
  (let [prj-changes (likely-project-shas gitdir)
        sha-info (map
                  (fn [{:keys [sha file]}]
                    (when-let [p (robust-read-project gitdir sha file)]
                      {:proj (symbol (:group p) (:name p))
                       :v (:version p)
                       :sha sha}))
                  (report-progress gitdir prj-changes))]
    (remove nil? sha-info)))

;; Add metadata pinning (how big to bump? all? minor? sha?) -- warn about not updating
;; Opt-in autobump  -- default to no major auto
(defn get-fresh-versions []
  (let [dirs (all-repos-dirs)
        _ (fetch-checkout-all dirs)]
    (into {}
          (for [dir dirs
                prj-file (find-project-files dir)]
            (let [{:keys [group name version] :as prj} (project/read prj-file)
                  gver (get-voom-version (:root prj) :gitdir dir)
                  qual (format-voom-ver gver timestamp-fmt)
                  new-ver (str (s/replace version #"-SNAPSHOT" "") qual)]
              [(symbol group name) new-ver])))))

(defn fresh-version [fresh-versions-map [prj ver :as dep]]
  (if-let [new-ver (get fresh-versions-map prj)]
    (assoc dep 1 new-ver)
    (do
      (when (ver-parse ver)
        (println "Warning: didn't find or freshen voom-version dep" dep))
      dep)))

(defn rewrite-project-file [input-str replacement-map]
  (reduce (fn [^String text [[prj old-ver :as old-dep] [_ new-ver]]]
            (let [short-prj (if (= (name prj) (namespace prj))
                              (name prj)
                              (str prj))
                  pattern (re-pattern (str "\\Q" short-prj "" "\\E(\\s+)\\Q\"" old-ver "\"\\E"))
                  matches (re-seq pattern input-str)]
              (when (empty? matches)
                (throw (ex-info (str "No match found for " [prj old-ver])
                                {:pattern pattern :dep old-dep})))
              (when (second matches)
                (throw (ex-info (str "More than one match found for " [prj old-ver])
                                {:pattern pattern :dep old-dep})))
              (s/replace text pattern (str short-prj "$1" \" new-ver \"))))
          input-str
          replacement-map))

(defn freshen [project & args]
  (let [prj-file-name (str (:root project) "/project.clj")
        old-deps (:dependencies project)
        fresh-versions-map (get-fresh-versions)
        desired-new-deps (map #(fresh-version fresh-versions-map %) old-deps)]
    (doseq [[[prj old-ver] [_ new-ver]] (map list old-deps desired-new-deps)]
      (println (format "%-40s" prj)
               (str old-ver
                    (when (not= old-ver new-ver)
                      (str " -> " new-ver)))))
    (if (= old-deps desired-new-deps)
      (println "All deps already up-to-date.")
      (let [replacement-map (into {} (map #(when (not= %1 %2) [%1 %2])
                                          old-deps desired-new-deps))
            tmp-file (File/createTempFile
                      ".project-" ".clj" (File. ^String (:root project)))]

        (spit tmp-file
              (rewrite-project-file (slurp prj-file-name) replacement-map))

        (if (= desired-new-deps (:dependencies (project/read (str tmp-file))))
          (.renameTo tmp-file (File. prj-file-name))
          (throw (ex-info (str "Freshen mis-fire. See "
                               tmp-file " for attempted change.")
                          {:old-deps old-deps
                           :replacement-map replacement-map
                           :desired-new-deps desired-new-deps
                           :tmp-file-name (str tmp-file)})))))))

(defn repo-project-files
  [gitdir]
  (->
   (git {:gitdir gitdir} "ls-files" "project.clj" "**/project.clj")
   :lines))

(defn box-add
  [proj & args]
  (let [res (for [rdir (all-repos-dirs)
                  pfile (repo-project-files rdir)]
              [rdir pfile])]
    (doseq [[^File r p] res]
      (let [pf (str (.getParent r) "/" p)]
        (prn (.getParent r) p (select-keys (project/read pf) [:group :name]))))))

;; === lein entrypoint ===

(defn nope [& args]
  (println "That voom sub-comand is not yet implemented."))

;; TODO: Consider revamping these entry points. Separate top-level
;; lein commands?  Separate lein plugins?
(def sub-commands
  {"build-deps" build-deps
   "freshen" freshen
   "box-add" box-add
   "new-task" nope})

(defn voom
  "Usage:
    lein voom [flags] [lein command ...]
      Runs lein command with a project version augmented with git
      version of the most recent change of this project directory.
      Flags include:
        :insanely-allow-dirty-working-copy - by default voom
          refuses to handle a dirty working copy
        :no-upstream - by default voom wants to see the current
          version reachable via an upstream repo
        :long-sha - uses a full length sha instead of the default
          short form
    lein voom [:long-sha] :print
    lein voom :parse <version-str>"
  [project & args]
  (let [[kstrs sargs] (split-with #(.startsWith ^String % ":") args)
        kargset (set (map #(keyword (subs % 1)) kstrs))
        long-sha (kargset :long-sha)
        new-project (update-proj-version project long-sha)]
    ;; TODO throw exception if upstream doesn't contain this commit :no-upstream
    (cond
     (:print kargset) (println (:version new-project))
     (:parse kargset) (prn (ver-parse (first sargs)))
     :else (if-let [f (get sub-commands (first sargs))]
             (apply f new-project (rest sargs))
             (if (and (dirty-wc? (:root new-project))
                      (not (:insanely-allow-dirty-working-copy kargset)))
               (lmain/abort "Refusing to continue with dirty working copy. (Hint: Run 'git status')")
               (lmain/resolve-and-apply new-project sargs))))))
