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
        plugins  (do                              ;; TODO define once?
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

(defn ->content [msg]
   (get-in msg [:value :content :content]))

(defn get-id [^js db]
  (:id (parse-json (.whoami db))))


;; Publish to database 

(defn publish! [uid {:keys [content type] :as message}]
  (if-let [^js db (get @db-conns uid)]    
    (.publish db (clj->js message) 
              (fn [err msg]
                (if err
                  (bus/dispatch! bus/msg-ch :error {:uid uid :message err})
                  (bus/dispatch! bus/msg-ch :response {:uid uid :message (js->clj msg)}))))
    (bus/dispatch! bus/msg-ch  :error {:uid uid :message "Unable to get server with User-id"})))

(defn private-publish! [uid {:keys [content type] :as message} recipients]
  (if-let [^js db (get @db-conns uid)]
    (.private.publish db
                      (clj->js {:type type :text content}) 
                      (clj->js recipients) ;array of hashes    
                      (fn [err msg]
                        (if err
                          (bus/dispatch! bus/msg-ch :error {:uid uid :message err})
                          (bus/dispatch! bus/msg-ch :response {:uid uid :message (js->clj msg)}))))
    (bus/dispatch! bus/msg-ch {:uid uid :error "Unable to get server with User-id"})))

;; Feed stream
(defn feed [uid]
  (if-let [^js db (get @db-conns uid)]
    (pull (.createFeedStream db #js {:reverse true})
          (.collect pull  
                    (fn [err msg]
                      (if err
                        (bus/dispatch! bus/msg-ch :error {:uid uid :message err})
                        (bus/dispatch! bus/msg-ch :response {:uid uid :message (parse-json msg)})))))
    (bus/dispatch! bus/msg-ch :error {:uid uid :message (str "Unable to get server with User-id: " uid )})))

;; get message
(defn get-message [uid msg-id]
  (if-let [^js db (get @db-conns uid)]
    (.get db msg-id
          (fn [err msg] 
            (if err
              (bus/dispatch! bus/msg-ch :error {:uid uid :message err})
              (bus/dispatch! bus/msg-ch :response {:uid uid :message (js->clj msg)}))))))

;; Queries
(defn query! [uid query]
  (if-let [^js db (get @db-conns uid)]
    (pull (.query.read db (clj->js query))
          (.collect pull  (fn [err ary] (if err
                                          (bus/dispatch! bus/msg-ch :error {:uid uid :message err})
                                          (bus/dispatch! bus/msg-ch :feed {:uid uid :message (parse-json ary)})))))
    (bus/dispatch! bus/msg-ch :error {:uid uid :message (str "Unable to get server with User-id: " uid )}))) 


(defn db-collect [uid source-fn]
  "collects values from source-fn which is passed a db connection and returns an array of objects"
  (if-let [^js db (get @db-conns uid)]
    (pull (source-fn db)
          (.collect pull (fn [err ary] (if err
                                         (bus/dispatch! bus/msg-ch :error {:uid uid :message (parse-json err)})
                                         (bus/dispatch! bus/msg-ch :feed {:uid uid :message (parse-json ary)})))))
    (bus/dispatch! bus/msg-ch :error {:uid uid :message (str "Unable to get server with User-id: " uid )})))

(defn db-drain [uid source-fn]
   (if-let [^js db (get @db-conns uid)]
    (pull (source-fn db)
          (.drain pull 
                  ;; this gets run on each object in stream
                  (fn op? [val] (bus/dispatch! bus/msg-ch :feed {:uid uid :message (parse-json val)}))
                  ;; this gets run when stream runs out
                  (fn done? [val] (bus/dispatch! bus/msg-ch :feed {:uid uid :message (str "Feed closed: " (parse-json val))}))))
    (bus/dispatch! bus/msg-ch :error {:uid uid :message (str "Unable to get server with User-id: " uid )})))


(defn query-collect! [uid qry] (db-collect uid (fn [^js db] (.query.read db (clj->js qry)))))
(defn query-drain! [uid qry] (db-drain uid (fn [^js db] (.query.read db (clj->js qry)))))


;; Blobs
(comment

  (defn blobs-get [uid hash-id cb-fn]
    (if-let [^js db (get @db-conns uid)]
      (pull (.blobs.get db hash-id)
            (.collect pull (fn [err values] (if err (println "Error getting blob: " err)
                                                (cb-fn values)))))))

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
             (fn [{:keys [uid msg-id]}]
               (get-message uid msg-id)))

(bus/handle! bus/msg-bus :query
             (fn [{:keys [uid msg]}]
               (query-drain! uid msg)))




