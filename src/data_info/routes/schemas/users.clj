(ns data-info.routes.schemas.users
  (:use [common-swagger-api.schema :only [describe
                                          NonBlankString
                                          StandardUserQueryParams]]
        [data-info.routes.schemas.common])
  (:require [schema.core :as s]))

(s/defschema AddGroupQueryParams
  (merge StandardUserQueryParams
         {:group (describe NonBlankString "The group name to add")}))

(s/defschema AddUserToGroupQueryParams
  (merge StandardUserQueryParams
         {:username (describe NonBlankString "The username to add to the group")}))

(def QualifiedUser NonBlankString)
(s/defschema UserGroupsReturn
  {:user (describe QualifiedUser "The user as requested, but qualified with iRODS zone")
   :groups (describe [QualifiedUser] "The list of qualified group names of groups this user belongs to")})
