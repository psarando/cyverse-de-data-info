(ns data-info.util.irods
  "This namespace encapsulates all of the common iRODS access logic."
  (:require [clojure.tools.logging :as log]
            [slingshot.slingshot :refer [try+ throw+]]
            [clj-jargon.by-uuid :as uuid]
            [clj-jargon.init :as init]
            [clj-jargon.item-ops :as ops]
            [clj-jargon.metadata :as meta]
            [clojure-commons.error-codes :as error]
            [clojure-commons.file-utils :as file]
            [data-info.util.config :as cfg])
  (:import [clojure.lang IPersistentMap]
           [java.util UUID]
           [java.io IOException InputStream]
           [org.irods.jargon.core.exception JargonException]
           [org.apache.tika Tika]))

(defmacro catch-jargon-io-exceptions
  [& body]
  `(try+
     (do ~@body)
     (catch JargonException e#
       (if (instance? IOException (.getCause e#))
         (throw+ {:error_code error/ERR_UNAVAILABLE
                  :reason (str "iRODS is unavailable: " e#)})
         (throw+)))))

(defmacro with-jargon-exceptions
  [& params]
  (let [[opts [[cm-sym] & body]] (split-with #(not (vector? %)) params)]
    `(catch-jargon-io-exceptions
       (init/with-jargon (cfg/jargon-cfg) ~@opts [~cm-sym] (do ~@body)))))

(defn ^String abs-path
  "Resolves a path relative to a zone into its absolute path.

   Parameters:
     zone         - the name of the zone
     path-in-zone - the path relative to the zone

   Returns:
     It returns the absolute path."
  [^String zone ^String path-in-zone]
  (file/path-join "/" zone path-in-zone))


(defn ^UUID lookup-uuid
  "Retrieves the UUID associated with a given entity path.

   Parameters:
     cm   - the jargon context map
     path - the path to the entity

   Returns:
     It returns the UUID."
  [^IPersistentMap cm ^String path]
  (let [attrs (meta/get-attribute cm path uuid/uuid-attr)]
    (when-not (pos? (count attrs))
      (log/warn "Missing UUID for" path)
      (throw+ {:error_code error/ERR_NOT_FOUND :path path}))
    (-> attrs first :value UUID/fromString)))

(defn- detect-media-type-from-contents
  [^IPersistentMap cm ^String path]
  (with-open [^InputStream istream (ops/input-stream cm path)]
    (.detect (Tika.) istream)))

(defn ^String detect-media-type
  "detects the media type of a given file

   Parameters:
     cm   - (OPTIONAL) an open jargon context
     path - the absolute path to the file

   Returns:
     It returns the media type."
  ([^IPersistentMap cm ^String path]
   (let [path-type (.detect (Tika.) (file/basename path))]
     (if (or (= path-type "application/octet-stream")
             (= path-type "text/plain"))
       (detect-media-type-from-contents cm path)
       path-type)))

  ([^String path]
   (with-jargon-exceptions [cm]
     (detect-media-type cm path))))
