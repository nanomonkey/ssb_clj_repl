(ns server.macros)

;; These macros are meant to clean up the server calls, but don't work quite yet...

;; Scuttlebutt database calls
(defmacro db-sync [uid source-fn parameters]
  `(if-let [^js db (get @ssb/db-conns uid)]
     (parse-json (~source-fn db ~parameters))))

(defmacro db-async [uid bus-tag source-fn & parameters]
  `(if-let [^js db# (get @db-conns uid)]
    (. db# ~source-fn  ~parameters (fn [err# value#] (if err
                                          (bus/dispatch! bus/msg-ch :error {:uid uid :message (ssb/parse-json err#)})
                                          (bus/dispatch! bus/msg-ch ~bus-tag {:uid uid :message (ssb/parse-json value#)}))))
    (bus/dispatch! bus/msg-ch :error {:uid uid :message (str "Unable to get server with User-id: " uid )})))

(defmacro db-collect
  [uid bus-tag source-fn & parameters]
  `(if-let [^js db# (get @~'db-conns ~uid)]
     (~'pull (. db# ~source-fn ~parameters)
      (.collect ~'pull (fn [err# ary#] (if err#
                                         (bus/dispatch! bus/msg-ch :error {:uid ~uid :message (~'parse-json err#)})
                                         (bus/dispatch! bus/msg-ch ~bus-tag {:uid ~uid :message (~'parse-json ary#)})))))
     (bus/dispatch! bus/msg-ch :error {:uid ~uid :message (str "Unable to get server with User-id: " ~uid )})))

(defmacro db-drain 
  [uid bus-tag source-fn & parameters]
  `(if-let [^js db (get @db-conns uid)]
    (pull (. db ~source-fn ~parameters)
          (.drain pull 
                  ;; this gets run on each object in stream
                  (fn op? [val] (bus/dispatch! bus/msg-ch ~bus-tag {:uid uid :message (parse-json val)}))
                  ;; this gets run when stream runs out
                  (fn done? [val] (when val 
                                    (bus/dispatch! bus/msg-ch ~bus-tag 
                                                   {:uid uid :message (str "Feed closed: " (parse-json val))})))))
    (bus/dispatch! bus/msg-ch :error {:uid uid :message (str "Unable to get server with User-id: " uid )})))
