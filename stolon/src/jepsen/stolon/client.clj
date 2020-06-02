(ns jepsen.stolon.client
  "Helper functions for interacting with PostgreSQL clients."
  (:require [clojure.tools.logging :refer [info]]
            [dom-top.core :refer [with-retry]]
            [jepsen [client :as client]
                    [util :as util]]
            [next.jdbc :as j]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql.builder :as sqlb])
  (:import (java.sql Connection)))

(def user
  "The user we use to connect to postgres. This is set, by Stolon, to the linux user that we used to run the keeper."
  "postgres")

(def password
  "The password we use to connect to postgres."
  "pw")

(defn open
  "Opens a connection to the given node."
  [node]
  (let [spec  {:dbtype    "postgresql"
               ;:dbname    "jepsen"
               :host      node
               :user      user
               :password  password
               ; The docs say ssl is a boolean but also it's mere *presence*
               ; implies using SSL, so... maybe we have to set disable too?
               :ssl       false
               ; OK neither of these apparently works, so... let's try in the
               ; server config.
               :sslmode   "disable"}
        ds    (j/get-datasource spec)
        conn  (j/get-connection ds)]
    conn))

(defn set-transaction-isolation!
  "Sets the transaction isolation level on a connection. Returns conn."
  [conn level]
  (.setTransactionIsolation
    conn
    (case level
      :serializable     Connection/TRANSACTION_SERIALIZABLE
      :repeatable-read  Connection/TRANSACTION_REPEATABLE_READ
      :read-committed   Connection/TRANSACTION_READ_COMMITTED
      :read-uncommitted Connection/TRANSACTION_READ_UNCOMMITTED))
  conn)

(defn close!
  "Closes a connection."
  [^java.sql.Connection conn]
  (.close conn))

(defn await-open
  "Waits for a connection to node to become available, returning conn. Helpful
  for starting up."
  [node]
  (with-retry [tries 100]
    (info "Waiting for" node "to come online...")
    (let [conn (open node)]
      (try (j/execute-one! conn
                           ["create table if not exists jepsen_await ()"])
           conn
           (catch org.postgresql.util.PSQLException e
             (condp re-find (.getMessage e)
               ; Ah, good, someone else already created the table
               #"duplicate key value violates unique constraint \"pg_type_typname_nsp_index\""
               conn

               (throw e)))))
    (catch org.postgresql.util.PSQLException e
      (when (zero? tries)
        (throw e))

      (Thread/sleep 5000)
      (condp re-find (.getMessage e)
        #"connection attempt failed"
        (retry (dec tries))

        #"Connection to .+ refused"
        (retry (dec tries))

        (throw e)))))

(defmacro with-errors
  "Takes an operation and a body; evals body, turning known errors into :fail
  or :info ops."
  [op & body]
  `(try ~@body
        (catch org.postgresql.util.PSQLException e#
          (condp re-find (.getMessage e#)
            #"ERROR: could not serialize access"
            (assoc ~op :type :fail, :error [:could-not-serialize (.getMessage e#)])

            #"ERROR: deadlock detected"
            (assoc ~op :type :fail, :error [:deadlock (.getMessage e#)])

            (throw e#)))))