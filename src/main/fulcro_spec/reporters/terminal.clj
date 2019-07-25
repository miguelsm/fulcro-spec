(ns fulcro-spec.reporters.terminal
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as s]
    [clojure.test :as t]
    [colorize.core :as c]
    [io.aviso.exception :as pretty]
    [fulcro-spec.diff :as diff]
    [fulcro-spec.reporter :as base]))

;; ensure test runners don't see fulcro-spec's extended events
(defmethod t/report :begin-specification [_])
(defmethod t/report :end-specification [_])
(defmethod t/report :begin-behavior [_])
(defmethod t/report :end-behavior [_])
(defmethod t/report :begin-manual [_])
(defmethod t/report :end-manual [_])
(defmethod t/report :begin-provided [_])
(defmethod t/report :end-provided [_])

(def cfg
  "Terminal reporter configuration. You may swap against this atom to set configuration parameters to modify how
   reports are generated.

   Options (defaults are shown) are:

   ```
   {:fail-only?     false ; do not show passing tests
    :color?         true  ; highlight things using terminal colors
    :diff-hl?       false ; print the (unexpected) actual
    :diff?          true  ; Show a diff?

    :diff-list?     false ; print a list of all of the data diffs
    :num-diffs      1     ; max number of diffs if using diff-list?

    :full-diff?     true  ; always show full expected and actual?
    :frame-limit    100   ; Max stack frames on an exception
    :quick-fail?    true  ; stop on the first failure

    ;; Affects output of original assertions
    :*print-level*  3   ; See clojure *print-level*.
    :*print-length* 3}  ; See clojure *print-length*
   ```

   Typically you'd do something like this to change an option:

   ```
   (swap! fulcro-spec.reporters.terminal/cfg assoc :color? false)
   ```
   "
  (atom
    {:fail-only?     false
     :color?         true
     :diff-hl?       false
     :diff?          true

     :diff-list?     false
     :num-diffs      1

     :full-diff?     true
     :frame-limit    100
     :quick-fail?    true
     :*print-level*  3
     :*print-length* nil}))

(defn env [k] (get @cfg k))

(defn merge-cfg!
  "For use in the test-refresh repl to change configuration on the fly.
  Single arity will show you the possible keys you can use.
  Passing an empty map will show you the current values."
  ([] (println "Valid cfg keys: " (set (keys @cfg))))
  ([new-cfg]
   (doseq [[k v] new-cfg]
     (assert (contains? @cfg k)
       (str "Invalid key '" k "', try one of these " (set (keys @cfg)))))
   (swap! cfg merge new-cfg)))

(defn color-str [status & strings]
  (let [color?        (env :color?)
        status->color {:normal (comp c/bold c/yellow)
                       :diff   (comp c/bold c/cyan)
                       :where  (comp c/bold c/white)}
        color-fn      (or (and color? (status->color status))
                        (case status
                          :diff/impl (fn [[got exp]]
                                       ((comp c/bold c/inverse)
                                        (str exp " != " got)))
                          nil)
                        (condp (fn [p x] (pos? (p x 0))) status
                          :fail c/red
                          :error c/red
                          :pass c/green
                          c/reset))]
    (apply color-fn strings)))

(defn pad [pad n] (apply str (repeat n pad)))

(defn space-level [level]
  (pad " " (* 2 level)))

(defn print-throwable [e]
  (print (pretty/format-exception e {:frame-limit (env :frame-limit)}))
  (some-> (.getCause e) print-throwable))

(defn pretty-str [s n]
  (as-> (with-out-str (pprint s)) s
    (clojure.string/split s #"\n")
    (apply str (interpose (str "\n" (pad " " (inc (* 2 n)))) s))))

(defn print-highligted-diff [diff actual]
  (let [process-diff-elem (fn [d]
                            (let [{:keys [got exp]} (diff/extract d)]
                              (color-str :diff/impl [got exp])))
        patched-actual    (diff/patch actual diff process-diff-elem)]
    (println (str \" (color-str :diff/impl ["EXP" "ACT"]) \" \:)
      (pretty-str patched-actual 2))))

(defn print-diff [diff actual print-fn]
  (when (and (seq diff) (env :diff?) (diff/diff? diff))
    (println)
    (when (env :diff-list?)
      (let [num-diffs (env :num-diffs)
            num-diffs (if (number? num-diffs)
                        num-diffs (count diff))]
        (println (color-str :diff "diffs:"))
        (doseq [d (take num-diffs diff)]
          (let [{:keys [exp got path]} (diff/extract d)]
            (when (seq path)
              (println (str "-  at: " path)))
            (println "  exp:" (pretty-str exp 6))
            (println "  got:" (pretty-str got 3))
            (println)))
        (when (< num-diffs (count diff))
          (println "&" (- (count diff) num-diffs) "more..."))))
    (when (and (env :diff-hl?) (coll? actual))
      (print-highligted-diff diff actual))))

(defn ?ellipses [s]
  (binding [*print-level*  (env :*print-level*)
            *print-length* (env :*print-length*)]
    (try (apply str (drop-last (with-out-str (pprint (read-string s)))))
         (catch Throwable _ s))))

(defn parse-message [m]
  (try (->> (read-string (str "[" m "]"))
         (sequence (comp (map str) (map base/fix-str)))
         (zipmap [:actual :arrow :expected]))
       (catch Error _ {:message m})))

(defn print-message [m print-fn]
  (print-fn (color-str :normal "ASSERTION:"))
  (let [{:keys [arrow actual expected message]} (parse-message m)]
    (print-fn)
    (if message
      (print-fn message)
      (do
        (print-fn (color-str :normal (str (?ellipses actual))))
        (print-fn (color-str :normal (str arrow)))
        (print-fn (color-str :normal (str (?ellipses expected))))))
    (print-fn)))

(defn print-extra [e print-fn]
  (print-fn (color-str :normal "    extra:") e))

(defn print-where [w s print-fn]
  (let [status->str {:error "Error"
                     :fail  "Failed"}]
    (->> (s/replace w #"G__\d+" "")
      (str (status->str s) " in ")
      (color-str :where)
      print-fn)))

(defn print-test-result [{:keys [message where status actual
                                 expected extra throwable diff]}
                         print-fn print-level]
  (print-fn)
  (-> (or where "Unknown") (print-where status print-fn))
  (when (and (= status :error)
          (instance? Throwable actual))
    (print-throwable actual))
  (when (and throwable
          (not (instance? Throwable actual)))
    (print-throwable throwable))
  (-> (or message "Unmarked Assertion") (print-message print-fn))
  (when (env :full-diff?)
    (print-fn "   Actual:" (pretty-str actual (+ 5 print-level)))
    (print-fn " Expected:" (pretty-str expected (+ 5 print-level))))
  (some-> extra (print-extra print-fn))
  (some-> diff (print-diff actual print-fn))
  (when (env :quick-fail?)
    (throw (ex-info "" {::stop? true}))))

(def when-fail-only-keep-failed
  (filter #(if-not (env :fail-only?)
             true
             (or (pos? (:fail (:status %) 0))
               (pos? (:error (:status %) 0))))))

(defn print-test-item [test-item print-level]
  (t/with-test-out
    (let [status  (:status test-item)
          failed? (and (map? status) (or (pos-int? (:fail status)) (pos-int? (:error status))))]
      (if (= "unmarked" (:name test-item))
        (when failed?
          (println (space-level print-level)
            (color-str (:status test-item)
              "UNMARKED ASSERTION/TEST")))
        (println (space-level print-level)
          (color-str (:status test-item)
            (:name test-item)))))
    (into []
      (comp (filter (comp #{:fail :error} :status))
        (map #(print-test-result % (->> print-level inc space-level
                                     (partial println))
                (inc print-level))))
      (:test-results test-item))
    (into []
      (comp when-fail-only-keep-failed
        (map #(print-test-item % (inc print-level))))
      (:test-items test-item))))

(defn print-namespace [make-tests-by-namespace]
  (t/with-test-out
    (println)
    (println (color-str (:status make-tests-by-namespace)
               "Testing " (:name make-tests-by-namespace)))
    (into []
      (comp when-fail-only-keep-failed
        (map #(print-test-item % 1)))
      (:test-items make-tests-by-namespace))))

(defn print-report-data
  "Prints the current report data from the report data state and applies colors based on test results"
  [reporter]
  (do
    (defmethod print-method Throwable [e w]
      (print-method (c/red e) w))
    (t/with-test-out
      (let [{:keys [namespaces test pass fail error]} (base/get-test-report reporter)]
        (println "Running tests for:" (map :name namespaces))
        (try (->> namespaces
               (into [] when-fail-only-keep-failed)
               (sort-by :name)
               (mapv print-namespace))
             (catch Exception e
               (when-not (->> e ex-data ::stop?)
                 (print-throwable e))))
        (println "\nRan" test "tests containing"
          (+ pass fail error) "assertions.")
        (println fail "failures," error "errors.")))
    (remove-method print-method Throwable)
    reporter))

(def this (base/make-test-reporter))

(def fulcro-report
  (base/fulcro-report {:test/reporter this}
    (comp base/reset-test-report! print-report-data :test/reporter)))
