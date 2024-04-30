(ns aerospike.transact
  "Tests MRTs"
  (:require [aerospike.support :as s]
            [clojure.tools.logging :refer [debug info warn]]
            [jepsen [client :as client]
             [independent :as independent]]
            [jepsen.tests.cycle.wr :as rw]))


(def txn-set "Set Name for Txn Test" "entries")

(defn txn-wp [tid]
  (let [p s/write-policy]
    (set! (.tran p) tid)
    p
    )
  )

(defn mop!
  "Executes a transactional micro-op on a connection. Returns the completed
  micro-op."
  [conn tid [f k v]]
  [f k (case f
         :r (-> conn
                (s/fetch s/ans txn-set k tid)
                :bins
                :value
                (or []))
         :w (do 
              (let [wp (txn-wp tid)]
                (s/put! conn wp s/ans txn-set k {:value v})                
              )
              v)
        
  )]
  
  )

(defrecord TranClient [client namespace set]
  client/Client
  (open! [this _ node]
    (assoc this :client (s/connect node)))
  (setup! [this _] this)
  (invoke! [this test op]
    (info "Invoking" op)
    (if (= (:f op) :txn)   
      (s/with-errors op #{}
        ;; (do 
        (let [tid (.tranBegin client)
              ;; wp (txn-wp tid)
              txn (:value op)
              txn' (mapv (partial mop! client tid) txn)
              ]
          ;; (info "TRANSACTION!" tid "begin")
          ;; (mapv (partial mop! client wp) txn)
          ;; (info "TRANSACTION!" tid "ending")
          (.tranEnd client tid)
          
      ;; )
          (assoc op :type :ok :value txn')
          )
      )
      (info "REGULAR OP!"))
  )
  (teardown! [_ test])
  (close! [this test]
    (s/close client)))


(defn workload []
  {:client (TranClient. nil s/ans "vals")
   :checker (rw/checker)
   :generator (rw/gen {})}
)