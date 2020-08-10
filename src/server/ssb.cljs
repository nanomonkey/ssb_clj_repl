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


(defn start-server [^String config-path]
  "returns db connection"
  (let [config (ssb-config config-path nil)
        plugins  (do
                   (.use ssb-server ssb-master)
                   (.use ssb-server ssb-gossip)
                   (.use ssb-server ssb-replicate)
                   (.use ssb-server ssb-query)
                   (.use ssb-server ssb-backlinks)
                   (.use ssb-server ssb-about))
        server (ssb-server config)]
    server))

(defn parse-json [msg] 
  (js->clj msg :keywordize-keys true))

(defn get-id [db]
  (:id (parse-json (.whoami db))))

;; Pull stream to message bus
(defn pull->bus
  "take a pull-stream source and drop it into a message bus channel"
  ([uid source parameters] (pull->bus uid bus/msg-ch :response source))
  ([uid msg-ch response-key source]
   (if-let [server (get @db-conns uid)]
     (pull
      (source server (fn read [err val]
                       (if err
                         (bus/dispatch! bus/msg-ch {:uid uid  :error err})
                         (bus/dispatch! bus/msg-ch {:uid uid response-key val})))))
     (bus/dispatch! bus/msg-ch  {:uid uid :error "Unable to get server with User-id"}))))


;; Publish to database 

(defn publish! [uid {:keys [content type] :as message}]
  (if-let [server (get @db-conns uid)]    
    (.publish server (clj->js message) 
              (fn [err msg]
                (if err
                  (bus/dispatch! bus/msg-ch {:uid uid :error err})
                  (bus/dispatch! bus/msg-ch {:uid uid :response (js->clj msg)}))))
    (bus/dispatch! bus/msg-ch  {:uid uid :error "Unable to get server with User-id"})))

(defn private-publish! [uid {:keys [content type] :as message} recipients]
  (if-let [server (get @db-conns uid)]
    (.private.publish server
                      (clj->js {:type type :text content}) 
                      (clj->js recipients) ;array of hashes    
                      (fn [err msg]
                        (if err
                          (bus/dispatch! bus/msg-ch {:uid uid :error err})
                          (bus/dispatch! bus/msg-ch {:uid uid :response (js->clj msg)}))))
    (bus/dispatch! bus/msg-ch {:uid uid :error "Unable to get server with User-id"})))

;; Feed stream
(defn feed [uid]
  (if-let [server (get @db-conns uid)]
    (pull (.createFeedStream server #js {:reverse true})
          (.collect pull  
                    (fn [err msg]
                      (if err
                        (bus/dispatch! bus/msg-ch :error {:uid uid :messag err})
                        (bus/dispatch! bus/msg-ch :response {:uid uid :message (js->clj msg)})))))
    (bus/dispatch! bus/msg-ch :error {:uid uid :message (str "Unable to get server with User-id: " uid )})))

;; get message
(defn get-message [uid msg-id]
  (.get (get @db-conns uid) msg-id
        (fn [err msg] 
          (if err
            (bus/dispatch! bus/msg-ch :error {:uid uid :message err})
            (bus/dispatch! bus/msg-ch :response {:uid uid :message (js->clj msg)})))))

;; Queries
(defn query [uid query]
  (if-let [server  (get @db-conns uid)]
    (pull (.query.read server query)
          (.collect pull  (fn [err ary] (if err
                                          (bus/dispatch! bus/msg-ch :error {:uid uid :message err})
                                          (bus/dispatch! bus/msg-ch :query-response {:uid uid :message ary}))))))) 


(defn db-call [uid {:keys [call-fn parameters]}]
  (if-let [db (get @db-conns uid)]
    (pull (call-fn db parameters)
          (.collect pull (fn [err stream] (if err
                                            (bus/dispatch! bus/msg-ch :error {:uid uid :message err})
                                            (bus/dispatch! bus/msg-ch :response {:uid uid :message stream})))))))


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
             (fn [[uid config]]
               (swap! db-conns assoc uid (start-server config))))

(bus/handle! bus/msg-bus :add-message
             (fn [{:keys [uid msg]}]
               (publish! uid {:content msg :type "post"})))

(bus/handle! bus/msg-bus :get
             (fn [uid msg-id]
               (get-message uid msg-id)))

