(ns server.scratch)

;; CRUT messages
{:create (fn [{:keys [uid type content]}] (publish! uid {:type type :val val}))
 :update (fn [{:keys [uid id changes]}] (publish! uid {:type :update 
                                                       :root id 
                                                       :content changes}))
 :tombstone (fn [{:keys [uid id]}] (publish! uid {:type :tombstone :root id}))}

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

(defn chan->pull
  "Convert a channel into a pull-stream source"
  [ch]
  (fn [end f]
    (if end
      (f end)
      (take! ch
             (fn [v]
               (if (nil? v) ; then channel has been closed
                 (f true)   ; and we should tell the pull-stream so (only once)
                 (f nil v)  ; otherwise pass on the value from the channel
                 ))))))


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

(defn cb->chs [error-chan return-chan]
  (fn [err msg] (if err (>! err error-chan)
                    (>! msg return-chan))))

(defn pull->chans
  "Convert a pull-stream source into a channel"
  ([source] (pull->chan (chan) source))
  ([error-ch return-ch source]
   (source nil (fn read [err val]
                 (if err
                   (go 
                     (put! error-ch err))
                   (go
                     (put! return-ch val
                           #(if %
                              (source nil read)))))))))

(defn chan->pull
  "Convert a channel into a pull-stream source"
  [ch]
  (fn [end f]
    (if end
      (f end)
      (take! ch
             (fn [v]
               (if (nil? v) ; then channel has been closed
                 (f true)   ; and we should tell the pull-stream so (only once)
                 (f nil v)  ; otherwise pass on the value from the channel
                 ))))))

(defn read-file->chan [path] 
  "returns channel with file contents"
  (let [c (chan)]
    (.readFile fs path "utf8" (fn [err data] (go (>! c data)))) 
    c)) 

(defn create-read-stream [path]
  (let [out (chan)
        stream (.createReadStream fs path)]
    (.on stream "close" #(close! out))
    (.on stream "data" #(put! out %))
    out))
