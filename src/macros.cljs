(ns macros.core
  (:require ["nodejs" :as js]))

(defn error? [x]
  (instance? js/Error x))

(defn throw-err [e]
  (when (error? e) (throw e))
  e)

(defmacro <? [ch]
  `(throw-err (<! ~ch)))

(comment
 also consider: https://github.com/gilbertw1/cljs-asynchronize
)
