; a basic application using the google app engine
; when this file is created it will create a class that extends
; javax.servlet.http.HttpServlet which can be mapped in the
; applications web.xml.

(ns myproject.helloworld
  (:gen-class :extends javax.servlet.http.HttpServlet)
  (:use [compojure.http servlet routes helpers])
  (:use myproject.templates)
  (:require [appengine.datastore :as ds])
  (:import [com.google.appengine.api.datastore Query]))

(defn index
  [request]
  (let [items (ds/find-all (Query. "item"))
        tofu (load-soy "server-templates/simple.soy")
        data {"number" (count items)}]
    (render tofu "examples.simple.helloWorld" data nil)))

(defn new
  [request]
  (do
    (ds/create-entity {:kind "item" :text "something"})
    (redirect-to "/")))

(defroutes helloworld
  (GET "/" index)
  (GET "/new" new))

(defservice helloworld)
