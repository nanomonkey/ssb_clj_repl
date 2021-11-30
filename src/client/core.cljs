(ns client.core
  (:require
   [clojure.string  :as str]
   [clojure.edn :as edn]
   [cljs.core.async :as async  :refer (<! >! put! chan)]
   [taoensso.encore :as encore :refer ()]
   [taoensso.timbre :as timbre :refer-macros (tracef debugf infof warnf errorf)]
   [taoensso.sente  :as sente  :refer (cb-success?)])

  (:require-macros
   [cljs.core.async.macros :as asyncm :refer (go go-loop)]))

;;;; Utils for logging output and errors to on-screen console

(def output-el (.getElementById js/document "output"))
(defn ->output! [fmt & args]
  (let [msg (apply encore/format fmt args)]
    (timbre/debug msg)
    (aset output-el "value" (str "• " (.-value output-el) "\n" msg))
    (aset output-el "scrollTop" (.-scrollHeight output-el))))

(->output! "ClojureScript appears to have loaded correctly.")

(def errors-el (.getElementById js/document "errors"))
(defn ->errors-put! [fmt & args]
  (let [msg (apply encore/format fmt args)]
    (timbre/debug msg)
    (aset errors-el "value" (str "• " (.-value errors-el) "\n" msg))
    (aset errors-el "scrollTop" (.-scrollHeight errors-el))))

(->errors-put! "-=[ Error Messages ]=-")

(def feed-el (.getElementById js/document "feed"))
(defn ->feed! [fmt & args]
  (let [msg (apply encore/format fmt args)]
    (timbre/debug msg)
    (aset feed-el "value" (str "• " (.-value feed-el) "\n" msg))
    (aset feed-el "scrollTop" (.-scrollHeight feed-el))))

(->feed! "-=[ Feed ]=-")

(defn blob->DataURL [blob cb]
  "untested translation of"
  (doto (js/FileReader.)
    (.onload (fn [e] (cb (.-result (.target e)))))
    (.readAsDataURL blob)))  

(def image-el (.getElementById js/document "image"))

(defn ->image! [image-URL]
  (aset image-el "src" image-URL))

(def images-div (.getElementById js/document "images"))

(defn add-image [name src width height alt]
  (let [image (.createElement js/document "img")]
    (debugf "creating image with src=%s" src)
    (doto image
      (aset "src" src)
      (aset "name" name)
      (aset "width" width)
      (aset "height" height)
      (aset "alt" alt))
    (.appendChild images-div image)))

;; Sente Channels
(let [{:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket-client!
       "/chsk" ; Must match server Ring routing URL
       {:type   :auto
        :packer :edn})]

  (def chsk       chsk)
  (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
  (def chsk-send! send-fn) ; ChannelSocket's send API fn
  (def chsk-state state)   ; Watchable, read-only atom
)

;;;; Sente event handlers

(defmulti -event-msg-handler
  "Multimethod to handle Sente `event-msg`s"
  :id ; Dispatch on event-id
  )

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event]}]
  (->output! "Unhandled event: %s" event))

(defmethod -event-msg-handler :chsk/state
  [{:as ev-msg :keys [?data]}]
  (if (= ?data {:first-open? true})
    (->output! "Channel socket successfully established!")
    (->output! "Channel socket state change: %s" ?data)))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  (let [[?uid ?csrf-token ?handshake-data] ?data]
    (->output! "Handshake: %s" ?data)))

(defmulti chsk-recv (fn [id ?data] id))

(defmethod -event-msg-handler :chsk/recv
  [{:as ev-msg :keys [?data]}]
  ;(->output! "Push event from server: %s" ?data)
  (chsk-recv (?data 0) (?data 1)))

;; recieved message handlers
(defmethod chsk-recv :post-event
  [id {:as ev-msg :keys [?data]}]
  (let [[?uid ?csrf-token ?handshake-data ?msg] ?data]
    (->output! "Message Posted: %s" ?msg)))

(defmethod chsk-recv :ssb/error-event
  [id {:as ?data :keys [message]}]
  (->errors-put! "Error: %s" message))

(defmethod chsk-recv :ssb/response
  [id {:as ?data :keys [message]}]
  (->output! "SSB-response: %s" message))

(defmethod chsk-recv :ssb/feed
  [id {:as ?data :keys [message]}]
  (->feed! "* %s" message))

(defmethod chsk-recv :ssb/contact-name
  [id {:as ?data :keys [message]}]
  (->feed! "* %s" message))

(defmethod chsk-recv :ssb/blob
  [id {:as ?data :keys [message]}]
  (->image! message))

(defmethod chsk-recv :ssb/display
  [id {:as ?data :keys [message]}]
  
  (add-image "name" message 200 200 "alt"))


;;;; Sente event router (our `event-msg-handler` loop)

(defonce router_ (atom nil))

(defn  stop-router! [] (when-let [stop-f @router_] (stop-f)))
(defn start-router! []
  (stop-router!)
  (reset! router_
          (sente/start-client-chsk-router!
           ch-chsk event-msg-handler)))


;;;; UI events ;;;;

;; Post message to SSB
(defn btn-post-click [ev]
  (let [message (.-value (.getElementById js/document "input-post"))]
    (if (str/blank? message)
      (js/alert "Please enter a message first")
      (do 
        (->output! "Message Posted: %s" message)
        (chsk-send!  [:ssb/post {:msg (str message)}] 5000
                     (fn [cb-reply] (->output! "Posted reply: %s" cb-reply)))))))

(when-let [target-el (.getElementById js/document "btn-post")]
  (.addEventListener target-el "click" btn-post-click))


(defn btn-get-messages-click [ev]
  (let [count (int (.-value (.getElementById js/document "input-message-cnt")))
        query  {:query [{:$filter {:value {:content {:type "post"}}}}] :limit count :reverse true}]
    (chsk-send! [:ssb/query {:msg query}] 5000 
                (fn [cb-reply] (->output! "Posted reply: %s" cb-reply)))))

(when-let [target-el (.getElementById js/document "btn-get-messages")]
  (.addEventListener target-el "click" btn-get-messages-click))

(defn btn-query-click [ev]
  (let [map      (edn/read-string (.-value (.getElementById js/document "query-map")))
        filter   (edn/read-string (.-value (.getElementById js/document "query-filter")))
        reduce   (edn/read-string (.-value (.getElementById js/document "query-reduce")))
        count    (int (.-value (.getElementById js/document "query-limit")))
        reverse? (boolean (.-value (.getElementById js/document "query-reverse")))
        query  {:query (into [] (remove nil? [(when map {:$map map})
                                     (when filter {:$filter filter}) 
                                     (when reduce {:$reduce reduce})]))
                :limit count 
                :reverse reverse?
                }]
    (chsk-send! [:ssb/query {:msg query}] 5000 
                (fn [cb-reply] (->output! "Posted reply: %s" cb-reply)))))

(when-let [target-el (.getElementById js/document "btn-query")]
  (.addEventListener target-el "click" btn-query-click))

(defn btn-query-explain-click [ev]
  (let [map      (edn/read-string (.-value (.getElementById js/document "query-map")))
        filter   (edn/read-string (.-value (.getElementById js/document "query-filter")))
        reduce   (edn/read-string (.-value (.getElementById js/document "query-reduce")))
        count    (int (.-value (.getElementById js/document "query-limit")))
        reverse? (boolean (.-value (.getElementById js/document "query-reverse")))
        query  {:query (remove nil? [(when map {:$map map})
                                     (when filter {:$filter filter}) 
                                     (when reduce {:$reduce reduce})])
                :limit count 
                :reverse reverse?
                }]
    (chsk-send! [:ssb/query-explain {:msg query}] 5000 
                (fn [cb-reply] (->output! "Posted reply: %s" cb-reply)))))

(when-let [target-el (.getElementById js/document "btn-query-explain")]
  (.addEventListener target-el "click" btn-query-explain-click))

(defn btn-get-contact-click [ev]
  (let [id (.-value (.getElementById js/document "input-contact-id"))]
    (chsk-send! [:ssb/lookup-name {:msg id}] 500
                (fn [cb-reply] (->output! "Posted reply: %s" cb-reply)))))

(when-let [target-el (.getElementById js/document "btn-get-contact")]
  (.addEventListener target-el "click" btn-get-contact-click))

(defn btn-add-file-change [ev]
  (let [file (aget ev "target" "files" 0)  ;; take only the first file
        reader (js/FileReader.)
        onload (fn [onload-ev] (chsk-send! [:ssb/add-file {:file (.. onload-ev -target -result)}] 500
                                           (fn [cb-reply] (->output! "File added: %s" cb-reply))))]
    (aset reader "onload" onload)
    (when file
      (debugf "Filereader loaded: %s" (.-name file))
      (.readAsDataURL reader file))))

(when-let [target-el (.getElementById js/document "input-file")]
  (.addEventListener target-el "change" btn-add-file-change))

(defn btn-add-file-click-ajax [ev]
  "untested alternative"
  (let [files (.-files (.getElementById js/document "input-file"))]
    (sente/ajax-lite "/upload"
                         {:method :post
                          :headers {:x-csrf-token (:csrf-token @chsk-state)}
                          :files files}
                         (fn [ajax-resp]
                           (->output! "File upload response: %s" ajax-resp)))))

(when-let [target-el (.getElementById js/document "btn-add-file")]
  (.addEventListener target-el "click" btn-add-file-click-ajax))

(defn btn-input-blob-id-click [ev]
  (let [blob-id (.-value (.getElementById js/document "input-blob-id"))]
    (chsk-send! [:ssb/serve-blob {:blob-id blob-id}] 500
                (fn [cb-reply] (->feed! cb-reply)))))

(when-let [target-el (.getElementById js/document "btn-input-blob-id")]
  (.addEventListener target-el "click" btn-input-blob-id-click))

(defn btn-display-blobs-click [ev]
  (chsk-send! [:ssb/display-blobs {}] 500
              (fn [cb-reply] (->feed! cb-reply))))

(when-let [target-el (.getElementById js/document "btn-display-blobs")]
  (.addEventListener target-el "click" btn-display-blobs-click))

(defn btn-list-blobs-click [ev]
  (chsk-send! [:ssb/list-blobs {}] 500
              (fn [cb-reply] (->feed! cb-reply))))

(when-let [target-el (.getElementById js/document "btn-list-blobs")]
  (.addEventListener target-el "click" btn-list-blobs-click))


(defn btn-login-click [ev]
  (let [user-id (.-value (.getElementById js/document "input-login"))
        config (.-value (.getElementById js/document "input-ssb-config"))] 
    (if (or (str/blank? user-id) (str/blank? config))
      (js/alert "Please enter a user-id, and configuration file-path first")
      (do
        (->output! "Logging in with user-id %s, and config file %s" user-id config)

            ;;; Here we'll trigger an Ajax POST request that resets our server-side session. Then we ask
            ;;; our channel socket to reconnect, thereby picking up the new  session
        (sente/ajax-lite "/login"
                         {:method :post
                          :headers {:x-csrf-token (:csrf-token @chsk-state)}
                          :params {:user-id    (str user-id)
                                   :config     (str config)}}
                         (fn [ajax-resp]
                           (->output! "Ajax login response: %s" ajax-resp)
                           (let [login-successful? true ; Your logic here
                                 ]
                             (if-not login-successful?
                               (->output! "Login failed")
                               (do
                                 (->output! "Login successful")
                                 (sente/chsk-reconnect! chsk))))))))))

(when-let [target-el (.getElementById js/document "btn-login")]
  (.addEventListener target-el "click" btn-login-click))


;;;; Init stuff

(defn start! [] (start-router!))

(defonce _start-once (start!))
