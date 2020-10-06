(ns guestbook.core
  (:require [reagent.core :as r]
            [reagent.dom  :as dom]
            [ajax.core :refer [GET POST]]
            [clojure.string :as string]
            [guestbook.validation :refer [validate-message]]
            [guestbook.websockets :as ws] ;; TODO: add this for feedback
            [re-frame.core :as rf]
            [mount.core :as mount]))

(rf/reg-event-fx
 :app/initialize
 (fn [_ _]
   {:db {:messages/loading? true}}
   {:dispatch [:messages/load]}))

(rf/reg-event-fx
 :messages/load
 (fn [{:keys [db]} _]
   (GET "/api/messages"
     {:headers {"Accept" "application/transit+json"}
      :handler #(rf/dispatch [:messages/set (:messages %)])})
   {:db (assoc db :messages/loading? true)}))

(rf/reg-sub
 :messages/loading?
 (fn [db _]
   (:messages/loading? db)))

(rf/reg-event-db
 :messages/set
 (fn [db [_ messages]]
   (-> db
       (assoc :messages/loading? false
              :messages/list messages))))

(rf/reg-event-db
 :form/set-field
 [(rf/path :form/fields)]
 (fn [fields [_ id value]]
   (assoc fields id value)))

(rf/reg-event-db
 :form/clear-fields
 [(rf/path :form/fields)]
 (fn [_ _]
   {}))

(rf/reg-sub
 :form/fields
 (fn [db _]
   (:form/fields db)))

(rf/reg-sub
 :form/field
 :<- [:form/fields]
 (fn [fields [_ id]]
   (get fields id)))

(rf/reg-event-db
 :form/set-server-errors
 [(rf/path :form/server-errors)]
 (fn [_ [_ errors]]
   errors))

(rf/reg-sub
 :form/server-errors
 (fn [db _] (:form/server-errors db)))

(rf/reg-sub
 :form/validation-errors
 :<- [:form/fields]
 (fn [fields _]
   (validate-message fields)))

(rf/reg-sub
 :form/validation-errors?
 :<- [:form/validation-errors]
 (fn [errors _]
   (not (empty? errors))))

(rf/reg-sub
 :form/errors
 :<- [:form/validation-errors]
 :<- [:form/server-errors]
 (fn [[validation server] _]
   (merge validation server)))

(rf/reg-sub
 :form/error
 :<- [:form/errors]
 (fn [errors [_ id]]
   (get errors id)))

(rf/reg-event-db
 :messages/add
 (fn [db [_ message]]
   (update db :messages/list conj message)))

(rf/reg-sub
 :messages/list
 (fn [db _]
   (:messages/list db)))

(rf/reg-event-db
 :message/add
 (fn [db [_ message]]
   (update db :messages/list conj message)))

(rf/reg-event-fx
 :message/send!-called-back
 (fn [_ [_ {:keys [success errors]}]]
   (if success
     {:dispatch [:form/clear-fields]}
     {:dispatch [:form/set-server-errors errors]})))

(rf/reg-event-fx
 :message/send!
 (fn [{:keys [db]} [_ fields]]
   {:db (dissoc db :form/server-errors)
    :ws/send! {:message [:message/create! fields]
               :timeout 10000
               :callback-event [:message/send!-called-back]}}))

(rf/reg-event-fx
 :message/send!
 (fn [{:keys [db]} [_ fields]]
   (ws/send!
    [:message/create! fields]
    10000
    (fn [{:keys [success errors] :as response}]
      (.log js/console "Called Back: " (pr-str response))
      (if success
        (rf/dispatch [:form/clear-fields])
        (rf/dispatch [:form/set-server-errors errors]))))
   {:db (dissoc db :form/server-errors)}))

(rf/reg-event-db
 :app/show-modal
 (fn [db [_ modal-id]]
   (assoc-in db [:app/active-modals modal-id] true)))

(rf/reg-event-db
 :app/hide-modal
 (fn [db [_ modal-id]]
   (update db :app/active-modals dissoc modal-id)))

(rf/reg-sub
 :app/active-modals
 (fn [db _]
   (:app/active-modals db {})))

(rf/reg-sub
 :app/modal-showing?
 :<- [:app/active-modals]
 (fn [modals [_ modal-id]]
   (get modals modal-id false)))

(defn modal-card [id title body footer]
  [:div.modal
   {:class (when @(rf/subscribe [:app/modal-showing? id]) "is-active")}
   [:div.modal-background
    {:on-click #(rf/dispatch [:app/hide-modal id])}]
   [:div.modal-card
    [:header.modal-card-head
     [:p.modal-card-title title]
     [:button.delete
      {:on-click #(rf/dispatch [:app/hide-modal id])}]]
    [:section.modal-card-body
     body]
    [:footer.modal-card-foot
     footer]]])

(defn modal-button [id title body footer]
  [:div
   [:button.button.is-primary
    {:on-click #(rf/dispatch [:app/show-modal id])}
    title]
   [modal-card id title body footer]])

(rf/reg-event-db
 :auth/handle-login
 (fn [db [_ {:keys [identity]}]]
   (assoc db :auth/user identity)))

(rf/reg-event-db
 :auth/handle-logout
 (fn [db _]
   (dissoc db :auth/user)))

(rf/reg-sub
 :auth/user
 (fn [db _]
   (:auth/user db)))

(defn login-button []
  (r/with-let
    [fields (r/atom {})
     error    (r/atom nil)

     do-login
     (fn [_]
       (reset! error nil)
       (POST "/api/login"
         {:headers {"Accept" "application/transit+json"}
          :params @fields
          :handler (fn [response]
                     (reset! fields {})
                     (rf/dispatch [:auth/handle-login response])
                     (rf/dispatch [:app/hide-modal :user/login]))
          :error-handler (fn [error-response]
                           (reset! error
                                   (or (:message (:response error-response))
                                       (:status-text error-response)
                                       "Unknown Error")))}))]
    [modal-button :user/login
     ;; Title
     "Log In"
     ;; Body
     [:div
      (when-not (string/blank? @error)
        [:div.notification.is-danger
         @error])
      [:div.field
       [:div.label "Login"]
       [:div.control
        [:input.input
         {:type "text"
          :value (:login @fields)
          :on-change #(swap! fields assoc :login (.. % -target -value))}]]]
      [:div.field
       [:div.label "Password"]
       [:div.control
        [:input.input
         {:type "password"
          :value (:password @fields)
          :on-change #(swap! fields assoc :password (.. % -target -value))
          ;; Submit login form when `Enter` key is pressed
          :on-key-down #(when (= (.-keyCode %) 13)
                          (do-login))}]]]]
     ;; Footer
     [:button.button.is-primary.is-fullwidth
      {:on-click do-login
       :disabled (or (string/blank? (:login @fields)) (string/blank? (:password @fields)))}
      "Log In"]]))

(defn register-button []
  (r/with-let
    [fields (r/atom {})
     error    (r/atom nil)

     do-register
     (fn [_]
       (reset! error nil)
       (POST "/api/register"
         {:headers {"Accept" "application/transit+json"}
          :params @fields
          :handler (fn [response]
                     (reset! fields {})
                     (rf/dispatch [:app/hide-modal :user/register])
                     (rf/dispatch [:app/show-modal :user/login]))
          :error-handler (fn [error-response]
                           (reset! error
                                   (or (:message (:response error-response))
                                       (:status-text error-response)
                                       "Unknown Error")))}))]
    [modal-button :user/register
     ;; Title
     "Create Account"
     ;; Body
     [:div
      (when-not (string/blank? @error)
        [:div.notification.is-danger
         @error])
      [:div.field
       [:div.label "Login"]
       [:div.control
        [:input.input
         {:type "text"
          :value (:login @fields)
          :on-change #(swap! fields assoc :login (.. % -target -value))}]]]
      [:div.field
       [:div.label "Password"]
       [:div.control
        [:input.input
         {:type "password"
          :value (:password @fields)
          :on-change #(swap! fields assoc :password (.. % -target -value))}]]]
      [:div.field
       [:div.label "Confirm Password"]
       [:div.control
        [:input.input
         {:type "password"
          :value (:confirm @fields)
          :on-change #(swap! fields assoc :confirm (.. % -target -value))
          ;; Submit login form when `Enter` key is pressed
          :on-key-down #(when (= (.-keyCode %) 13)
                          (do-register))}]]]]
     ;; Footer
     [:button.button.is-primary.is-fullwidth
      {:on-click do-register
       :disabled (or (string/blank? (:login @fields))
                     (string/blank? (:password @fields))
                     (string/blank? (:confirm @fields)))}
      "Create Account"]]))

(defn logout-button []
  [:button.button
   {:on-click #(POST "/api/logout"
                 {:handler (fn [_] (rf/dispatch [:auth/handle-logout]))})}
   "Log Out"])

(defn nameplate [{:keys [login]}]
  [:button.button.is-primary
   login])

(defn text-input [{val :value
                   attrs :attrs
                   :keys [on-save]}]
  (let [draft (r/atom nil)
        value (r/track #(or @draft @val ""))]
    (fn []
      [:input.input
       (merge attrs
              {:type :text
               :on-focus #(reset! draft (or @val ""))
               :on-blur (fn []
                          (on-save (or @draft ""))
                          (reset! draft nil))
               :on-change #(reset! draft (.. % -target -value)) :value @value})])))

(defn textarea-input [{val :value
                       attrs :attrs
                       :keys [on-save]}]
  (let [draft (r/atom nil)
        value (r/track #(or @draft @val ""))]
    (fn []
      [:textarea.textarea
       (merge attrs
              {:on-focus #(reset! draft (or @val ""))
               :on-blur (fn []
                          (on-save (or @draft ""))
                          (reset! draft nil))
               :on-change #(reset! draft (.. % -target -value)) :value @value})])))

(defn handle-response! [response]
  (if-let [errors (:errors response)]
    (rf/dispatch [:form/set-server-errors errors])
    (do
      (rf/dispatch [:message/add response])
      (rf/dispatch [:form/clear-fields response]))))

(defn reload-messages-button []
  (let [loading? (rf/subscribe [:messages/loading?])]
    [:button.button.is-info.is-fullwidth
     {:on-click #(rf/dispatch [:messages/load])
      :disabled @loading?}
     (if @loading?
       "Loading Messages" "Refresh Messages")]))

(defn message-list [messages]
  [:ul.messages
   (for [{:keys [timestamp message name]} @messages]
     ^{:key timestamp}
     [:li
      [:time (.toLocaleString timestamp)]
      [:p message]
      [:p " - " name]])])

(defn errors-component [id]
  (when-let [error @(rf/subscribe [:form/error id])]
    [:div.notification.is-danger (string/join error)]))

(defn message-form []
  [:div
   [errors-component :server-error]
   [:div.field
    [:label.label {:for :name} "Name"]
    [errors-component :name]
    [text-input {:attrs {:name :name}
                 :value (rf/subscribe [:form/field :name])
                 :on-save #(rf/dispatch [:form/set-field :name %])}]]
   [:div.field
    [:label.label {:for :message} "Message"]
    [errors-component :message]
    [textarea-input
     {:attrs {:name :message}
      :value (rf/subscribe [:form/field :message])
      :on-save #(rf/dispatch [:form/set-field :message %])}]]
   [:input.button.is-primary
    {:type :submit
     :disabled @(rf/subscribe [:form/validation-errors?])
     :on-click #(rf/dispatch
                 [:message/send!
                  @(rf/subscribe [:form/fields])])
     :value "comment"}]])

(defn home []
  (let [messages (rf/subscribe [:messages/list])]
    (fn []
      [:div.content>div.columns.is-centered>div.column.is-two-thirds
       [:div.columns>div.column
        [:h3 "Messages"]
        [message-list messages]]
       [:div.columns>div.column
        [reload-messages-button]]
       [:div.columns>div.column
        [message-form]]])))

(defn navbar []
  (let [burger-active (r/atom false)]
    (fn []
      [:nav.navbar.is-info
       [:div.container
        [:div.navbar-brand
         [:a.navbar-item
          {:href "/"
           :style {:font-weight "bold"}}
          "guestbook"]
         [:span.navbar-burger.burger
          {:data-target "nav-menu"
           :on-click #(swap! burger-active not)
           :class (when @burger-active "is-active")}
          [:span]
          [:span]
          [:span]]]
        [:div#nav-menu.navbar-menu
         {:class (when @burger-active "is-active")}
         [:div.navbar-start
          [:a.navbar-item
           {:href "/"}
           "Home"]]
         [:div.navbar-end
          [:div.navbar-item
           (if-some [user @(rf/subscribe [:auth/user])]
             [:div.buttons
          ;; TODO
          ;; Style the user nameplate
          ;; Add logout event
              [nameplate user]
              [logout-button]]
             [:div.buttons
              [login-button]
              [register-button]])]]]]])))

(defn app []
  [:div.app
   [navbar]
   [:section.section
    [:div.container
     [home]]]])

(defn ^:dev/after-load mount-components []
  (rf/clear-subscription-cache!)
  (.log js/console "Mounting Components...")
  (dom/render [#'app] (.getElementById js/document "content"))
  (.log js/console "Components Mounted!"))

(defn init! []
  (.log js/console "Initializing App...")
  (mount/start)
  (rf/dispatch [:app/initialize])
  (mount-components))