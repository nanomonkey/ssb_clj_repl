(ns client.core
  (:require
   [clojure.string  :as str]
   [cljs.core.async :as async  :refer (<! >! put! chan)]
   [taoensso.encore :as encore :refer ()]
   [taoensso.timbre :as timbre :refer-macros (tracef debugf infof warnf errorf)]
   [taoensso.sente  :as sente  :refer (cb-success?)])

  (:require-macros
   [cljs.core.async.macros :as asyncm :refer (go go-loop)]))-

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

;; Sente Channnels
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
  (->output! "Push event from server: %s" ?data)
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
  (->feed! message))

(defmethod -event-msg-handler :search-result
 [{:as ev-msg :keys [?data]}]
  (let [[?uid ?csrf-token ?handshake-data ?msg] ?data]
    (->output! "Search Result: %s" ?msg)))

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
        query  {:query {:$filter {:value {:content {:type "post"}}}} :limit count :reverse true}]
    (chsk-send! [:ssb/query {:msg query}] 5000 
                (fn [cb-reply] (->output! "Posted reply: %s" cb-reply)))))

(when-let [target-el (.getElementById js/document "btn-get-messages")]
  (.addEventListener target-el "click" btn-get-messages-click))


(defn btn-login-click [ev]
  (let [user-id (.-value (.getElementById js/document "input-login"))
        config (.-value (.getElementById js/document "input-ssb-config"))] 
    (if (or (str/blank? user-id) (str/blank? config))
      (js/alert "Please enter a user-id, and configuration file-path first")
      (do
        (->output! "Logging in with user-id %s, and config file %s" user-id config)

            ;;; Here we'll trigger an Ajax POST request that resets our server-side session. Then we ask
            ;;; our channel socket to reconnect, thereby picking up the new  session.

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
