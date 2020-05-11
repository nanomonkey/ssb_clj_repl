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

(def conns (atom {}))

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
  (publish (:server @conns) message))

(defn private-publish [db message recipients]
  (.private.publish db 
                    (clj->js {:type "post" :text message}) 
                    (clj->js recipients) ;array of hashes
                    (fn [err msg] (if err (println "Error:" err) 
                                      (println "private message posted")))))

;; Queries
(defn query-read [db query return-chan]
  "returns contents of query response to return channel"
  (pull (.query.read db query)
        (.collect pull  (fn [err ary] (if err (js/console.log err) 
                                          (put! return-chan ary)))))) 


(defn query! [search]
  (query-read (:server @conns) 
              (clj->js search)
              (:send-ch @conns)))
