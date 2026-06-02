(ns lite.core)

#?(:clj
   (defn assoc* [x k v] (assoc x k v))
   :cljs
   (defn assoc* [x k v] (-assoc x k v)))

#?(:clj
   (defn update* [& args] (apply update args))
   :cljs
   (defn update*
     ([m k f]   (assoc* m k (f (get m k))))
     ([m k f x] (assoc* m k (f (get m k) x)))))

#?(:clj
   (defn assoc-in* [m path v] (assoc-in m path v))
   :cljs
   (defn assoc-in* [m path v]
     (if-let [k (first path)]
       (-assoc m k (assoc-in* (get m k) (next path) v))
       v)))

(defn select-keys* [m keyseq]
  (loop [ret {} keys (seq keyseq)]
    (if keys
      (let [k     (first keys)
            entry (get m k ::not-found)]
        (recur (if (not= entry ::not-found) (assoc* ret k entry) ret)
               (next keys)))
      ret)))

(defn walk [inner outer form]
  (cond
    (vector? form) (outer (mapv inner form))
    (map? form)    (outer (reduce-kv (fn [m k v] (assoc* m (inner k) (inner v))) {} form))
    (set? form)    (outer (reduce #(conj %1 (inner %2)) #{} form))
    :else          (outer form)))

(defn postwalk [f form]
  (walk #(postwalk f %) f form))
