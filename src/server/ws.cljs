(ns server.ws
  "Sente based web socket"
  (:require
   [server.ssb :as ssb]
   [server.message-bus :as bus]
   ;;[cljs.nodejs        :as nodejs]
   [clojure.string     :as str]
   [hiccups.runtime    :as hiccupsrt]
   [cljs.core.async    :as async  :refer (<! >! put! take! chan)]
   [taoensso.encore    :as encore :refer ()]
   [taoensso.timbre    :as timbre :refer-macros (tracef debugf infof warnf errorf)]
   [taoensso.sente     :as sente]
   [taoensso.sente.server-adapters.express :as sente-express]

   ;;nodejs libraries
   ["http" :as http]
   ["express" :as express]
   ["express-ws" :as express-ws]
   ["ws" :as ws]
   ["cookie-parser" :as cookie-parser]
   ["body-parser" :as body-parser]
   ["csurf" :as csurf]
   ["express-session" :as express-session]

   ;; Optional, for Transit encoding:
   ;[taoensso.sente.packers.transit :as sente-transit]
   )
  (:require-macros
   [hiccups.core :as hiccups :refer [html]]
   [cljs.core.async.macros :as asyncm :refer (go go-loop)]))

(set! js/console.debug js/console.log)
(enable-console-print!)
;;(timbre/set-level! :trace) ; Uncomment for more logging

;;;; Ring handlers

(defn landing-pg-handler [ring-req]
  (debugf "Landing page handler")
  (-> [:html
       [:head "Content-Type: text/html"]
       [:body
        [:h1 "SSB Navigator"]
        [:hr]

        [:p [:strong "Server Response"] ":"]
        [:textarea#output {:style "width: 100%; height: 200px;"}]

        [:p [:strong "Error Messages"]]
        [:textarea#errors {:style "width: 100%; height: 200px;"}]

        [:hr]
        [:h2 "Login"]
        [:p
         [:input#input-login {:type :text :placeholder "User-id"}]
         [:input#input-ssb-config {:type :text :value "/.ssb"}] 
         [:button#btn-login {:type "button"} "Secure login!"]]

        [:hr]
        [:h2 "Post Message:"]
        [:p
         [:input#input-post {:type :text :placeholder "Message text..."}]
         [:button#btn-post {:type "button"} "Post!"]]

        [:hr]
        [:h2 "Get Messages"]
        [:p
         [:input#input-message-cnt {:type :number :placeholder 10}]
         [:button#btn-get-messages {:type "button"} "Get Messages!"]
         [:textarea#feed {:style "width: 100%; height: 200px;"}]]

        [:hr]
        [:h2 "Query Feeds"]
        [:p
         [:input#query-string {:type :text :placeholder "Query string"}]
         [:label "Query limit:"] [:input#query-limit {:type :number :placeholder 10}]
         [:label "Message type"] [:select-options {:id "query-type"} ["all" "post" "about" "likes"] "post"]
         [:button#btn-query {:type "button"} "Query Database"]]

        [:script {:src "js/main.js"}]    ; Include our cljs target
             ]]
      (hiccups/html)))


(let [;; Serialization format, must use same val for client + server:
      packer :edn ; Default packer, a good choice in most cases
      ;; (sente-transit/get-flexi-packer :edn) ; Experimental, needs Transit dep
      {:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
              connected-uids]}
      (sente-express/make-express-channel-socket-server! {:packer packer
                                                          :user-id-fn 
                                                          (fn [ring-req] (aget (:body ring-req) "session" "uid"))})]
  (def ajax-post                ajax-post-fn)
  (def ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                  ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!               send-fn) ; ChannelSocket's send API fn
  (def connected-uids           connected-uids) ; Watchable, read-only atom
  )

(defn express-login-handler
  "Here's where you'll add your server-side login/auth procedure (Friend, etc.).
  In our simplified example we'll just always successfully authenticate the user
  with whatever user-id they provided in the auth request."
  [req res]
  (let [req-session (aget req "session")
        body        (aget req "body")
        uid     (aget body "user-id")
        config      (aget body "config")]
    ;(debugf "req: %s" (js->clj req :keywordize-keys true))
    ;(debugf "res: %s" (js->clj res :keywordize-keys true))
    (aset req-session "uid" uid)
    (bus/dispatch! bus/msg-ch :server-start [uid config]) ;;TODO create login
    (.send res "Success")))

(defn routes [^js express-app]
  (doto express-app
    (.get "/" (fn [req res] (.send res (landing-pg-handler req))))

    (.ws "/chsk"
         (fn [ws req next]
           (ajax-get-or-ws-handshake req nil nil
                                     {:websocket? true
                                      :websocket  ws})))

    (.get "/chsk" ajax-get-or-ws-handshake)
    (.post "/chsk" ajax-post)
    (.post "/login" express-login-handler)
    (.use (.static express "public"))
    (.use (fn [^js req res next]
            (warnf "Unhandled request: %s" (.-originalUrl req))
            (next)))))

(defn wrap-defaults [^js express-app ^js routes]
  (let [cookie-secret "the shiz"]
    (doto express-app
      (.use (fn [^js req res next]
              (tracef "Request: %s" (.-originalUrl req))
              (next)))
      (.use (express-session
             #js {:secret            cookie-secret
                  :resave            true
                  :cookie            {}
                  :store             (.MemoryStore express-session)
                  :saveUninitialized true}))
      (.use (.urlencoded body-parser
                         #js {:extended false}))
      (.use (cookie-parser cookie-secret))
      (.use (csurf
             #js {:cookie false}))
      (routes))))

(defn main-ring-handler [express-app]
  ;; Can we even call this a ring handler?
  (wrap-defaults express-app routes))

(defn start-selected-web-server! [ring-handler port]
  (infof "Starting express...")
  (let [express-app       (express)
        express-ws-server (express-ws express-app)]

    (ring-handler express-app)

    (let [http-server (.listen express-app port)]
      {:express-app express-app
       :ws-server   express-ws-server
       :http-server http-server
       :stop-fn     #(.close http-server)
       :port        port})))



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
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (debugf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-from-server event}))))

(defmethod -event-msg-handler
  :ssb/post
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn uid]}]
  (let [msg (:msg ?data)]
    (debugf "Post event: %s" event)
    (debugf "ev-msg: %s" ev-msg)
    (bus/dispatch! bus/msg-ch :add-message {:uid uid :msg msg})))

(defmethod -event-msg-handler
  :ssb/query
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn uid]}]
  (let [msg (:msg ?data)]
    (debugf "Query event: %s" event)
    (bus/dispatch! bus/msg-ch :query {:uid uid :msg msg}) 
    ;(ssb/query uid msg)
    ;(when ?reply-fn (?reply-fn {:post-event ?data}))
    ))

;; Message Bus Handlers

(bus/handle! bus/msg-bus :error
             (fn [{:keys [uid message]}] 
               (println "Error: " message)
               (chsk-send! uid [:ssb/error-event {:message message}])))

(bus/handle! bus/msg-bus :response
             (fn [{:keys [uid message]}]
               (chsk-send! uid [:ssb/response {:message message}])))

(bus/handle! bus/msg-bus :feed
             (fn [{:keys [uid message]}]
               (chsk-send! uid [:ssb/feed {:message message}])))


(bus/handle! bus/msg-bus :query-response
             (fn [{:keys [uid message]}]
               (chsk-send! uid [:ssb/query-response {:message message}])))


(bus/handle! bus/msg-bus :raw-feed
             (fn [{:keys [uid message]}]
               (doseq [msg message]
                 #(chsk-send! uid  [:ssb/feed :uid uid :message  (->content msg)]))))


;;;; Sente event router (our `event-msg-handler` loop)

(defonce router_ (atom nil))
(defn  stop-router! [] (when-let [stop-f @router_] (stop-f)))
(defn start-router! []
  (stop-router!)
  (reset! router_
          (sente/start-server-chsk-router!
           ch-chsk event-msg-handler)))

;;;;server>user async push 

(defn start-example-broadcaster!
  "As an example of server>user async pushes, setup a loop to broadcast an
  event to all connected users every 10 seconds"
  []
  (let [broadcast!
        (fn [i]
          (debugf "Broadcasting server>user: %s" @connected-uids)
          (doseq [uid (:any @connected-uids)]
            (chsk-send! uid
                        [:some/broadcast
                         {:what-is-this "An async broadcast pushed from server"
                          :how-often    "Every 10 seconds"
                          :to-whom      uid
                          :i            i}])))]

    (go-loop [i 0]
      (<! (async/timeout 10000))
      (broadcast! i)
      (recur (inc i)))))

(defn test-fast-server>user-pushes
  "Quickly pushes 100 events to all connected users. Note that this'll be
  fast+reliable even over Ajax!"
  []
  (doseq [uid (:any @connected-uids)]
    (doseq [i (range 100)]
      (chsk-send! uid [:fast-push/is-fast (str "hello " i "!!")]))))

(defn ws-send-ch [event-type ch]
  "continuously sends content from supplied channel"
  (go-loop []
    (chsk-send! [event-type (<! ch)])
  (recur)))

(comment (test-fast-server>user-pushes))

;;;; Init stuff

(defonce web-server_ (atom nil)) ; {:server _ :port _ :stop-fn (fn [])}
(defn stop-web-server! [] (when-let [m @web-server_] ((:stop-fn m))))
(defn start-web-server! [& [port]]
  (stop-web-server!)
  (let [{:keys [stop-fn port] :as server-map}
        (start-selected-web-server! (var main-ring-handler) (or port 4000))
        uri (str "http://localhost:" port "/")]
    (infof "Web server is running at `%s`" uri)
    (reset! web-server_ server-map)))

(defn stop!  []  (stop-router!)  (stop-web-server!))
(defn start! [] (start-router!) (start-web-server!))

;; (start-example-broadcaster!)
;; (defonce _start-once (start!))

(comment
(defn -main [& _]
  (start!))

(set! *main-cli-fn* -main) ;; this is required

  (start!)
  (test-fast-server>user-pushes)
)
