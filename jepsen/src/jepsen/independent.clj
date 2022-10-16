(ns jepsen.independent
  "Some tests are expensive to check--for instance, linearizability--which
  requires we verify only short histories. But if histories are short, we may
  not be able to sample often or long enough to reveal concurrency errors. This
  namespace supports splitting a test into independent components--for example
  taking a test of a single register and lifting it to a *map* of keys to
  registers."
  (:require [jepsen.util :as util :refer [map-kv]]
            [jepsen.store :as store]
            [jepsen.checker :refer [merge-valid check-safe Checker]]
            [jepsen.generator :as gen]
            [jepsen.generator.context :as ctx]
            [clojure.tools.logging :refer :all]
            [clojure.core.reducers :as r]
            [clojure.pprint :refer [pprint]]
            [dom-top.core :refer [bounded-pmap]]))

(def dir
  "What directory should we write independent results to?"
  "independent")

(defn tuple
  "Constructs a kv tuple"
  [k v]
  (clojure.lang.MapEntry. k v))

(defn tuple?
  "Is the given value generated by an independent generator?"
  [value]
  (instance? clojure.lang.MapEntry value))

(defn sequential-generator
  "Takes a sequence of keys [k1 k2 ...], and a function (fgen k) which, when
  called with a key, yields a generator. Returns a generator which starts with
  the first key k1 and constructs a generator gen1 via (fgen k1), returns
  elements from gen1 until it is exhausted, then moves to k2.

  The generator wraps each :value in the operations it generates in a [k1
  value] tuple.

  fgen must be pure."
  [keys fgen]
  ; AHHHH LOOK HOW MUCH SIMPLER THIS IS
  (map (fn [k]
         (gen/map (fn wrap-pair [op]
                     (assoc op :value (tuple k (:value op))))
                   (fgen k)))
       keys))

(defn group-threads
  "Given a group size and pure generator context, returns a collection of
  collections of threads, each per group."
  [n ctx]
  ; Sanity checks
  (let [group-size   n
        thread-count (ctx/all-thread-count ctx)
        group-count (quot thread-count group-size)]
              (assert (<= group-size thread-count)
                      (str "With " thread-count " worker threads, this"
                           " jepsen.concurrent/concurrent-generator cannot"
                           " run a key with " group-size " threads concurrently."
                           " Consider raising your test's :concurrency to at least "
                           group-size "."))

              (assert (= thread-count (* group-size group-count))
                      (str "This jepsen.independent/concurrent-generator has "
                           thread-count
                           " threads to work with, but can only use "
                           (* group-size group-count)
                           " of those threads to run " group-count
                           " concurrent keys with " group-size
                           " threads apiece. Consider raising or lowering the"
                           " test's :concurrency to a multiple of " group-size
                           ".")))
  (->> (gen/all-threads ctx)
       sort
       (partition n)))

(defn make-group->threads
  "Given a group size and pure generator context, returns a vector where each
  element is the set of threads in the group corresponding to that index."
  [n ctx]
  (->> (group-threads n ctx)
       (mapv set)))

(defn make-thread->group
  "Given a group size and pure generator context, returns a map of threads to
  groups."
  [n ctx]
  (into {}
        (for [[group threads] (map-indexed vector (group-threads n ctx))
              thread threads]
          [thread group])))

(defn tuple-gen
  "Wraps a generator so that it returns :value [k v] tuples for :invoke ops."
  [k gen]
  (gen/map (fn [op]
             (if (= :invoke (:type op))
               (assoc op :value (tuple k (:value op)))
               op))
            gen))

(defrecord ConcurrentGenerator
  [; n is the size of each group
   n
   ; fgen turns a key into a generator
   fgen
   ; group->threads is a vector mapping groups to sets of threads; lazily init.
   group->threads
   ; thread->group is a map which takes threads to groups. Lazily initialized.
   thread->group
   ; A vector of context filters, one for each group. We use these to speed up
   ; computing thread-restricted contexts for each group's generator. Lazily
   ; initialized.
   group->context-filter
   ; keys is our collection of remaining keys
   keys
   ; gens is a vector of generators, one for each thread group.
   gens]

  gen/Generator
  (op [this test ctx]
    ; (prn)
    ; (prn :op :=======================================)
    (let [; Figure out our thread<->group mappings and context filters
          group->threads (or group->threads (make-group->threads n ctx))
          group->context-filter (or group->context-filter
                                    (mapv ctx/make-thread-filter
                                          group->threads))
          thread->group  (or thread->group  (make-thread->group  n ctx))
          ; Lazily initialize our generators
          gens2 (or gens
                    (let [group-count (inc (reduce max 0 (vals thread->group)))
                          gens      (->> (take group-count keys)
                                         (map fgen)
                                         (mapv tuple-gen keys))]
                      ; Extend with nils if necessary
                      (into gens (repeat (- group-count (count gens)) nil))))
          ; If we consumed keys, update them.
          keys (if gens keys
                 (let [group-count (inc (reduce max 0 (vals thread->group)))]
                   (drop group-count keys)))
          ; What threads are open?
          free-threads (gen/free-threads ctx)
          ; What groups do they belong to?
          free-groups  (set (map thread->group free-threads))]

      ; (prn :free-threads free-threads)
      ; (prn :free-groups free-groups)

      ; We go through each free group, and find the soonest operation any of
      ; those groups can offer us.
      (loop [groups   free-groups
             keys     keys
             gens     gens2
             soonest  nil]
        ;(prn :----------)
        ;(prn :group (first groups))
        ;(prn :keys keys)
        ;(prn :gens gens)
        ;(prn :soonest-op soonest-op)
        (if-not (seq groups)
          ; We're done
          (if (:op soonest)
            ; We have an operation to yield
            [(:op soonest)
             (ConcurrentGenerator. n fgen group->threads thread->group
                                   group->context-filter
                                   keys (assoc gens (:group soonest)
                                               (:gen' soonest)))]
            ; We don't have an operation to yield given the current context,
            ; but some groups which weren't currently free might have ops to
            ; yield still. If there's a generator left... we're still pending.
            (when (some identity gens)
              [:pending (ConcurrentGenerator. n fgen group->threads
                                              thread->group
                                              group->context-filter keys
                                              gens)]))

          ; OK, let's consider this group
          (let [group (first groups)
                ; What's the generator for this group?
                gen   (nth gens group)
                ; We'll need a context for this group specifically
                ctx   ((group->context-filter group) ctx)
                ; OK, ask this gen for an op.
                [op gen'] (gen/op gen test ctx)
                ; If this generator is exhausted, we replace it.
                gens  (if op
                        gens
                        (assoc gens group
                               (when (seq keys)
                                 (let [k (first keys)]
                                   (tuple-gen k (fgen k))))))
                ; If we had to build a new generator, advance keys.
                keys (if op keys (next keys))]
            (if (or op (nil? (get gens group)))
              ; Either we generated an op, or we failed to generate one *and*
              ; there's no replacement generator, because we're out of keys.
              (recur (next groups)
                     keys
                     gens
                     (gen/soonest-op-map soonest
                                          (when op {:op     op
                                                    :group  group
                                                    :gen'   gen'
                                                    :weight (count
                                                              (group->threads
                                                                group))})))
              ; We didn't get an op, but we do still have a generator. Let's
              ; try again.
              (recur groups
                     keys
                     gens
                     soonest)))))))

  (update [this test ctx event]
    (let [process (:process event)
          thread  (gen/process->thread ctx process)
          group   (thread->group thread)]
      (ConcurrentGenerator.
        n fgen group->threads thread->group group->context-filter keys
        (update gens group gen/update test ctx event)))))

(defn concurrent-generator
  "Takes a positive integer n, a sequence of keys (k1 k2 ...) and a function
  (fgen k) which, when called with a key, yields a generator. Returns a
  generator which splits up threads into groups of n threads per key, and has
  each group work on a key for some time. Once a key's generator is exhausted,
  it obtains a new key, constructs a new generator from key, and moves on.

  Threads working with this generator are assumed to have contiguous IDs,
  starting at 0. Violating this assumption results in uneven allocation of
  threads to groups.

  Excludes the nemesis by design; only worker threads run here.

  Updates are routed to the generator which that thread is currently
  executing."
  [n keys fgen]
  (assert (pos? n))
  (assert (integer? n))
  ; There's a straightforward way to write this, which is to use gen/reserve
  ; to break things up into separate groups of threads, and have each group go
  ; through sequential-generator with e.g. modulo keys. The problem is
  ; that this leaves gaps in the key sequence, which can be annoying for users.
  ; Instead, we fold this into a custom generator.
  []
  (gen/clients
    (ConcurrentGenerator. n fgen nil nil nil keys nil)))

(defn history-keys
  "Takes a history and returns the set of keys in it."
  [history]
  (->> history
       (reduce (fn [ks op]
                 (let [v (:value op)]
                   (if (tuple? v)
                     (conj! ks (key v))
                     ks)))
               (transient #{}))
       persistent!))

(defn subhistory
  "Takes a history and a key k and yields the subhistory composed of all ops in
  history which do not have values with a differing key, unwrapping tuples to
  their original values."
  [k history]
  (->> history
       (keep (fn [op]
               (let [v (:value op)]
                 (cond
                   (not (tuple? v)) op
                   (= k (key v))    (assoc op :value (val v))
                   true             nil))))
       vec))

(defn checker
  "Takes a checker that operates on :values like `v`, and lifts it to a checker
  that operates on histories with values of `[k v]` tuples--like those
  generated by `sequential-generator`.

  We partition the history into (count (distinct keys)) subhistories. The
  subhistory for key k contains every element from the original history
  *except* those whose values are MapEntries with a different key. This means
  that every history sees, for example, un-keyed nemesis operations or
  informational logging.

  The checker we build is valid iff the given checker is valid for all
  subhistories. Under the :results key we store a map of keys to the results
  from the underlying checker on the subhistory for that key. :failures is the
  subset of that :results map which were not valid."
  [checker]
  (reify Checker
    (check [this test history opts]
      (let [ks       (history-keys history)
            results  (->> ks
                          (bounded-pmap
                            (fn [k]
                              (let [h (subhistory k history)
                                    subdir (concat (:subdirectory opts)
                                                   [dir k])
                                    results (check-safe
                                              checker test h
                                              {:subdirectory subdir
                                               :history-key  k})]
                                ; Write analysis
                                (store/with-out-file test [subdir
                                                           "results.edn"]
                                  (pprint results))

                                ; Write history
                                (store/with-out-file test [subdir
                                                           "history.edn"]
                                  (util/print-history prn h))

                                ; Return results as a map
                                [k results])))
                          (into {}))
            failures (->> results
                          (reduce (fn [failures [k result]]
                                    (if (:valid? result)
                                      failures
                                      (conj! failures k)))
                                  (transient []))
                          persistent!)]
        {:valid?   (merge-valid (map :valid? (vals results)))
         :results  results
         :failures failures}))))
