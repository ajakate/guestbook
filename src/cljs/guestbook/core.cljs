(ns guestbook.core
  (:require [reagent.core :as r]
            [reagent.dom  :as dom]
            [ajax.core :refer [GET POST]]
            [clojure.string :as string]
            [guestbook.validation :refer [validate-message]]))

(defn send-message! [fields errors]
  (if-let [validation-errors (validate-message @fields)]
    (reset! errors validation-errors)
    (POST "/message"
      {:params @fields
       :headers
       {"Accept" "application/transit+json"
        "x-csrf-token" (-> js/document (.getElementById "token") .-value)}
       :handler #(do
                   (.log js/console (str "response: " %))
                   (reset! errors nil))
       :error-handler #(do
                         (.error js/console (str "error:" %))
                         (reset! errors (get-in % [:response :errors])))})))

(defn errors-component [errors id]
  (when-let [error (id @errors)]
    [:div.notification.is-danger (string/join error)]))

(defn message-form []
  (let [fields (r/atom {})
        errors (r/atom nil)]
    (fn [] [:div
            [:p "Name: " (:name @fields)]
            [:p "Message: " (:message @fields)]
            [errors-component errors :server-error]
            [:div.field
             [:label.label {:for :name} "Name"]
             [errors-component errors :name]
             [:input.input
              {:type :text
               :name :name
               :on-change #(swap! fields assoc :name (-> % .-target .-value)) :value (:name @fields)}]]
            [:div.field
             [:label.label {:for :message} "Message"]
             [errors-component errors :message]
             [:textarea.textarea
              {:name :message
               :value (:message @fields)
               :on-change #(swap! fields assoc :message (-> % .-target .-value))}]]
            [:input.button.is-primary
             {:type :submit
              :value "comment"
              :on-click #(send-message! fields errors)}]])))

(defn home []
  [:div.content>div.columns.is-centered>div.column.is-two-thirds
   [:div.columns>div.column
    [message-form]]])

(dom/render
 [home]
 (.getElementById js/document "content"))