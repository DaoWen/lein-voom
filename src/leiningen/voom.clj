(ns leiningen.voom
  (:require [clojure.java.shell :as shell]
            [clojure.string :as s]
            [clojure.pprint :refer [pprint print-table]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [leiningen.core.project :as project]
            [leiningen.core.main :as lmain]
            [org.satta.glob :refer [glob]]
            [robert.hooke :as hooke])
  (:import [java.util Date]
           [java.io File FileInputStream FileOutputStream OutputStreamWriter]
           [java.util.logging Logger Handler Level]
           [org.sonatype.aether.transfer ArtifactNotFoundException]))

(set! *warn-on-reflection* true)

(def ^:dynamic ^FileInputStream *ififo* nil)
(def ^:dynamic ^OutputStreamWriter *ofifo* nil)
(def ^:dynamic ^File *pwd* nil)

(defn sh
  [& cmdline]
  (apply shell/sh (map #(if (= File (class %))
                          (.getPath ^File %)
                          %)
                       cmdline)))

(defn fcmd
  [cmd]
  (if (and *ififo* *ofifo*)
    (do
      (binding [*out* *ofifo*]
        (println cmd)
        (.flush *out*))
      (let [b (byte-array 5)]
        (while (= -1 (.read *ififo* b))
          (Thread/sleep 1))
        (-> b String. s/trim Integer/parseInt)))
    -1))

(defn git
  [{:keys [^File gitdir ok-statuses]
    :or {ok-statuses #{0}}} & subcmd]
  ;; We won't handle bare repos or displaced worktrees
  (let [cmd-args (if (nil? gitdir)
                   []
                   [:dir gitdir])
        all-args (concat subcmd cmd-args)
        ;; _ (prn :calling (doall (cons 'git all-args)))
        {:keys [exit] :as rtn} (apply sh "git" all-args)
        rtn (assoc rtn :bool (if (zero? (:exit rtn))
                               true
                               false))]
    (when-not (ok-statuses exit)
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
        {:keys [out]} (git {:gitdir gitdir} "log" "-1" fmt path)
        [sha, datestr] (-> out s/trim (s/split #"," 2))
        ctime (Date. ^String datestr)]
    {:ctime ctime :sha sha}))

(defn format-voom-ver
  [gver]
  (let [{:keys [version ctime sha]} gver]
    (assert version   (str "format-voom-ver requires :version " (pr-str gver)))
    (assert ctime (str "format-voom-ver requires :ctime " (pr-str gver)))
    (assert sha   (str "format-voom-ver requires :sha " (pr-str gver)))
    (str (s/replace version #"-SNAPSHOT" "")
         "-" (formatted-timestamp timestamp-fmt ctime) "-g" sha)))

(defn update-proj-version
  [project long-sha?]
  (let [gver (-> project :root (get-voom-version :long-sha? long-sha?))
        upfn #(format-voom-ver (assoc gver :version %))
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

(def task-dir ".voom-box")

(def repos-home (or (System/getenv "REPOS_HOME")
                    (str (System/getProperty "user.home")
                         (str "/.voom-repos"))))

(defn remotes
  "For a given .git directory, returns a map like:
  {:origin {:push  \"git@github.com:abrooks/lein-voom.git\",
            :fetch \"git@github.com:abrooks/lein-voom.git\"}}"
  [gitdir]
  (reduce
   (fn [m line]
     (let [[_ remote url direction] (re-matches #"(.*)\t(.*)\s+\(([^)]*)\)$" line)]
       (assoc-in m [(keyword remote) (keyword direction)] url)))
   {}
   (:lines (git {:gitdir gitdir} "remote" "-v"))))

(defn ancestor?
  [gitdir old new]
  (:bool (git {:gitdir gitdir :ok-statuses #{0 1}}
                   "merge-base" "--is-ancestor" old new)))

(defn dirty-repo?
  [gitdir]
  (let [g {:gitdir gitdir}
        dirty (:lines (git g "status" "--short"))
        stashes (:lines (git g "stash" "list"))
        remotes (->> (git g "ls-remote")
                     :lines
                     next
                     (map #(first (s/split % #"\t" 2)))
                     set)
        local-refs (filter (complement #{"refs/stash"})
                           (:lines (git g "rev-parse" "--symbolic" "--all")))
        local-shas (:lines (apply git g "rev-parse" local-refs))
        locals (zipmap local-refs local-shas)
        unpushed-local-refs (map key (filter #(not (remotes (val %))) locals))
        local-commits (filter #(not (some (partial ancestor? gitdir %)
                                          remotes))
                              unpushed-local-refs)]
    (if (empty? (concat dirty stashes local-commits))
      false
      {:dirty-files dirty
       :stashes stashes
       :local-commits local-commits})))

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
  (->>
   (git {:gitdir d :ok-statuses #{0 1}} "rev-parse" "--verify" "--quiet" sha)
   :exit
   zero?))

(defn locate-sha
  [dirs sha]
  (seq (for [d dirs
             :when (contains-sha? d sha)]
         d)))

(defn all-repos-dirs []
  (glob (str (s/replace repos-home #"/$" "") "/*")))

(defn fetch-all
  [dirs]
  (doseq [^File d dirs]
    (print (str "Fetching: " (.getPath d) "\n"))
    (flush)
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
  ;; TODO: find correct sha and project.clj more efficiently and with
  ;; less ambiguity. (only consider projects changed at the given sha)
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

;; Shhh...
(.setDynamic #'slurp)
(.setDynamic #'load-file)
(defn robust-read-project
  [gitdir sha prj-path]
  (try
    ;; Hack to work around our crazy project.clj files
    (binding [slurp (patch-fn slurp "{}")
              load-file (patch-fn load-file {})]
      (read-project gitdir sha prj-path))
    (catch Exception e
      ;; 128 means git complained about
      ;; something. Probably a non-existant
      ;; project.clj at this sha.
      (when-not (= 128 (:exit (ex-data e)))
        (println "Ignoring error:" (pr-str e)))
      nil)))

(defn origin-branches
  [gitdir]
  (->> (git {:gitdir gitdir} "rev-parse"
            "--symbolic-full-name" "--remotes=origin/")
       :lines
       (map #(re-find #"[^/]+$" %))
       distinct))

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
          (when (or (= (inc i) c) (< 500 (- now (.val last-report))))
            (set! (.val last-report) now)
            (printf (str "\r%s %" digits "d/%d ...") msg (inc i) c)
            (flush)))
        x)
      xs))
   (lazy-seq (println "done"))))

(defn parse-sha-refs
  [s]
  (let [[sha datestr parents refstr] (vec (.split #"," s 4))
        refs (when refstr
               (when-let [[_ x] (re-find #"\((.*)\)" refstr)]
                 (mapv #(s/replace % #"^tag: " "") (.split #",\s+" x))))]
    {:sha sha, :ctime (Date. ^String datestr), :parents parents, :refs refs}))

(defn project-change-shas
  [gitdir & opts]
  (->> (apply git {:gitdir gitdir} "log" "--pretty=format:%H,%cd,%p,%d"
              "--name-status" "-m" opts)
       :lines
       (keep #(if-let [[_ op path] (re-matches #"(.)\t(.*)" %)]
                (when (re-find #"(^|/)project\.clj$" path)
                  {:op op :path path})
                (when (seq %)
                  (parse-sha-refs %))))
       (#(concat % [{:sha "end sigil"}]))
       (reductions (fn [[partial complete] entry]
                     (if (:sha entry)
                       [entry partial]
                       [(update-in partial [:ops] (fnil conj []) entry) nil]))
                   [nil nil])
       (keep second)
       (filter :ops)))

(defn tag-repo-projects
  [gitdir]
  (let [branches (origin-branches gitdir)
        proj-shas (apply project-change-shas gitdir
                         "--not" "--tags=voom-branch--*"
                         (map #(str #_double-negative--> "^origin/" %) branches))]

    ;; add missing voom-- tags
    (doseq [:when (seq proj-shas)
            {:keys [sha refs parents ops]} (report-progress gitdir proj-shas)
            {:keys [op path]} ops]
      (when-let [p (if (= "D" op)
                     {:root (str (.getParent (io/file path)))}
                     (robust-read-project gitdir sha path))]
        (let [tag (s/join "--" (-> ["voom"
                                    (str (:group p)
                                         (when (:group p) "%")
                                         (:name p))
                                    (:version p)
                                    (s/replace (:root p) #"/" "%")
                                    (subs sha 0 7)]
                                   (cond-> (empty? parents)
                                     (conj "no-parent"))))]
          (git {:gitdir gitdir} "tag" "-f" tag sha))))

    ;; TODO: clean up abandoned voom-- and voom-branch-- tags
    ;; Update all voom-branch-- tags
    (doseq [branch branches]
      (let [tag (str "voom-branch--" branch)]
        (git {:gitdir gitdir} "tag" "-f" tag (str "origin/" branch))))))

(defn p-repos
  "Call f once for each repo dir, in parallel. When all calls are
  done, return nil."
  [f]
  (->> (all-repos-dirs)
       (map #(future (f %)))
       doall
       (map deref)
       dorun))

(defn clear-voom-tags
  [gitdir]
  (let [tags (->> (git {:gitdir gitdir} "tag" "--list" "voom-*")
                  :lines
                  (remove empty?))]
    (when (seq tags)
      (apply git {:gitdir gitdir} "tag" "--delete" tags)
      nil)))

(defn parse-tag
  [tag]
  (->
   (zipmap [:prefix :proj :version :path :sha :no-parent] (s/split tag #"--"))
   (update-in [:path] (fnil #(s/replace % #"%" "/") :NOT_FOUND))
   (update-in [:proj] (fnil #(s/replace % #"%" "/") :NOT_FOUND))))

(defn assert-good-version [ver proj-name version tags found-branch neg-tags commits]
  (when-not ver
    (println "Tags matching" proj-name version ":")
    (doseq [t tags]
      (prn t))
    (println "Tag filters to exclude from branch" found-branch ":")
    (doseq [t neg-tags]
      (prn t))
    (println "Commits:")
    (doseq [c commits]
      (prn c))
    (throw (ex-info "Failed to find version for commit." {}))))

(defn newest-voom-ver-by-spec
  [proj-name {:keys [version repo branch path]
              :or {version ""}}]
  (for [gitdir (all-repos-dirs)
        :when (or (nil? repo) (= repo (-> (remotes gitdir) :origin :fetch)))
        :let [ptn (s/join "--" ["voom"
                                (str (namespace proj-name) "%" (name proj-name))
                                (str version "*")])
              tags (set (:lines (git {:gitdir gitdir} "tag" "--list" ptn)))
              tspecs (if (= tags [""])
                       []
                       (map parse-tag tags))
              paths (set (map :path tspecs))]
        found-path paths
        :when (or (= found-path path) (nil? path))
        found-branch (origin-branches gitdir)
        :when (or (= found-branch branch) (nil? branch))
        :let [;; All the tags NOT accessible via this branch (we will exclude them):
              not-not-tags (set (mapcat #(:refs (parse-sha-refs %))
                                        (:lines (git {:gitdir gitdir} "log" "--pretty=format:%H,%cd,%p,%d" "--all"
                                                     "--simplify-by-decoration" "-m" (str "^origin/" found-branch)))))
              ;; yes-yes-tags are the matching tags reachable via this branch:
              yes-yes-tags (set/difference tags not-not-tags)
              tags-here (filter #(= found-path (:path (parse-tag %))) yes-yes-tags)]
        ;; If this branch contains no matching tags, the
        ;; project+version we're looking for must not be on this
        ;; branch. Skip it.
        :when (seq tags-here)
        :let [tags-here-with-parents (remove #(.endsWith ^String % "--no-parent") tags-here)
              neg-tags (map #(str "^" % "^") tags-here-with-parents)
              ;; All commits on the current branch more recent than
              ;; (and including) the most recent tag matching our
              ;; version spec:
              commits
              , (map
                 parse-sha-refs
                 (:lines (apply git {:gitdir gitdir} "log"
                                "--pretty=format:%H,%cd,%p,%d" "--reverse"
                                (concat neg-tags [(str "origin/" found-branch) "--" found-path]))))]
        :when (seq commits)]
    (let [refs (-> commits first :refs)
          reflist (filter #(and
                            (= (str proj-name) (:proj %))
                            (= found-path (:path %))) (map parse-tag refs))
          ver (-> reflist first :version)]
      (assert-good-version ver proj-name version tags found-branch neg-tags commits)
      ;; Walk forward through time, looking for when the next commit
      ;; is one too far (meaning: we have no further commits to
      ;; consider or the project at this path has changed version or
      ;; project name):
      (some (fn [[current next-commit]]
              (when (or (= :end next-commit)
                        ;; Find if any of the refs of this commit are
                        ;; voom tags at the same path:
                        (some #(let [t (parse-tag %)]
                                 (and
                                  (= "voom" (:prefix t))
                                  (= found-path (:path t))))
                              (:refs next-commit)))
                {:sha (:sha current)
                 :ctime (:ctime current)
                 :version ver
                 :path found-path
                 :proj proj-name
                 :gitdir gitdir
                 :branch found-branch}))
            (partition 2 1 (concat commits [:end]))))))

(defn print-repo-infos
  [repo-infos]
  (->> repo-infos
       (map (fn [info]
              (-> info
                  (dissoc :gitdir)
                  (assoc :repo (-> (remotes (:gitdir info)) :origin :fetch))
                  (update-in [:sha] #(subs (str % "--------") 0 7)))))
       (sort-by :ctime)
       (print-table [:repo :proj :version :branch :path :ctime :sha]))
  (newline))

(defn fresh-version [[prj ver :as dep]]
  (let [voom-meta (:voom (meta dep))
        ver-spec (or (:version voom-meta)
                     (re-find #"^[^.]+." ver))
        groups (->> (newest-voom-ver-by-spec prj (assoc voom-meta :version ver-spec))
                    (map #(assoc % :voom-ver (format-voom-ver
                                              (update-in % [:sha] subs 0 7))))
                    (group-by :voom-ver))]
    (case (count groups)
     0 (do (println "No matching version found for" prj (pr-str ver-spec))
           dep)
     1 (assoc dep 1 (key (first groups)))
     (do (print "\nMultiple bump resolutions for:"
                prj (pr-str ver-spec) (pr-str voom-meta))
         (print-repo-infos (map #(first (val %)) groups))
         dep))))

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
  (p-repos (fn [p] (fetch-all [p]) (tag-repo-projects p)))
  (let [prj-file-name (str (:root project) "/project.clj")
        old-deps (:dependencies project)
        desired-new-deps (doall (map #(fresh-version %) old-deps))]
    (doseq [[[prj old-ver] [_ new-ver]] (map list old-deps desired-new-deps)]
      (println (format "%-40s" prj)
               (str old-ver
                    (when (not= old-ver new-ver)
                      (str " -> " new-ver)))))
    (if (= old-deps desired-new-deps)
      (println "No versions bumped.")
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

(defn all-projects
  []
  (into #{}
        (flatten
         (for [g (all-repos-dirs)]
           (map #(s/replace (second (s/split % #"--")) #"%" "/")
                (:lines (git {:gitdir g} "tag" "--list" "voom--*")))))))

(defn resolve-short-proj
  [dep projects]
  (for [proj projects
        :when (.contains ^String proj dep)]
    proj))

(defn ^File to-file
  [p]
  (if (= File (type p))
    p
    (File. ^String p)))

(defn ^File adj-path
  [^File f & path]
  (-> f
      to-file
      .getCanonicalPath
      (str "/" (s/join "/" path))
      File.))

(defn ^File adj-dir
  [p & path]
  ;; TODO Should this be made an isDirectory check?
  (let [^File f (to-file p)
        d (if (.isFile f)
            (.getParentFile f)
            f)]
    (apply adj-path d path)))

(defn ^File find-box
  "Locates voom-box root starting from current working directory."
  []
  (loop [^File path (or *pwd* (-> "user.dir" System/getProperty File.))]
    (let [^String ppath (.getCanonicalPath path)
          ^File pfile (adj-dir ppath task-dir)]
      (when-not (= "/" ppath)
        (if (.exists pfile)
          path
          (recur (.getParentFile path)))))))

(defn all-boxes
  []
  (when-let [box (find-box)]
    (->> (.listFiles box)
         (filter #(.contains (.getCanonicalPath ^File %) (str "/" task-dir "/")))
         (map (memfn ^File getName)))))

(defn safe-delete-repo
  [checkout pdir]
  (if-let [dirty (dirty-repo? checkout)]
          (do (println "Must clean up repo:" pdir)
              (prn dirty)
              (lmain/abort "Please fix."))
          (do (sh "rm" "-f" pdir)
              (sh "rm" "-rf" checkout))))

(defn box-repo-add
  [{:keys [gitdir branch sha proj path]}]
  (if-let [bdir (find-box)]
    (let [pname (-> (str proj)
                    (s/replace #"/" "--"))
          pdir (adj-path bdir pname)
          checkout (adj-path bdir task-dir pname)
          g {:gitdir checkout}
          remote (-> (remotes gitdir) :origin :fetch)]
      (when (and (.exists ^File checkout)
                 (not= remote (-> (remotes (:gitdir g)) :origin :fetch)))
        (safe-delete-repo checkout pdir))
      (if (.exists ^File checkout)
        (git g "fetch")
        (git {} "clone" remote "--refer" gitdir checkout))
      (git g "checkout" sha) ; must detach head for update...
      (git g "branch" "-f" branch sha)
      (git g "checkout" branch)
      (sh "rm" "-f" pdir)
      ;; (SYMLINK WILL NEED TO BE UPDATED FOR EACH CHECKOUT)
      (sh "ln" "-s" (adj-path checkout path) pdir))
    (println "Can't find box")))

(defn fold-args-as-meta
  [adeps]
  ;; TODO better handle malformed commandlines for error reporting
  (loop [deps [] [fdep & rdeps] adeps]
    (if fdep
      (if (seq rdeps)
        (if (map? fdep)
          (let [[ndep & rdeps] rdeps]
            (recur (conj deps (with-meta ndep fdep)) rdeps))
          (recur (conj deps fdep) rdeps))
        (conj deps fdep))
      deps)))

(defn box-add
  [proj & adeps]
  (p-repos (fn [p] (tag-repo-projects p)))
  (doseq [:let [deps (fold-args-as-meta adeps)]
          dep deps
          :let [full-projs (if (.contains (str dep) "/")
                             [dep]
                             (resolve-short-proj (pr-str dep) (all-projects)))
                full-projs (map symbol full-projs)
                repo-infos (mapcat #(newest-voom-ver-by-spec % (meta dep)) full-projs)]]
    (case (count repo-infos)
      0 (throw (ex-info "Could not find matching projects" {:dep dep}))
      1 (box-repo-add (first repo-infos))
      (do
        (print "Multiple projects / locations match" (str \" dep \"\:))
        (print-repo-infos repo-infos)))))

(defn box-remove
  [proj & args]
  (doseq [a args
          :let [prjs (resolve-short-proj a (all-boxes))]]
    (if (= 1 (count prjs))
      (let [box-root (find-box)
            link (adj-path box-root (first prjs))
            repo (adj-path box-root task-dir (first prjs))]
        (safe-delete-repo repo link))
      (do
        (print (str "Cannot remove '" a "', multiple matches:"))
        (doseq [p prjs]
          (print (str " " p)))
        (println)))))

(declare voom)
(defn box
  [proj & args]
  (let [[^String ver ^String pwd ^String ififo ^String ofifo & rargs] args
        _ (assert (= "1" ver))
        fpwd (File. pwd)
        fofifo (future (-> ofifo FileOutputStream. OutputStreamWriter.))
        fififo (future (-> ififo FileInputStream.))]
    (binding [*pwd* fpwd
              *ofifo* @fofifo
              *ififo* @fififo]
      (.read *ififo* (byte-array 5))
      (apply voom proj rargs)
      ;; TODO formalize break/exit handling
      (fcmd "break"))))

;; === lein entrypoint ===

;; TODO: Consider revamping these entry points. Separate top-level
;; lein commands?  Separate lein plugins?
(defn ^:no-project-needed voom
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
  (let [[kw-like more-args] (split-with #(re-find #"^:" %) args)
        kargset (set (map edn/read-string kw-like))
        sargs (map edn/read-string more-args)
        long-sha (kargset :long-sha)
        new-project (delay (update-proj-version project long-sha))]
    ;; TODO throw exception if upstream doesn't contain this commit :no-upstream
    (cond
     (:print kargset) (println (:version @new-project))
     (:parse kargset) (prn (ver-parse (first more-args)))
     (:find-box kargset) (prn (find-box))
     (:box kargset) (apply box project more-args)
     (:box-add kargset) (apply box-add project (map edn/read-string more-args))
     (:box-remove kargset) (apply box-remove project more-args)
     (:retag-all-repos kargset) (time (p-repos (fn [p] (clear-voom-tags p) (tag-repo-projects p))))
     (:freshen kargset) (freshen project)
     (:build-deps kargset) (build-deps project)
     :else (if (and (dirty-wc? (:root @new-project))
                    (not (:insanely-allow-dirty-working-copy kargset)))
             (lmain/abort "Refusing to continue with dirty working copy. (Hint: Run 'git status')")
             (lmain/resolve-and-apply @new-project more-args)))))
