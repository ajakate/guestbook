(ns guestbook.routes.home
  (:require
   [guestbook.layout :as layout]
   [guestbook.db.core :as db]
   [guestbook.middleware :as middleware]
   [ring.util.http-response :as response]
   [struct.core :as st]
   [guestbook.validation :refer [validate-message]]))

(defn home-page [{:keys [flash] :as request}]
  (layout/render
   request
   "home.html"))

(defn about-page [request]
  (layout/render
   request "about.html"))

(defn home-routes []
  [""
   {:middleware [middleware/wrap-csrf
                 middleware/wrap-formats]}
   ["/" {:get home-page}]
   ["/about" {:get about-page}]])