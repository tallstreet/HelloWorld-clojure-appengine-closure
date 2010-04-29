
(ns myproject.templates
  (:import [com.google.template.soy SoyFileSet$Builder])
  (:import [com.google.template.soy.data SoyMapData])
  (:import [com.google.template.soy.tofu SoyTofu])
  (:import [java.io File])
  )

(defn load-soy
  [file]
  (let [sfs (.build (doto (new SoyFileSet$Builder)
              (.add (File. file))))]
    (.compileToJavaObj sfs)))

(defn render
  [tofu template data messages]
  (.render tofu "examples.simple.helloWorld" data nil))