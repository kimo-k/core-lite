(ns core.lite
  "Lite-mode-safe replacements for clojure.core functions that pull in
  PersistentHashMap, PersistentVector, Range, or other types incompatible
  with :lite-mode + :elide-to-string ClojureScript builds.

  Require with an alias: [core.lite :as 🪶]
  github.com/kimo-k/core-lite"
  (:refer-clojure :exclude [assoc update assoc-in update-in select-keys range
                            atom])
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

#?(:clj
   (defn update-in [& args] (apply core/update-in args))
   :cljs
   (defn update-in
     "Drop-in for cljs.core/update-in in :lite-mode builds. Only 3-arity [m path f]
  and 4-arity [m path f x] are provided — variadic would pull in apply infrastructure."
     ([m path f]
      (if-let [k (first path)]
        (assoc m k (update-in (get m k {}) (next path) f))
        (f m)))
     ([m path f x]
      (if-let [k (first path)]
        (assoc m k (update-in (get m k {}) (next path) f x))
        (f m x)))))

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

#?(:cljs
   (deftype LiteAtom [state meta validator watches]
     Object
     (equiv [this other]
       (-equiv this other))

     IAtom

     IEquiv
     (-equiv [o other] (identical? o other))

     IDeref
     (-deref [_] state)

     IMeta
     (-meta [_] nil)

     IWatchable
     (-notify-watches [this oldval newval]
       ;; reduce-kv via IKVReduce — avoids doseq's ChunkedSeq + MapEntry destructure
       (reduce-kv (fn [_ k f] (f k this oldval newval) nil) nil watches))
     (-add-watch [this key f]
       (set! (.-watches this)
             (if (nil? watches)
               {key f}                   ; variable keys → HashMapLite (lighter)
               (-assoc watches key f)))
       this)

     (-remove-watch [this key]
       (when-not (nil? watches)
         (set! (.-watches this) (-dissoc watches key))))

     IReset
     (-reset! [this new-value]
       (let [old-value state]
         (set! (.-state this) new-value)
         (when-not (nil? watches)
           (-notify-watches this old-value new-value))
         new-value))

     ISwap
     (-swap! [this f]     (-reset! this (f state)))
     (-swap! [this f x]   (-reset! this (f state x)))
     (-swap! [this f x y] (-reset! this (f state x y)))

     IHash
     (-hash [this] (goog/getUid this))))

#?(:clj
   (defn atom [init] (core/atom init))
   :cljs
   (defn atom
     "Drop-in for cljs.core/atom in :lite-mode builds. Returns a LiteAtom with
  only :state and :watches fields (no :meta, no :validator) and a reduce-kv-based
  -notify-watches (no doseq → no ChunkedSeq leak).

  LiteAtom implements IReset and ISwap (in addition to cljs.core/Atom's protocols)
  so cljs.core/swap!, reset!, add-watch, remove-watch, deref all dispatch correctly
  via protocols. ISwap is 2/3/4-arity — no variadic (avoids apply infrastructure)."
     [x]
     (LiteAtom. x nil nil nil)))
