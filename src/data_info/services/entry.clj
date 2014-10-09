(ns data-info.services.entry
  "This namespace provides the business logic for all entries endpoints."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [dire.core :refer [with-pre-hook! with-post-hook!]]
            [slingshot.slingshot :refer [throw+]]
            [clj-icat-direct.icat :as icat]
            [clj-jargon.init :as init]
            [clj-jargon.item-info :as item]
            [clj-jargon.item-ops :as ops]
            [clj-jargon.permissions :as perm]
            [clj-jargon.validations :as jv]
            [clojure-commons.error-codes :as error]
            [clojure-commons.file-utils :as file]
            [clojure-commons.validators :as cv]
            [data-info.util.config :as cfg]
            [data-info.util.logging :as dul]
            [data-info.util.irods :as irods]
            [data-info.util.validators :as duv])
  (:import [java.util UUID]))


(defn id-exists?
  [{user :user entry :entry}]
  (init/with-jargon (cfg/jargon-cfg) [cm]
    (duv/user-exists cm user)
    (if-let [path (irods/get-path cm (UUID/fromString entry))]
      {:status (if (perm/is-readable? cm user path) 200 403)}
      {:status 404})))


(with-pre-hook! #'id-exists?
  (fn [params]
    (dul/log-call "exists?" params)
    (duv/valid-uuid-param "entry" (:entry params))
    (cv/validate-map params {:user string?})))

(with-post-hook! #'id-exists? (dul/log-func "exists?"))


(defn- download-file
  [user file-path]
  (init/with-jargon (cfg/jargon-cfg) [cm]
    (duv/path-readable cm user file-path)
    (if (zero? (item/file-size cm file-path))
      ""
      (ops/input-stream cm file-path))))


(defn- get-disposition
  [path attachment]
  (let [filename (str \" (file/basename path) \")]
    (if (or (nil? attachment) (Boolean/parseBoolean attachment))
      (str "attachment; filename=" filename)
      (str "filename=" filename))))


(defn- get-file
  [path {:keys [attachment user]}]
  (let [content-type (future (irods/detect-media-type path))]
    {:status  200
     :body    (download-file user path)
     :headers {"Content-Disposition" (get-disposition path attachment)
               "Content-Type"        @content-type}}))

(with-pre-hook! #'get-file
  (fn [path params]
    (dul/log-call "get-file" path params)
    (cv/validate-map params {:user string?})
    (when-let [attachment (:attachment params)]
      (duv/valid-bool-param "attachment" attachment))
    (log/info "User for download: " (:user params))
    (log/info "Path to download: " path)))

(with-post-hook! #'get-file (dul/log-func "get-file"))


(defn- filtered-paths
  "Returns a seq of full paths that should not be included in paged listing."
  [user]
  [(file/path-join (cfg/irods-home) user)
   (file/path-join (cfg/irods-home) "public")])


(defn- should-filter?
  "Returns true if the map is okay to include in a directory listing."
  [user path-to-check]
  (let [fpaths (set (concat (cfg/filter-files) (filtered-paths user)))]
    (or (contains? fpaths path-to-check)
        (not (jv/good-string? path-to-check)))))


(defn- page-entry->map
  "Turns a entry in a paged listing result into a map containing file/directory information that can
   be consumed by the front-end."
  [user {:keys [type full_path base_name data_size modify_ts create_ts access_type_id uuid]}]
  (let [filter   (or (should-filter? user full_path) (should-filter? user base_name))
        base-map {:id            uuid
                  :path          full_path
                  :label         base_name
                  :filter        filter
                  :file-size     data_size
                  :date-created  (* (Integer/parseInt create_ts) 1000)
                  :date-modified (* (Integer/parseInt modify_ts) 1000)
                  :permission    (perm/fmt-perm access_type_id)}]
    (if (= type "dataobject")
      base-map
      (assoc base-map :hasSubDirs true :file-size 0))))


(defn- page->map
  "Transforms an entire page of results for a paged listing in a map that can be returned to the
   client."
  [user page]
  (let [entry-types (group-by :type page)
        do          (get entry-types "dataobject")
        collections (get entry-types "collection")
        xformer     (partial page-entry->map user)]
    {:files   (mapv xformer do)
     :folders (mapv xformer collections)}))


(defn- total-filtered
  [user zone path]
  (icat/number-of-filtered-items-in-folder user
                                           zone
                                           path
                                           (apply str jv/bad-chars)
                                           (cfg/filter-files)
                                           (filtered-paths user)))


(defn- paged-dir-listing
  "Provides paged directory listing as an alternative to (list-dir). Always contains files."
  [user path limit offset sfield sord]
  (log/info "paged-dir-listing - user:" user "path:" path "limit:" limit "offset:" offset)
  (init/with-jargon (cfg/jargon-cfg) [cm]
    (duv/user-exists cm user)
    (duv/path-readable cm user path)
    (let [stat  (item/stat cm path)
          zone  (cfg/irods-zone)
          pager (log/spy (icat/paged-folder-listing user zone path sfield sord limit offset))]
      (assoc (page->map user pager)
        :id             (irods/lookup-uuid cm path)
        :path           path
        :filter         (should-filter? user path)
        :permission     (perm/permission-for cm user path)
        :hasSubDirs     true
        :date-created   (:date-created stat)
        :date-modified  (:date-modified stat)
        :file-size      0
        :total          (icat/number-of-items-in-folder user zone path)
        :total_filtered (total-filtered user zone path)))))


(def ^:private api-field->db-col
  {"datecreated"  :create-ts
   "datemodified" :modify-ts
   "name"         :base-name
   "path"         :full-path
   "size"         :data-size
   nil            :base-name})


(def ^:private api-order->db-order
  {"asc"  :asc
   "desc" :desc
   nil    :asc})


(defn- canonicalize-str
  [str]
  (when str (str/lower-case str)))


(defn- resolve-sort-field
  [sort-field]
  (get api-field->db-col (canonicalize-str sort-field)))


(defn- resolve-sort-order
  [sort-order]
  (get api-order->db-order (canonicalize-str sort-order)))


(defn- validate-sort-field
  [sort-field]
  (when-not (contains? api-field->db-col (canonicalize-str sort-field))
    (log/warn "invalid sort field" sort-field)
    (throw+ {:error_code "ERR_INVALID_SORT_FIELD" :field sort-field})))


(defn- validate-sort-order
  [sort-order]
  (when-not (contains? api-order->db-order (canonicalize-str sort-order))
    (log/warn "invalid sort order" sort-order)
    (throw+ {:error_code "ERR_INVALID_SORT_ORDER" :sort-order sort-order})))


(defn- get-folder
  "Entrypoint for the API that calls (paged-dir-listing)."
  [path {:keys [limit offset sort-field sort-order user]}]
  (let [path       (file/rm-last-slash path)
        limit      (Integer/parseInt limit)
        offset     (Integer/parseInt offset)
        sort-field (resolve-sort-field sort-field)
        sort-order (resolve-sort-order sort-order)]
    (paged-dir-listing user path limit offset sort-field sort-order)))

(with-pre-hook! #'get-folder
  (fn [path params]
    (dul/log-call "get-folder" path params)
    (cv/validate-map params {:limit  cv/field-nonnegative-int?
                             :offset cv/field-nonnegative-int?
                             :user   string?})
    (when-let [field (:sort-field params)]
      (validate-sort-field field))
    (when-let [order (:sort-order params)]
      (validate-sort-order order))))

(with-post-hook! #'get-folder (dul/log-func "get-folder"))


(defn get-by-path
  [path-in-zone {zone :zone :as params}]
  (let [full-path (irods/abs-path zone path-in-zone)

        ;; detecting if the path is a folder happens in a separate connection to iRODS on purpose.
        ;; It appears that downloading a file after detecting its type causes the download to fail.
        ; TODO after migrating to jargon 4, check to see if this error still occurs.
        folder? (init/with-jargon (cfg/jargon-cfg) [cm]
                  (duv/path-exists cm full-path)
                  (item/is-dir? cm full-path))]
    (if folder?
      (get-folder full-path params)
      (get-file full-path params))))
