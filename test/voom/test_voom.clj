(ns voom.test-voom
  (:require [loom.alg-generic :as lag]
            [leiningen.voom.shabam :as shabam]
            [leiningen.voom :as voom]
            [clojure.set :as set]
            [clojure.test.check :as tc]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]))

(defn dag-samples-gen
  [dag percent]
  (let [dag-size (count dag)
        sample-count (int (* percent dag-size))]
    (gen/bind (apply gen/tuple
                     ;; May collide but this is fine.
                     (repeat (* 2 sample-count) (gen/choose 0 dag-size)))
              (fn [samples]
                (gen/tuple (gen/return dag) (gen/return samples))))))

(defn gen-dag
  ([] (gen-dag [#{}] 10))
  ([nodes] (gen-dag [#{}] nodes))
  ([dag-so-far nodes]
     (gen/bind (gen/frequency [[80 (gen/return 1)]
                               [19 (gen/return 2)]
                               [1 (gen/return 0)]])
               (fn [parent-count]
                 (gen/bind (gen/such-that
                            (fn [& parents]
                              (when (not (empty? parents))
                                (apply distinct? parents)))
                            (apply gen/tuple
                                   (repeat (min (count dag-so-far) parent-count)
                                           (gen/choose 0 (dec (count dag-so-far))))))
                           (fn [parents]
                             (if (< 0 nodes)
                               (gen-dag (conj dag-so-far (set parents))
                                        (dec nodes))
                               (dag-samples-gen dag-so-far 1/2))))))))


(defn anc-model-new [] {})

(defn anc-model-add
  [anc-model node & parents]
  (let [ancs (reduce set/union
                     (map #(get anc-model %)
                          parents))
        ancs (into ancs parents)
        ancs (disj ancs nil)]
    (assoc anc-model node ancs)))

(defn anc-model-anc?
  [anc-model childer parenter]
  (boolean
   (get
    (get anc-model childer)
    parenter)))

(defn anc->anc-model
  [ancestry]
  (let [anc-nodes (lag/ancestry-nodes ancestry)]
    (zipmap anc-nodes
            (map #(set (lag/ancestors ancestry %)) anc-nodes))))

(def dag-similarity-props
  (prop/for-all [[dag samples] (gen/bind (gen/choose 0 100)
                                         (fn [dag-size]
                                           (gen-dag dag-size)))]
                (let [anc (reduce (fn [a [i ps]]
                                    (apply lag/ancestry-add a i (seq ps)))
                                  (lag/ancestry-new)
                                  (map-indexed vector dag))
                      shabam (reduce (fn [a [i ps]]
                                       (apply shabam/shabam-add a i (seq ps)))
                                     (shabam/shabam-new)
                                     (map-indexed vector dag))
                      anc-model (reduce (fn [a [i ps]]
                                          (apply anc-model-add a i (seq ps)))
                                        (anc-model-new)
                                        (map-indexed vector dag))
                      samp-pairs (partition 2 samples)
                      all-nodes (range (count dag))
                      anc-to-model (anc->anc-model anc)]
                  (and
                   (= anc-model anc-to-model)
                   (every?
                    (fn [s]
                      (and
                       (= (shabam/sha-ancestors shabam s all-nodes)
                          (voom/sha-ancestors anc s all-nodes))
                       (= (shabam/sha-successors shabam s all-nodes)
                          (voom/sha-successors anc s all-nodes))))
                    samples)
                   (every?
                    (fn [[a b]]
                      (and
                       (= (lag/ancestor? anc b a)
                          (anc-model-anc? anc-model b a))
                       (= (lag/ancestor? anc a b)
                          (anc-model-anc? anc-model a b))))
                    samp-pairs)))))

(defspec ^:test-check-fast dag-similarity-100
  100
  dag-similarity-props)

(defspec ^:test-check-slow dag-similarity-2000
  2000
  dag-similarity-props)
