(ns duct.module.web
  (:require [clojure.java.io :as io]
            [duct.core.web :as core]
            [duct.middleware.web :as mw]
            [duct.server.http.jetty :as jetty]
            [integrant.core :as ig]
            [meta-merge.core :refer [meta-merge]]
            [ring.middleware.defaults :as defaults]))

(defn- not-in? [m ks]
  (= (get-in m ks ::missing) ::missing))

(defn- assoc-in-default [config keys default]
  (cond-> config (not-in? config keys) (assoc-in keys default)))

(defn- missing-middleware? [middleware key]
  (not (contains? (set (map :key middleware)) key)))

(defn- conj-middleware [middleware key]
  (cond-> middleware (missing-middleware? middleware key) (conj (ig/ref key))))

(defn- add-server [config {:keys [server-port] :or {server-port 3000}}]
  (if-let [[[k v]] (ig/find-derived config :duct.server/http)]
    (assoc-in-default config [k :port] server-port)
    (assoc config :duct.server.http/jetty {:port server-port})))

(defn- add-handler [config]
  (let [[[k v]] (ig/find-derived config :duct.server/http)]
    (-> config
        (assoc-in [k :handler] (ig/ref ::core/handler))
        (assoc-in-default [::core/handler :endpoints]  [])
        (assoc-in-default [::core/handler :middleware] []))))

(defn add-middleware [config key value]
  (-> config
      (update-in [::core/handler :middleware] conj-middleware key)
      (update key (partial meta-merge value))))

(defmethod ig/init-key ::api [_ options]
  (fn [config]
    (-> config
        (add-server options)
        (add-handler)
        (add-middleware ::mw/not-found   {:response "Resource Not Found"})
        (add-middleware ::mw/defaults    defaults/api-defaults)
        (add-middleware ::mw/hide-errors {:response "Internal Server Error"}))))

(def ^:private error-404 (io/resource "duct/module/web/errors/404.html"))
(def ^:private error-500 (io/resource "duct/module/web/errors/500.html"))

(defn- site-defaults [{:keys [static-resources]}]
  (assoc-in defaults/site-defaults [:static :resources]
            (into ["duct/module/web/public"] (if static-resources [static-resources]))))

(defmethod ig/init-key ::site [_ options]
  (fn [config]
    (-> config
        (add-server options)
        (add-handler)
        (add-middleware ::mw/not-found   {:response error-404})
        (add-middleware ::mw/webjars     {})
        (add-middleware ::mw/defaults    (site-defaults options))
        (add-middleware ::mw/hide-errors {:response error-500}))))
