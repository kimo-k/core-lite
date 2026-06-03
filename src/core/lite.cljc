(ns core.lite
  "Lite-mode-safe replacements for clojure.core functions that pull in
  PersistentHashMap, PersistentVector, Range, or other types incompatible
  with :lite-mode + :elide-to-string ClojureScript builds.

  Require with an alias: [core.lite :as 🪶]
  github.com/kimo-k/core-lite"
  (:refer-clojure :exclude [assoc update assoc-in select-keys range])
  #?(:clj (:require [clojure.core :as core])))

#?(:clj
   (defn assoc [x k v] (core/assoc x k v))
   :cljs
   (defn assoc
     "Drop-in for cljs.core/assoc in :lite-mode builds. Dispatches via -assoc
  protocol, avoiding the PersistentArrayMap DCE root the standard 3-arity creates.
  Only 3-arity (single k/v) — variadic assoc still pulls in that root."
     [x k v] (-assoc x k v)))

#?(:clj
   (defn update [& args] (apply core/update args))
   :cljs
   (defn update
     "Drop-in for cljs.core/update in :lite-mode builds. Only 3-arity [m k f]
  and 4-arity [m k f x] are provided — variadic would pull in apply infrastructure.
  For more extra args use (assoc m k (f (get m k) x y z)) directly.
  On CLJ delegates to clojure.core/update and supports all arities."
     ([m k f]   (assoc m k (f (get m k))))
     ([m k f x] (assoc m k (f (get m k) x)))))

#?(:clj
   (defn assoc-in [m path v] (core/assoc-in m path v))
   :cljs
   (defn assoc-in
     "Drop-in for cljs.core/assoc-in in :lite-mode builds. Recursive via -assoc
  protocol. Missing intermediate keys are created as empty ObjMap ({}) rather than
  PersistentArrayMap. Path keys other than keywords/strings will promote those
  intermediate maps to HashMapLite."
     [m path v]
     (if-let [k (first path)]
       (-assoc m k (assoc-in (get m k {}) (next path) v))
       v)))

(defn select-keys
  "Drop-in for cljs.core/select-keys in :lite-mode builds. Loop-based; no lazy
  seq or transient overhead. Returns the same map type as {} (ObjMap in CLJS).
  Missing keys are silently omitted — same behaviour as clojure.core/select-keys."
  [m keyseq]
  (loop [ret {} keys (seq keyseq)]
    (if keys
      (let [k     (first keys)
            entry (get m k ::not-found)]
        (recur (if (not= entry ::not-found) (assoc ret k entry) ret)
               (next keys)))
      ret)))

(defn walk
  "Drop-in for clojure.walk/walk in :lite-mode builds. Handles vectors, maps,
  and sets — does NOT descend into lists or lazy seqs (use mapv/map for those).
  Preserves lite types: a VectorLite in produces a VectorLite out, an ObjMap in
  produces an ObjMap out."
  [inner outer form]
  (cond
    (vector? form) (outer (mapv inner form))
    (map? form)    (outer (reduce-kv (fn [m k v] (assoc m (inner k) (inner v))) {} form))
    (set? form)    (outer (reduce #(conj %1 (inner %2)) #{} form))
    :else          (outer form)))

(defn postwalk
  "Drop-in for clojure.walk/postwalk in :lite-mode builds. Bottom-up traversal
  using walk. Inherits walk's limitation: does not descend into lists or lazy seqs."
  [f form]
  (walk #(postwalk f %) f form))

#?(:clj
   (defn range
     "On CLJ: delegates to clojure.core/range, returning a lazy seq."
     ([end] (core/range end))
     ([start end] (core/range start end))
     ([start end step] (core/range start end step)))
   :cljs
   (defn range
     "Drop-in for cljs.core/range in :lite-mode builds. Returns an eager VectorLite
  rather than a lazy seq, avoiding Range/IntegerRange/RangeIterator in the build.

  Edge cases vs cljs.core/range:
  - (range n) where n <= 0 → [] (not an empty lazy seq)
  - (range start end) where start >= end → []
  - (range start end step) where step <= 0 → infinite loop, don't do it
  - Floating-point step: works but beware accumulating rounding error"
     ([end]
      (loop [i 0 acc []]
        (if (>= i end) acc (recur (inc i) (conj acc i)))))
     ([start end]
      (loop [i start acc []]
        (if (>= i end) acc (recur (inc i) (conj acc i)))))
     ([start end step]
      (loop [i start acc []]
        (if (>= i end) acc (recur (+ i step) (conj acc i)))))))
