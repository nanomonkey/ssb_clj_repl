(ns server.ssb
  (:require [cljs.core.async :refer (chan put! take! close! timeout >! <!)]
            [goog.object :as gobj]
            ["ssb-server" :as ssb-server]
            ["ssb-server/plugins/master" :as ssb-master]
            ["ssb-gossip" :as ssb-gossip]
            ["ssb-replicate" :as ssb-replicate]
            ["ssb-backlinks" :as ssb-backlinks]
            ["ssb-about" :as ssb-about]
            ["ssb-query" :as ssb-query]
            ["ssb-keys" :as ssb-keys]
            ["ssb-config/inject" :as ssb-config]
            ["fs" :as fs]
            ["pull-stream" :as pull]
            ["flumeview-reduce" :as fv-reduce]
            ["flumeview-query" :as fv-query]
            ["flumedb" :as flumedb])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))


(declare server)

;; Setup Server
(defn create-secret-key [filename] 
  "Creates secret key file if one doesn't alread exist at filename path."
  (. ssb-keys loadOrCreateSync filename))

(defn start-server []
  "returns db connection"
  (let [config (ssb-config "/.ssb" nil)
        plugins  (do
                   (.use ssb-server ssb-master)
                   (.use ssb-server ssb-gossip)
                   (.use ssb-server ssb-replicate)
                   (.use ssb-server ssb-query)
                   (.use ssb-server ssb-backlinks)
                   (.use ssb-server ssb-about))
        server (ssb-server config)]
    server))

;; Publish to database
(defn publish [db {:keys [content type]}]
  "publish with full options of type and recipients"
  (.publish db (clj->js (conj {:type type 
                               :content content})) 
            (fn [err msg]
              (if err 
                (println "error: " err)
                (println "message published:" msg)))))

(defn publish! [message]
  (publish server message))

(defn private-publish [db message recipients]
  (.private.publish db (clj->js {:type "post" :text message :recps recipients})
                    (fn [err msg] (if err (println "Error:" err) 
                                      (println "private message posted")))))

(comment
  ;; Blobs

(comment

  (defn read-file->chan [path] 
    "returns channel with file contents"
    (let [fs (node/require "fs") 
          c (chan)]
      (.readFile fs path "utf8" (fn [err data] (go (>! c data)))) 
      c)) 

  (defn create-read-stream [path]
    (let [out (async/chan)
          stream (.createReadStream fs path)]
      (.on stream "close" #(async/close! out))
      (.on stream "data" #(async/put!  out %))
      out))

  (defn want-blob [db blob-id])
  (defn get-blob [db blob-id]
    (pull
     (.get db blob-id)
     (.collect pull (fn [err blob] (if err (println "Error: " err)
                                       (slurp blob)))))))
)
