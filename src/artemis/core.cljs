(ns artemis.core
  (:refer-clojure :exclude [read])
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [artemis.stores.mapgraph.core :as mgs]
            [artemis.stores.protocols :as sp]
            [artemis.network-steps.http :as http]
            [artemis.network-steps.protocols :as np]
            [artemis.document :as d]
            [artemis.result :refer [result->message]]
            [clojure.spec.alpha :as s]
            [cljs.core.async :as async]
            [cljs.core.async.impl.protocols :refer [WritePort ReadPort]]))

(defrecord
  ^{:added "0.1.0"}
  Client
  [store network-chain])

(s/def ::store #(satisfies? sp/GQLStore %))
(s/def ::network-chain #(satisfies? np/GQLNetworkStep %))
(s/def ::client #(instance? Client %))

(s/fdef create-client
        :args (s/cat :options (s/keys* :opt-un [::store ::network-chain]))
        :ret  ::client)

(defn create-client
  "Returns a new `Client` specified by `:store` and `:network-chain`."
  {:added "0.1.0"}
  [& {:keys [store network-chain]
      :or   {store         (mgs/create-store)
             network-chain (http/create-network-step)}}]
  (Client. (atom store) network-chain))

(s/fdef store
        :args (s/cat :client ::client)
        :ret  (s/nilable ::store))

(defn store
  "Returns the client's store. Returns `nil` if no store exists for the
  client."
  {:added "0.1.0"}
  [client]
  (some-> client :store deref))

(s/fdef network-chain
        :args (s/cat :client ::client)
        :ret  (s/nilable ::network-chain))

(defn network-chain
  "Returns the client's network chain. Returns `nil` if no network chain exists
  for the client."
  {:added "0.1.0"}
  [client]
  (:network-chain client))

(s/fdef client?
        :args (s/cat :x any?)
        :ret  boolean?)

(defn client?
  "Returns `true` if `x` is a valid client."
  {:added "0.1.0"}
  [x]
  (instance? Client x))

(s/def ::document d/doc?)
(s/def ::variables (s/nilable map?))
(s/def ::name string?)
(s/def ::operation (s/keys :req-un [::document] :opt-un [::variables ::name]))
(s/def ::context map?)
(s/def ::chan (s/and #(satisfies? WritePort %) #(satisfies? ReadPort %)))

(s/fdef exec
        :args (s/cat :network-chain ::network-chain
                     :operation     ::operation
                     :context       ::context)
        :ret  ::chan)

(defn exec
  "Calls `artemis.network-steps.protocols/-exec` on a given network chain."
  {:added "0.1.0"}
  [network-chain operation context]
  (np/-exec network-chain operation context))

(s/def ::data any?)
(s/def ::return-partial? boolean?)
(s/def ::result (s/keys :req-un [::data] :opt-un [::return-partial?]))

(s/fdef read
        :args (s/cat :store           ::store
                     :document        ::document
                     :variables       ::variables
                     :return-partial? ::return-partial?)
        :ret  (s/nilable ::result))

(defn read
  "Calls `artemis.stores.protocols/-read` on a given store."
  {:added "0.1.0"}
  [store document variables return-partial?]
  (sp/-read store document variables return-partial?))

(s/fdef write
        :args (s/cat :store           ::store
                     :data            ::data ;; Figure out the right names for all of these things
                     :document        ::document
                     :variables       ::variables)
        :ret  ::store)

(defn write
  "Calls `artemis.stores.protocols/-write` on a given store."
  {:added "0.1.0"}
  [store data document variables]
  (sp/-write store data document variables))

(defn- vars-and-opts [args]
  (let [variables   (when (map? (first args)) (first args))
        options     (if variables (next args) args)]
    {:variables variables
     :options   options}))

(defn- update-store! [client new-store]
  (update client :store reset! new-store))

(s/def ::out-chan ::chan)
(s/def ::fetch-policy #{:local-only :local-first :local-then-remote :remote-only})

(s/fdef query!
        :args (s/alt
               :arity-2 (s/cat :client   ::client
                               :document ::document)
               :arity-n (s/cat :client    ::client
                               :document  ::document
                               :variables (s/? map?)
                               :options   (s/keys* :opt-un [::out-chan
                                                            ::fetch-policy
                                                            ::return-partial? ;; maybe
                                                            ::context])))
        :ret  ::out-chan)

(defn query!
  "Given a client, document, and optional `:variables` and `:options`, returns
  a channel that will receive the response(s) for a query. Depending on the
  `:fetch-policy` option, the channel will receive one or more messages.

  The `:variables` argument is a map of variables for the GraphQL query.

  The `:options` argument is a map optionally including:

  - `:out-chan`     The channel to put query messages onto. Defaults to
                    `(cljs.core.async/channel)`.
  - `:context`      A map of context to pass along when executing the network
                    chain. Defaults to `{}`.
  - `:fetch-policy` A keyword specifying the fetch policy _(see below)_.
                    Defaults to `:local-only`.

  The available fetch policies and corresponding implications are:

  #### `:local-only`
  A query will never be executed remotely. Instead, the query will only run
  against the local store. If the query can't be satisfied locally, an error
  message will be put on the return channel. This fetch policy allows you to
  only interact with data in your local store without making any network
  requests which keeps your component fast, but means your local data might not
  be consistent with what is on the server. For this reason, this policy should
  only be used on data that is highly unlikely to change, or is regularly being
  refreshed.

  #### `:local-first`
  Will run a query against the local store first. The result of the local query
  will be put on the return channel. If that result is a non-nil value, then a
  remote query will not be executed. If the result is `nil`, meaning the data
  isn't available locally, a remote query will be executed. This fetch policy
  aims to minimize the number of network requests sent. The same cautions
  around stale data that applied to the `:local-only` policy do so for this
  policy as well.

  #### `:local-then-remote`
  Like the `:local-first` policy, this will run a query against the local store
  first and put the result on the return channel.  However, unlike
  `:local-first`, a remote query will always be executed regardless of the
  value of the local result. This fetch policy optimizes for users getting a
  quick response while also trying to keep cached data consistent with your
  remote data at the cost of extra network requests.

  #### `:remote-only`
  This fetch policy will never run against the local store.  Instead, it will
  always execute a remote query. This policy optimizes for data consistency
  with the server, but at the cost of an instant response."
  {:added "0.1.0"}
  ([client document]
   (query! client document {}))
  ([client document & args]
   (let [{:keys [variables options]} (vars-and-opts args)
         {:keys [out-chan fetch-policy context]
          :or   {out-chan     (async/chan)
                 context      {}
                 fetch-policy :local-only}} options
         local-read  #(read @(:store client)
                            document
                            variables
                            (get options :return-partial? false))
         remote-read #(exec (:network-chain client)
                            {:document  document
                             :variables variables}
                            context)]
     (case fetch-policy
       :local-only
       (let [local-result (local-read)]
         (async/put! out-chan
                     (-> local-result
                         result->message
                         (assoc :variables      variables
                                :in-flight?     false
                                :network-status :ready)))
         (async/close! out-chan))

       :local-first
       (let [local-result    (local-read)
             nil-local-data? (nil? (:data local-result))]
         (async/put! out-chan
                     (-> local-result
                         result->message
                         (assoc :variables      variables
                                :in-flight?     nil-local-data?
                                :network-status (if nil-local-data? :fetching :ready))))
         (if nil-local-data?
           (let [remote-result-chan (remote-read)]
             (go (let [remote-result (async/<! remote-result-chan)
                       message       (result->message remote-result)]
                   (update-store! client (write @(:store client)
                                                message
                                                document
                                                variables))
                   (async/put! out-chan (assoc message
                                               :variables      variables
                                               :in-flight?     false
                                               :network-status :ready))
                   (async/close! out-chan))))
           (async/close! out-chan)))

       :local-then-remote
       (let [local-result       (local-read)
             remote-result-chan (remote-read)]
         (async/put! out-chan
                     (-> local-result
                         result->message
                         (assoc :variables      variables
                                :in-flight?     true
                                :network-status :fetching)))
         (go (let [remote-result (async/<! remote-result-chan)
                   message       (result->message remote-result)]
               (update-store! client (write @(:store client)
                                            message
                                            document
                                            variables))
               (async/put! out-chan (assoc message
                                           :variables      variables
                                           :in-flight?     false
                                           :network-status :ready))
               (async/close! out-chan))))

       :remote-only
       (let [remote-result-chan (remote-read)]
         (async/put! out-chan {:data           nil
                               :variables      variables
                               :in-flight?     true
                               :network-status :fetching})
         (go (let [remote-result (async/<! remote-result-chan)
                   message       (result->message remote-result)]
               (update-store! client (write @(:store client)
                                            message
                                            document
                                            variables))
               (async/put! out-chan (assoc message
                                           :variables      variables
                                           :in-flight?     false
                                           :network-status :ready))
               (async/close! out-chan))))

       (throw (ex-info (str "Invalid :fetch-policy. Must be one of #{:local-only"
                            " :local-first :local-then-remote :remote-only}.")
                       {:reason ::invalid-fetch-policy
                        :value  fetch-policy})))
     out-chan)))

(s/fdef mutate!
        :args (s/alt
               :arity-2 (s/cat :client   ::client
                               :document ::document)
               :arity-n (s/cat :client    ::client
                               :document  ::document
                               :variables (s/? map?)
                               :options   (s/keys* :opt-un [::out-chan
                                                            ::optimistic-result
                                                            ::context])))
        :ret  ::out-chan)

(defn mutate!
  "Given a client, document, and optional `:variables` and `:options`, returns
  a channel that will receive the response(s) for a mutation.

  The `:variables` argument is a map of variables for the GraphQL mutation.

  The `:options` argument is a map optionally including:

  - `:out-chan`          The channel to put mutation messages onto. Defaults to
                         `(cljs.core.async/channel)`.
  - `:context`           A map of context to pass along when executing the
                          network chain. Defaults to `{}`.
  - `:optimistic-result` A map describing an anticipated/optimistic result.
                         The optimistic result will be put onto the channel
                         before waiting for a successful mutation response."
  {:added "0.1.0"}
  ([client document]
   (mutate! client document {}))
  ([client document & args]
   (let [{:keys [variables options]} (vars-and-opts args)
         {:keys [out-chan optimistic-result context]
          :or   {out-chan (async/chan)
                 context  {}}} options]
     (when optimistic-result
       (update-store! client (write @(:store client)
                                    {:data optimistic-result}
                                    document
                                    variables)))
     (async/put! out-chan
                 {:data           optimistic-result
                  :variables      variables
                  :in-flight?     true
                  :network-status :fetching})
     (go (let [result  (<! (exec (:network-chain client)
                                 {:document  document
                                  :variables variables}
                                 context))
               message (result->message result)]
           (update-store! client (write @(:store client)
                                        message
                                        document
                                        variables))
           (async/put! out-chan
                       (assoc message
                              :variables      variables
                              :in-flight?     false
                              :network-status :ready))
           (async/close! out-chan)))
     out-chan)))