(ns com.freiheit.clojure.appengine.appengine-local
  (:use
   [compojure.http routes servlet helpers]
   clojure.contrib.test-is
   compojure.server.jetty
   [clojure.contrib def str-utils duck-streams])
  (:import
   [com.google.appengine.api.labs.taskqueue.dev LocalTaskQueue]
   [com.google.appengine.tools.development ApiProxyLocalFactory ApiProxyLocalImpl LocalServiceContext LocalServerEnvironment]
   [com.google.apphosting.api ApiProxy ApiProxy$Environment]
   [java.io File]
   [java.util HashMap]))

;;;; Utility functions for running a Google App Engine application from the REPL.
;;;; This currently works best with compojure.
;;;;
;;;; To use it just do the following:
;;;;
;;;; 1) Define your routes for compojure.
;;;;    (defroutes app
;;;;       (GET "/" ....))
;;;;
;;;; 2) Define a Google App Engine Server.
;;;;    (defgaeserver app-server app)
;;;;
;;;; 3) Start the server.
;;;;    (start-gae-server app-server)
;;;;
;;;; This setup here uses some conventions.
;;;;
;;;; - The application will have its working directory based in "/tmp". So if you're
;;;;   looking for the generated files like datastore-indexes-auto.xml have a look in
;;;;   /tmp/WEB-INF/appengine-generated/.
;;;; - Static files are served from src/web. The subdirectories considered are
;;;;   /css, /js, /img, /static.
;;;;
;;;; Unit testing App Engine.
;;;;
;;;; This module also provides a deftest-appengine macro that helps you to write unit testing
;;;; code that needs the appengine services.
;;;; Just use it like the regular (deftest ...) macro. It will setup the services before running
;;;; the body of the macro. Every test gets a seperate working directory in /tmp/{name-of-the-test}.

;; ------------------------------------------------------------------------------
;; private functions
;; ------------------------------------------------------------------------------
(defvar *default-port* 9090)
(defvar *default-gae-directory* "/tmp")
(defvar *static-file-path* "/src/web/")

(defn- proxy-attributes-hashmap
  "Return a map of attributes for the ApiProxy."
  []
  (let [attributes (HashMap.)
        local-server-url (str "http://localhost:" *default-port*)]
    (.put attributes "com.google.appengine.server_url_key" local-server-url)
    attributes))

(declare get-base-path)

(defn- get-static-path
  "Return the paths for serving static files. Used for local startup."
  []
  (str (get-base-path) *static-file-path*))

(defn- serve-static-file
  "serves the file and sets the correct content/type."
  [path filename]
  {:body (serve-file path filename)
   :headers {"Content-Type"
             (cond
               (.endsWith filename ".css") "text/css"
               (.endsWith filename ".js") "text/javascript"
               (.endsWith filename ".gif") "image/gif"
               (.endsWith filename ".js") "text/javascript"
               true "text/html")}})


(defn- handle-static-files-local
  "Return a function to serve the file."
  [path filename]
  (fn [request] (serve-static-file path filename)))

(defn- static-route
  "Define a static router the given url pattern and the path."
  [url-pattern path]
  (let [full-path (str (get-static-path) path)]
    (GET url-pattern
      (or (handle-static-files-local full-path (params :*))
          :next))))

;; ------------------------------------------------------------------------------
;; public but not needed to be called directly
;; ------------------------------------------------------------------------------
(defn get-base-path
  "Return the base path of the current Google App Engine project."
  []
  (let [working-directory (.getAbsolutePath (File. "."))
        test-paths ["/src/clojure/main.*" "/src/main/clojure.*"]
        first-found (first
                     (reduce
                      (fn [found current]
                        (let [pattern (re-pattern (str "(.*)" current))
                              found-path (second (first (re-seq pattern working-directory)))]
                          (if (nil? found-path)
                            found
                            (cons found-path found))))
                      []
                      test-paths))]
    (if (nil? first-found)
      working-directory
      first-found)))

(defn set-gae-system-properties
  "Read the appengine-web.xml file, extract and save all system properties defined in this file."
  []
  (let [ae-web-xml-path (str (get-base-path) "/src/web/WEB-INF/appengine-web.xml")
        ae-web-xml-file (File. ae-web-xml-path)]
    (if (and (.exists ae-web-xml-file)
             (.canRead ae-web-xml-file))
      (doseq [system-property (for [xml-element (xml-seq (clojure.xml/parse ae-web-xml-path))
                                    :when (= (:tag xml-element) :property)]
                                (:attrs xml-element))]
        (System/setProperty (:name system-property) (:value system-property))))))

(defn copy-gae-xml-files
  "Copy the XML configuration files into the working directory of the application."
  ([]
     (copy-gae-xml-files (get-base-path) *default-gae-directory*))
  ([basedir target-dir]
     (doall
      (for [[s d] [["/src/web/WEB-INF/queue.xml" "/WEB-INF/queue.xml"]]]
        (let [t-file (File. (str target-dir d))]
          (.mkdirs (.getParentFile t-file))
          (let [src-file (File. (str basedir s))]
            (when (.exists src-file)
              (.copy src-file t-file))))))))

(defn delete-old-generated-files
  "Delete the generated files from the appengine working directory."
  [basedir]
  (doseq [f (.listFiles (File. (str basedir "/WEB-INF/appengine-generated/")))]
    (.delete f)))

(defmacro with-app-engine
  "Setup the appengine environment for the current thread and execute the given body."
  ([body]
     `(with-app-engine env-proxy ~body))
  ([proxy body]
     `(do (com.google.apphosting.api.ApiProxy/setEnvironmentForCurrentThread ~proxy)
          ~body)))

(defvar env-proxy
  (proxy [ApiProxy$Environment] []
    (isLoggedIn [] false)
    (getRequestNamespace [] "")
    (getDefaultNamespace [] "")
    (getAttributes [] (proxy-attributes-hashmap))
    (getAppId [] "local")))

(defroutes static-routes
  (static-route "/css/*" "/css/")
  (static-route "/js/*" "/js/")
  (static-route "/img/*" "/img/")
  (static-route "/static/*" "/static/"))

(def static-servlet (servlet static-routes))

(defn login-aware-proxy
  "Returns a proxy for the google apps environment."
  [request]
  (let [email (:email (:session request))]
    (proxy [ApiProxy$Environment] []
      (isLoggedIn [] (boolean email))
      (getAuthDomain [] "freiheit.com")
      (getRequestNamespace [] "")
      (getDefaultNamespace [] "")
      (getAttributes [] (proxy-attributes-hashmap))
      (getEmail [] (or email ""))
      (isAdmin [] true)
      (getAppId [] "local"))))

(defn environment-decorator
  "decorates the given application with the app engine services."
  [application]
  (fn [request]
    (with-app-engine (login-aware-proxy request)
     (application request))))

(defn init-app-engine
  "Initialize the app engine services. Needs to be done once for a JVM."
  ([]
     (init-app-engine *default-gae-directory*))
  ([dir]
     (let [file (File. dir)
           local-env (proxy [LocalServerEnvironment] []
                       (getAppDir [] file)
                       (getAddress [] "localhost")
                       (getPort [] *default-port*)
                       (waitForServerToStart [] nil))
	   api-proxy (.create (ApiProxyLocalFactory.) local-env)]
       (make-parents file)
       (ApiProxy/setDelegate api-proxy))))

(defn shutdown-app-engine
  "Shutdown the app engine services."
  []
  (let [task-queue (.getService (ApiProxy/getDelegate) LocalTaskQueue/PACKAGE)]
    (.stop task-queue)
    (.stop (ApiProxy/getDelegate))))

;; ------------------------------------------------------------------------------
;; public functions
;; ------------------------------------------------------------------------------

(defmacro defgaeserver
  "Define a server like compojure's defserver but also setup all routes for static
   content.

   The given routes are decorated by a function that sets up the appengine service
   for the request."
  ([server-name routes]
     `(defgaeserver ~server-name
        ~routes static-servlet {} "/_ah/*" "/css/*" "/js/*" "/img/*" "/static/*"))
  ([server-name routes static opts & static-routes]
     `(defserver ~server-name
        (merge {:port ~*default-port*} ~opts)
        ~@(interleave static-routes (repeat static))
        "/*" (servlet (environment-decorator ~routes)))))

(defn start-gae-server
  "starts the application on a jetty with a local google app engine environment."
  [server]
  (copy-gae-xml-files)
  (init-app-engine)
  (set-gae-system-properties)
  (start server))

(defmacro deftest-appengine
  "Macro for defining tests that need the app engine services."
  [test-name body]
  (let [namestr (str test-name)]
    `(deftest ~test-name
       []
       (with-app-engine
         (let [base# (get-base-path)
               app-dir# (str "/tmp/gae-tests/" ~namestr)]
           (delete-old-generated-files app-dir#)
           (copy-gae-xml-files base# app-dir#)
           (init-app-engine app-dir#)
           (set-gae-system-properties)
           ~body
           (shutdown-app-engine))))))