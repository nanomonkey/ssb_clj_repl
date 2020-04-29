(ns server.core
  (:require [cljs.core.async :refer (chan put! take! close! timeout >! <!)]
            [goog.object :as gobj]
            [server.ws :as ws]
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

;; Secret Keys and Config file generation
(defn create-secret-key [filename]  
  (. ssb-keys loadOrCreateSync filename))

#_(defonce secret-key (. ssb-keys loadSync  "/.ssb/secret"))


;; messages
(defn format-message 
  ([message] (format-message message "post"))
  ([message type] (clj->js {:type type :text message}))
  ([message type respond-to] (clj->js {:type type :text message :respond respond-to})))

(defn pub-message [db message]
  "publish a public text message"
  (.publish db (format-message message) (fn [err msg]
                                              (if err 
                                                (println "error: " err)
                                                (println "message published:" msg)))))

(defn publish [db {:keys [content type private-recipients]}]
  "publish with full options of type and recipients"
  (.publish db (clj->js (conj {:type type 
                               :content content}
                              (if private-recipients {:recps private-recipients}))) 
            (fn [err msg]
              (if err 
                (println "error: " err)
                (println "message published:" msg)))))


(defn post-reply [db text root branch mentions]
  (.publish db {:type "post" :content {:text text
                                      :root root
                                      :branch branch}}
            (fn [err msg]
              (if err 
                (println "error: " err)
                (println "message published:" msg)))))

(comment 
 ;; publish a message 
 ;;(pub-message server "Boom!")
 ;;(publish server {:content {:text "Kaploo!"} :type "post"})
 ;;(post-reply server "Baam!!" root branch mentions)
)

(defn parse-json [msg] 
  (js->clj msg :keywordize-keys true))

(defn get-id [server]
  (:id (parse-json (.whoami server))))


;;{ type: 'post', text: String, channel: String, root: MsgLink, branch: MsgLink|MsgLinks, recps: FeedLinks, mentions: Links }
;;{ type: 'post-edit', text: String, root: MsgLink, revisionRoot: MsgLink, revisionBranch: MsgLink, mentions: Links }
;;{ type: 'about', about: Link, name: String, image: BlobLink }
;;{ type: 'contact', contact: FeedLink, following: Bool, blocking: Bool }
;;{ type: 'vote', vote: { link: Ref, value: -1|0|1, reason: String } }
;;{ type: 'pub', pub: { link: FeedRef, host: String, port: Number } }


;; Flume-reduce
(def default-codec
  #js {:encode #(-> % js->clj clj->js js/JSON.stringify)
       :decode (fn [cache]
                 (let [data (-> cache js/JSON.parse)
                       val (.-value data)]
                   (do
                     (set! (.-value data) (js->clj val :keywordize-keys true))
                     data)))})

(def index {:name "test"
            :version 1
            :codec nil
            :reducer (fn [db msg] (msg) )
            :map-fn nil
            :initial-state {}})

(defn flume-view [db {:keys [name version reduce-fn map-fn codec initial-state] 
                          :as index}]
  "pass dictionary of index components and return the contents of a map-reduce on sbot flumeview"
  (._flumeUse db name (fv-reduce version reduce-fn map-fn codec initial-state)))

(defn feed [db]
  (pull (.createFeedStream db #js {:reverse true}) 
        (.collect pull (fn [err msg] (if err (js/console.log err) (js/console.log msg))))))

(defn user-feed [db user-id]
  (pull (.createHistoryStream db #js {:id user-id})
        (.collect pull (fn [err msg] (if err (js/console.log err) 
                                         (js/console.log  msg))))))


(comment
  (feed server)
  (user-feed server id)

)

(defn userfeed->chan [db user-id]
  (let [out (chan)
        user-feed (pull (.createHistoryStream db #js {:id user-id})
                        (.collect pull (fn [err msg] (if err (js/console.log err) 
                                                         (put! out msg)))))]
    out))

;; Pull-Stream to channels



(comment 
  (def error-chan (chan))
  (def msg-chan (chan))
  (def error-log-path "/errors.txt")

  (defn split-feed [db err-chan msg-chan]
    (pull (.createFeedStream db)
          (.collect pull (fn [err msg] (go (if err (put! err-chan err) 
                                             (put! msg msg-chan)))))))

  (defn capture-err [err-chan]
    (.writeFile fs  error-log  "utf8" (take! err-chan)))

)


(defn pull->chan
  "Convert a pull-stream source into a channel"
  ([source] (pull->chan (chan) source))
  ([ch source]
   (source nil (fn read [err val]
                 (if err
                   (close! ch)  ; TODO: really?
                   (go
                    (put! ch val
                          #(if %
                             (source nil read)
                             (close! ch)))))))
   ch))

(defn feed->ch [db]
   (pull->chan (.createFeedStream db #js {:reverse true})))

;(take! (feed-ch server) println)


(def saved-feed (atom nil))
(defn save-user-feed [db user-id]
  (pull (.createHistoryStream db #js {:id user-id})
        (.collect pull (fn [err msg] (if err (js/console.log err) 
                                         (reset! saved-feed msg))))))

;;Flume-view query
(def type-index #js {:key "typ" :value [["value" "content" "type"] ["timestamp"]]})

(defn add-index [index createStream]  ;;TODO figure out what a createStream is (server???)
  (.add fv-query index createStream))

(defn query-author-posts-index [author] 
  #js {:value {:author author :content {:type "post"}}})

(defn create-query-view [db query-name version opt]
  "update the version integer to rebuild index"
  (.use db query-name (fv-query version opt)))

;;SSB-about
(defn about-name [db id]
  (let [channel (chan)]
    (.about.socialValue db #js {:key "name" :dest id} 
                        (fn [err value] (if err (js/console.log err) 
                                            (put! channel value))))
    channel))
;(take! (about-name server id) println)

(defn latest-about [db id]
  (let [channel (chan)]
    (.about.latestValue db #js {:key "name" :dest id} 
                        (fn [err value] (if err (js/console.log err)
                                            (put! channel value))))
    channel))
;(take! (latest-about id) println)

(def names (atom {}))

(defn add-about [db id]
  "updates name from about-name channel"
  (take! (about-name db id) #(swap! names assoc id %)))

(defn lookup-name [db id]
  "returns name of a given id, if not found attempts to add it for later use"
   (get @names id (add-about db id)))

(defn type->chan [db type]
  (let [channel (chan)]
    (pull (.messagesByType db #js {:type type})
          (.collect pull (fn [err msg] (if err (js/console.log err) 
                                           (put! channel msg)))))
    channel))

(comment
 ;(def contacts (atom {}))
 ;(take! (type->chan server "contacts") #(reset! contacts %))
)
;;SSB-query plugin

(def last-10-posts (clj->js {:query [{:$filter {:value {:content {:type "post"}}}}] 
                             :limit 10 :reverse true}))

(def query-1 
    (clj->js [{:$filter {:value {:content {:channel {:$is "string"} :type "post"}}}}
              {:$reduce {:channel ["value" "content" "channel"]
                        :count {:$count true}
                        :timestamp {:$max ["value" "timestamp"]}}}
              {:$sort [["timestamp"] ["count"]]}]))

(def query-2 
  #js [{:$filter {:value {:content {:channel {:$is "string"} :type "post"}}}}
       {:$reduce {:channel ["value" "content" "channel"]
                  :count {:$count true}
                  :timestamp {:$max ["value" "timestamp"]}}}
       {:$sort [["timestamp"] ["count"]]}])

(defn query-read [db query]
  "returns channel with contents of query response"
  (let [c (chan)]
    (pull (.query.read db query)
          (.collect pull  (fn [err ary] (if err (js/console.log err) 
                                            (put! c ary)))))
    c)) 

;(take! (query-read server last-10-posts) println)

(defn query-flatten [db query]
  (pull (.query.read db query)
        (.collect pull  (fn [err ary] (js/console.log (parse-json ary))))))


(defn destructure-by-type [content type]
  "destructures content by known type, else uses js->clj recursive conversion"
  (case type
    "post" {:text (gobj/get content "text")}
    "contact" {:follow (gobj/get content "following")
               :blocking (gobj/get content "blocking")
               :contact (gobj/get content "contact")}
    (js->clj content))) 

(defn flatten-msg [msg]
  (let [key (gobj/get msg "key")
        content (gobj/getValueByKeys msg #js ["value" "content"])
        author (gobj/getValueByKeys msg #js ["value" "author"])]
    (if-let [content (gobj/getValueByKeys msg #js ["value" "content"])]
      (let [type (gobj/get content "type")]
        (conj {:key key
               :author author
               :type type}
              (destructure-by-type content type)))
      {:key key
       :author author
       :type "private"})))

(defn flatten-user-feed [db user-id]
  (pull (.createHistoryStream db #js {:id user-id})
        (.collect pull (fn [err msg] (if err (js/console.log err) 
                                         (js/console.log (flatten-msg msg)))))))


(defn friends-hop [db user-id]
  (.friends.hops db user-id (fn [err msg] (if err (js/console.log err) 
                                              (println msg)))))
;;(def manifest (.manifest server))
;;(def peers (.gossip.peers server #js {:id id}))

;; Links
(defn cb
 "attempt to abstract out callbacks"
  ([] (cb println))
  ([func] (fn [err msg] (if err (println "Error: " err) (func msg)))))

(defn threads [db message-id] 
  "doesn't quite work..."
  (pull (.links db #js{:values true :rel 'root' :dest message-id}) (cb)))


(def latest-message (atom ""))
(defn get-latest-message [db]
  (take! (query-read db
                     (clj->js {:query [{:$filter {:value {:content {:type "post"}}}}] 
                               :limit 1 :reverse true})) 
         #(reset! latest-message (first (parse-json %)))))

(defn thread-read [db message-id]
  "returns channel with contents of query response"
  (let [c (chan)]
    (pull (.links db #js{:values true :rel 'root' :dest message-id})
          (.collect pull  (fn [err ary] (if err (js/console.log err) 
                                            (put! c ary)))))
    c)) 


(defn channel-contents [channel]
  (let [contents (atom nil)]
    (while (not contents)
      (take! channel #(reset! contents (parse-json %))))
    @contents))


(defn read-ch [chan]
  (take! chan println)
  (println "------")
  (read-ch chan))

(defn start-server [config-directory]
  (let [config (ssb-config config-directory nil)
        plugins (do (.use ssb-server ssb-master)
                    (.use ssb-server ssb-gossip)
                    (.use ssb-server ssb-replicate)
                    (.use ssb-server ssb-query)
                    (.use ssb-server ssb-backlinks)
                    (.use ssb-server ssb-about))
        server (ssb-server config)
        id (get-id server)]
    (js/console.log "server started")
    (js/console.log "Logged in as:" id)
    {:server server
     :id id}))

;; Main Loop
(defn main [& args]
  (let [db-conn (start-server "/.ssb")
        server (:server db-conn)
        id (:id db-conn)
        error-ch (chan 5)
        msg-ch (chan 5)
        recd-ch (chan 5)
        send-ch (chan 5)]
    (ws/start!)))

(defn reload! []
  (js/console.log "re-starting server"))

;; REPL 
; After running 'M-x cider-connect'
; (shadow.cljs.devtools.api/nrepl-select :server)
; (in-ns 'server.core)
