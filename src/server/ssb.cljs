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
            ["ssb-blobs" :as ssb-blobs]
            ["ssb-serve-blobs" :as ssb-serve-blobs]
            ["ssb-serve-blobs/id-to-url" :as blob-id->url]
            ["ssb-config/inject" :as ssb-config]
            ["fs" :as fs]
            ["pull-stream" :as pull]
            ["stream-to-pull-stream" :as to-pull]
            ["flumeview-reduce" :as fv-reduce]
            ["flumeview-query" :as fv-query]
            ["flumedb" :as flumedb])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   ;[server.macros :refer [db-sync db-async db-collect db-drain]]
                   ))

(defonce db-conns (atom {})) ;;ssb-db connections keyed by uid

;; Setup Server
(defn create-secret-key [filename] 
  "Creates secret key file if one doesn't alread exists at filename path."
  (. ssb-keys loadOrCreateSync filename))

(defonce plugins (do                           
                   (.use ssb-server ssb-master)
                   (.use ssb-server ssb-gossip)
                   (.use ssb-server ssb-replicate)
                   (.use ssb-server ssb-query)
                   (.use ssb-server ssb-backlinks)
                   (.use ssb-server ssb-about)
                   (.use ssb-server ssb-blobs)
                   (.use ssb-server ssb-serve-blobs)))

(defn start-server [^String config-path]
  "returns db connection"
  (let [config (ssb-config config-path nil)
        server (ssb-server config)]
    server))

;; Utility functions 
(defn parse-json [msg] 
  (js->clj msg :keywordize-keys true))

(defn decrypt [uid msg]
  (if-let [^js db (get @db-conns uid)]
    (.private.unbox db msg
                    (fn [err, content]
                      (if err
                        (bus/dispatch! bus/msg-ch :error {:uid uid :message err})
                        (bus/dispatch! bus/msg-ch :response {:uid uid :message (js->clj content)}))))
    (bus/dispatch! bus/msg-ch :error {:uid uid :message "Unable to get server with User-id"})))

(defn get-id [uid]
  (if-let [^js db (get @db-conns uid)]
    (:id (parse-json (.whoami db)))))

(defn destructure [content]
  (let [type (gobj/get content "type")]
    (case type
      "post" {:type "post" :text (str (gobj/get content "text"))}
      "contact" {:type "contact"
                 :follow (gobj/get content "following")
                 :blocking (gobj/get content "blocking")
                 :contact (gobj/get content "contact")}
      "vote" {:type "vote"
              :link (gobj/getValueByKeys content #js ["vote" "link"])
              :value (gobj/getValueByKeys content #js ["vote" "value"])
              :expression (gobj/getValueByKeys content #js ["vote" "expression"])
              } 
      (js->clj content)))) 


(defn flatten-msg [msg]
  (let [key (gobj/get msg "key")
        content (gobj/getValueByKeys msg #js ["value" "content"])
        author (gobj/getValueByKeys msg #js ["value" "author"])
        encrypted? (string? content)]
    (conj {:key key
           :author author}
          (if encrypted?
            (do
              (println content)
              {:type "encrypted"
               :content content})
            (destructure content)))))

;; Publish to database 

(defn publish! [uid contents]
  (if-let [^js db (get @db-conns uid)]    
    (.publish db (clj->js contents) 
              (fn [err msg]
                (if err
                  (bus/dispatch! bus/msg-ch :error {:uid uid :message err})
                  (bus/dispatch! bus/msg-ch :response {:uid uid :message (js->clj msg)}))))
    (bus/dispatch! bus/msg-ch  :error {:uid uid :message "Unable to get server with User-id"})))

(defn private-publish! [uid contents recipients]
  (if-let [^js db (get @db-conns uid)]
    (.private.publish db
                      (clj->js contents) 
                      (clj->js recipients) ;array of hashes    
                      (fn [err msg]
                        (if err
                          (bus/dispatch! bus/msg-ch :error {:uid uid :message err})
                          (bus/dispatch! bus/msg-ch :response {:uid uid :message (parse-json msg)}))))
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


;(defn feed [uid] (db-collect uid :response createFeedStream (clj->js {:reverse true})))
  
;; get message or entry
(defn get-message [uid msg-id]
  (if-let [^js db (get @db-conns uid)]
    (.get db msg-id
          (fn [err msg] 
            (if err
              (bus/dispatch! bus/msg-ch :error {:uid uid :message err})
              (bus/dispatch! bus/msg-ch :response {:uid uid :message (parse-json msg)}))))))

;; Queries
(defn query! [uid query]
  (if-let [^js db (get @db-conns uid)]
    (pull (.query.read db (clj->js query))
          (.collect pull  (fn [err ary] (if err
                                          (bus/dispatch! bus/msg-ch :error {:uid uid :message err})
                                          (bus/dispatch! bus/msg-ch :feed {:uid uid :message  ary})))))
    (bus/dispatch! bus/msg-ch :error {:uid uid :message (str "Unable to get server with User-id: " uid )}))) 


(defn db-collect
  "collects values from source-fn which is passed a db connection and returns an array of objects"
  ([uid source-fn] (db-collect uid source-fn :feed))
  ([uid source-fn bus-tag]
   (if-let [^js db (get @db-conns uid)]
     (pull (source-fn db)
           (.collect pull (fn [err ary] 
                            (if err
                              (bus/dispatch! bus/msg-ch :error {:uid uid :message (parse-json err)})
                              (bus/dispatch! bus/msg-ch bus-tag {:uid uid :message (parse-json ary)})))))
     (bus/dispatch! bus/msg-ch :error {:uid uid :message (str "Unable to get server with User-id: " uid )}))))

(defn db-drain 
  ([uid source-fn] (db-drain uid source-fn :feed))
  ([uid source-fn bus-tag]
   (if-let [^js db (get @db-conns uid)]
     (pull (source-fn db)
           (.drain pull 
                   ;; this gets run on each object in stream
                   (fn op? [val] (bus/dispatch! bus/msg-ch bus-tag {:uid uid :message (parse-json val)}))
                   ;; this gets run when stream runs out
                   (fn done? [val] (when val 
                                     (bus/dispatch! bus/msg-ch bus-tag {:uid uid :message (str "Feed closed: " (parse-json val))})))))
     (bus/dispatch! bus/msg-ch :error {:uid uid :message (str "Unable to get server with User-id: " uid )}))))

 
(defn db-sync [uid source-fn]
  (if-let [^js db (get @db-conns uid)]
    (source-fn db)))

(defn db-async [uid source-fn parameters cb]
  (if-let [^js db (get @db-conns uid)]
    (source-fn db parameters cb)
    (bus/dispatch! bus/msg-ch :error {:uid uid :message (str "Unable to get server with User-id: " uid )})))

(defn query-collect! [uid qry] (db-collect uid (fn [^js db] (.query.read db (clj->js qry)))))

(defn query-drain! [uid qry] (db-drain uid (fn [^js db] (.query.read db (clj->js qry))) :feed))

(defn query-explain! [uid qry] 
  (bus/dispatch! bus/msg-ch :feed {:uid uid :message (str (parse-json (db-sync uid (fn [^js db] (.query.explain db (clj->js qry))))))}))

(defn about [uid id]
  (db-async uid 
            (fn [^js db parameters cb-fn] (.about.latestValue db parameters cb-fn)) 
            (clj->js {:key "name" :dest id}) 
            #(bus/dispatch! bus/msg-ch :response {:uid uid :message %2})))

(defn about-name [uid id] 
  (if-let [^js db (get @db-conns uid)]
    (.about.latestValue db (clj->js {:key "name" :dest id}) 
                       (fn [err, name] (bus/dispatch! bus/msg-ch :response {:uid uid :message (str id ": " name )})))
  (bus/dispatch! bus/msg-ch :error {:uid uid :message (str "Unable to get server with User-id: " uid )})))

(defonce contacts (atom {}))

(defn find-name! [uid id]
  (db-async uid
            (fn [^js db parameters cb] (.about.latestValue db parameters cb))
            (clj->js {:key "name" :dest id})
            (fn [err name] (if err 
                             (bus/dispatch! bus/msg-ch :err {:uid uid 
                                                             :message (str "Unable to find name for id: " id)})
                               (do
                                 (swap! contacts assoc id name)
                                 (bus/dispatch! bus/msg-ch :name {:uid uid :message {id name}}))))))

(defn lookup-name! [uid id]
  (if-let [name (get @contacts id)]
    (bus/dispatch! bus/msg-ch :name {:uid uid :message {id name}})
    (find-name! uid id)))

(defn manifest! [uid] (db-sync uid (fn [^js db] (.manifest db))))

(defn latest! [uid] (db-sync uid (fn [^js db] (.latestSequence db))))

(defn user-feed! [uid user-id]
  (db-collect uid (fn [^js db] (.createHistoryStream db #js {:id user-id})) :feed))

(defn msg-thread! [uid message-id]
  (db-collect (fn [^js db] (.links db #js {:values true :rel 'root' :dest message-id})) :feed))


;; Blobs

(defn list-blobs [uid]
  (db-collect uid (fn [^js db] (.blobs.ls db (clj->js {:meta true}))) :response))

(defn display-blobs! [uid]
  (db-drain uid (fn [^js db] (.blobs.ls db (clj->js {:meta true}))) :display))

(defn list-blobs! [uid cb-fn]
  (if-let [^js db (get @db-conns uid)]
    (pull (.blobs.ls db)
           (.collect pull (fn [err values] (if err 
                                            (bus/dispatch! bus/msg-ch :error {:uid uid :message err})
                                            (cb-fn values)))))))

(defn has-blob? [uid blob-id]
  (if-let [^js db (get @db-conns uid)] 
    (.blobs.has db blob-id 
                (fn [err value] (if err (bus/dispatch! bus/msg-ch :err {:uid uid :message (str "Error: " err)})
                                    (bus/dispatch! bus/msg-ch :response {:uid uid :message (parse-json value)}))))
    (bus/dispatch! bus/msg-ch :error {:uid uid :message (str "Unable to get server with User-id: " uid )})))

(defn blobs-get! [uid hash-id cb-fn]
  (if-let [^js db (get @db-conns uid)]
    (pull (.blobs.get db (clj->js hash-id))
          (.collect pull (fn [err values] (if err 
                                            (bus/dispatch! bus/msg-ch :error {:uid uid :message err})
                                            (cb-fn values)))))))


(defn blobs-want [{:as request :keys [uid hash-id cb-fn]}]
  (if-let [^js db (get @db-conns uid)]
    (.blobs.want db hash-id (fn [err] (if err (println err)
                                         (blobs-get! uid hash-id cb-fn))))
    (bus/dispatch! bus/msg-ch :error {:uid uid :message (str "Unable to get server with User-id: " uid )})))

(defn add-blob! 
  ([uid file-path]
   (add-blob! uid 
             file-path 
             (fn [err hash] (bus/dispatch! bus/msg-ch :response {:uid uid :message {:blob-added hash}}))))
  ([uid file-path cb-fn]
   (if-let [^js db (get @db-conns uid)]
     (pull (.source to-pull (.createReadStream fs file-path))
           (.blobs.add db cb-fn))
     (bus/dispatch! bus/msg-ch :error {:uid uid :message (str "Unable to get server with User-id: " uid )}))))

(defn serve-blobs! 
  ([uid blob-id cb-fn]             ;;public image
   (cb-fn (blob-id->url blob-id)))
  ([uid blob-id unbox-key cb-fn]   ;; private image needs key to decrypt
   (cb-fn (blob-id->url blob-id (clj->js {:unbox unbox-key})))))


;; Message bus Handlers
;; possible tags: :create, :update, :delete, :query, :get, :respond, :private

(defonce message-handlers 
  {:server-start (fn [[uid config]] (swap! db-conns assoc uid (start-server config)))
   :add-message (fn [{:keys [uid msg]}] (publish! uid {:text msg :type "post"}))
   :private-message (fn [{:keys [uid msg rcps]}] (private-publish! uid {:text msg :mentions rcps} rcps))
   :get (fn [{:keys [uid msg-id]}] (get-message uid msg-id))
   :query (fn [{:keys [uid msg]}](query-drain! uid msg))
   :query-explain (fn [{:keys [uid msg]}] (query-explain! uid msg))
   :lookup-name (fn [{:keys [uid id]}] (lookup-name! uid id))
   :add-file (fn [{:keys [uid file]}] (add-blob! uid file))
   :get-blob (fn [{:keys [uid blob-id]}]
               (serve-blobs! uid blob-id #(bus/dispatch! bus/msg-ch :blob {:uid uid :message (js->clj %)})))
   :list-blobs (fn [{:keys [uid]}] (list-blobs! uid #(bus/dispatch! bus/msg-ch :feed {:uid uid :message %})))
   :display-blobs (fn [{:keys [uid]}] (display-blobs! uid))
   :thread (fn [{:keys [uid message-id]}] (msg-thread! uid message-id))
   :user-feed (fn [{:keys [uid user-id]}] (user-feed! uid user-id))
   :test (fn [message] (prn message))})

(doall (map (fn [[k v]] (bus/handle! bus/msg-bus k v)) message-handlers))
 
