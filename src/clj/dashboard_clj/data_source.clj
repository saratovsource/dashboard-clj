(ns dashboard-clj.data-source
  (:require [clojure.core.async :as async]))

(declare resolve-fn)
(declare data->event)

(defprotocol Fetchable
  (fetch [this]))

(defprotocol AutoNotify
  (start-watch [this]))


(defn- observe-data-and-notify [{:keys [data output-chan name]}]
  (add-watch data
             :watcher
             (fn [_ _ _ new-data]
               (println (str "data changed " name ":" new-data))
               (async/go
                 (async/>! output-chan (data->event name new-data))))))

(defrecord PollingDataSource [name read-fn params schedule data output-chan]
  Fetchable
  (fetch [this]
    (let [new-data (apply (resolve-fn read-fn) params)]
      (reset! data new-data)))
  AutoNotify
  (start-watch [this]
    (observe-data-and-notify this)))


(defrecord PushDataSource [name params data output-chan]
  AutoNotify
  (start-watch [this]
    (observe-data-and-notify this)))


(defmulti create-datasource #(get % :type :polling))

(defmethod create-datasource :polling
  [{:keys [name read-fn params schedule]}]
  (let [ds (map->PollingDataSource {
                                    :name        name
                                    :read-fn     read-fn
                                    :params      params
                                    :schedule    schedule
                                    :output-chan (async/chan (async/sliding-buffer 1))
                                    :data        (atom nil)})]
    (.start-watch ds)
    ds))

(defmethod create-datasource :push
  [{:keys [name init-fn params]}]
  (let [ds (map->PushDataSource {
                                 :name name
                                 :params params
                                 :data (atom nil)
                                 :output-chan (async/chan (async/sliding-buffer 1))})]
    (.start-watch ds)
    ((resolve-fn init-fn) ds)
    ds))



(defn data->event [event-name data]
  [:data-source/event [event-name {:data data}]])

(defn- kw->fn [kw]
  (try
    (let [user-ns (symbol (namespace kw))
          user-fn (symbol (name kw))]
      (or (ns-resolve user-ns user-fn)
          (throw (Exception.))))
    (catch Throwable e
      (throw (ex-info (str "Could not resolve symbol on the classpath, did you require the file that contains the symbol " kw "?") {:kw kw})))))

(defn- resolve-fn [fn-name]
  (kw->fn fn-name))
