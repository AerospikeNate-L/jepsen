(ns aerospike.txn_test
  "Tests MRTs"
  (:require [aerospike.support :as s]
            [clojure.tools.logging :refer [debug info warn]]
            [jepsen [client :as client]
                    [independent :as independent]]
            [jepsen.tests.cycle.append :as append]))

(def txn-set "Set Name for Txn Test->'entries'" "entries")

(defn mop!
  "Executes a transactional micro-op on a connection. Returns the completed
  micro-op."
  [conn test [f k v]]
  [f k (case f
         :r (-> conn
                (s/fetch s/ans txn-set k)
                :bins
                :value
                (or ""))
         :append (s/append-to-list-or-create conn s/ans txn-set k {:value v}))])

(defrecord TxnClient [client namespace set]
  client/Client
  (open! [this _ node]
    (assoc this :client (s/connect node)))
  (setup! [this _] this)
  (invoke! [this test op]
    (info "Invoking" op)
    (s/with-errors op #{}
      (let [txn       (:value op)
            use-txn?  (< 1 (count txn))
            txn'   (mapv (partial mop! client test) txn)]  ;; TODO - make this do the txn and become the result
        (info "OP OK! -->>" op)
        (info "RESULT -->>" txn')
        
        ;; (info "ResVal -->>" (get-in (.bins (nth txn' 2)) ["value"]))
        (assoc op :type :ok, :value txn')
        )))
  (teardown! [_ test])
  (close! [this test]
    (s/close client)))

(defn txn-client
  []
  (TxnClient. nil s/ans "entries"))


(defn workload
  []
  {:client (txn-client)
   :checker (append/checker )
   :generator (append/gen {})})