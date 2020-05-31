(ns server.ssb
  (:require [cljs.core.async :refer (chan put! take! close! timeout >! <!)]
            [server.message-bus :as bus]
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

(def db-conns (atom {}))

;; Setup Server
(defn create-secret-key [filename] 
  "Creates secret key file if one doesn't alread exist at filename path."
  (. ssb-keys loadOrCreateSync filename))

(defn start-server
  "returns db connection"
  ([] (start-server "/.ssb"))
  ([config-path] (start-server config-path [ssb-master ssb-gossip ssb-replicate 
                                            ssb-query ssb-backlinks ssb-about]))
  ([config-path plugins]
   (let [config (ssb-config config-path nil)
         _  (apply #(.use ssb-server %) plugins)
         server (ssb-server config)]
     server)))


(defn parse-json [msg] 
  (js->clj msg :keywordize-keys true))

(defn get-id [db]
  (:id (parse-json (.whoami db))))

;; Pull stream to message bus
(defn pull->bus
  "take a pull-stream source and drop it into a message bus channel"
  ([source] (pull->bus bus/msg-ch :response source))
  ([msg-ch response-key source]
   (source nil (fn read [err val]
                 (if err
                   (bus/dispatch! :error err)
                   (bus/dispatch! response-key val))))))


;; Publish to database
(defn publish! [db {:keys [content type]}]
  "publish with full options of type and recipients"
  (.publish db (clj->js (conj {:type type 
                               :content content})) 
            (fn [err msg]
              (if err
                (bus/dispatch! bus/msg-ch :error err)
                (bus/dispatch! bus/msg-ch :post-event (js->clj msg))))))

(defn private-publish [db message recipients]
  (.private.publish db 
                    (clj->js {:type "post" :text message}) 
                    (clj->js recipients) ;array of hashes
                    (fn [err msg] (if err (println "Error:" err) 
                                      (println "private message posted")))))

;; get message
(defn get-message [db msg-id]
  (pull
   (.get db msg-id)
   (.collect pull (fn [err msg]
                    (if err
                      (bus/dispatch! bus/msg-ch :error err)
                      (bus/dispatch! bus/msg-ch :reply {:id msg-id   ;;necessary?
                                                          :msg (js->clj msg)}))))))


;; Queries
(defn query [db query]
  (pull (.query.read db query)
        (.collect pull  (fn [err ary] (if err
                                        (bus/dispatch! bus/msg-ch :error err)
                                        (bus/dispatch! bus/msg-ch :query-response ary)))))) 


;; Blobs
(comment

  (defn blobs-get [db hash-id cb-fn]
    (pull (.blobs.get db hash-id)
          (.collect pull (fn [err values] (if err (println "Error getting blob: " err)
                                              (cb-fn values))))))
  (defn blobs-want [db hash-id cb-fn]
    (blobs.want db hash-id (fn [err] (if err (println err)
                                         (blobs-get db hash-id cb-fn)))))

)


;; Message bus Handlers
;; :create, :update, :delete, :query, :get, :respond, :private

(bus/handle! bus/msg-bus :server-start
             (fn [user-id]
               (swap! db-conns assoc user-id (start-server))))

(bus/handle! bus/msg-bus :add-message
             (fn [{:keys [uid msg]}]
               (if-let [server (get @db-conns uid)]
                 (println "UID: " uid "content: " content)
                 (println "Error getting server")))) ;; (publish! server {:content msg :type "post")

(bus/handle! bus/msg-bus :get
             (fn [uid msg-id]
               (get-message (get @db-conns uid) msg-id)))

