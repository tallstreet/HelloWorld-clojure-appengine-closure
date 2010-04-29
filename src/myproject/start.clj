(ns myproject.start
  (:use [myproject.helloworld])
  (:require [com.freiheit.clojure.appengine.appengine-local :as gae-local]))

(defn start-server
  []
  (gae-local/defgaeserver app-server helloworld)
  (gae-local/start-gae-server app-server))
