(ns mikurabe.store
  "SSoT for the mikurabe (見比べ / comparative narrative-framing) actor — the
  append-only observation ledger behind a `Store` protocol so the backend is
  a swap, not a rewrite (MemStore default ‖ DatomicStore via langchain.db,
  itself swappable to real Datomic Local / kotoba-server pod e.g.
  kotobase.net).

  Domain: a topic-cluster (N kawaraban :mirror articles or N kouhou
  briefings on the SAME story, already public, passively read) → a set of
  citation-grounded framing-technique observations → a published
  frameObservation record on app-aozora (collection
  com.etzhayyim.apps.mikurabe.frameObservation). The append-only ledger is
  the publication provenance — every commit/hold/escalate is an immutable
  fact, never overwritten; a held (NarrativeGovernor-rejected) observation
  is recorded as a hold, never published.

  The store talks to its backend ONLY through the langchain.db `:db-api`
  map {:q :transact! :db :pull :entid}. `langchain.db/api` (in-process
  EAVT) and `langchain.kotoba-db/kotoba-api` (kotoba-server XRPC) both
  implement it, so the same `DatomicStore` record runs on either by
  construction."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [langchain.db :as d]))

(defprotocol Store
  (observation [s id] "the committed frameObservation record for a cluster-id, or nil")
  (all-observations [s])
  (ledger [s])
  (commit-observation! [s id payload] "commit one assessed frameObservation record")
  (append-ledger! [s fact] "append one immutable decision fact"))

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (observation [_ id] (get-in @a [:observations id]))
  (all-observations [_] (sort-by :cluster-id (vals (:observations @a))))
  (ledger [_] (:ledger @a))
  (commit-observation! [s id payload] (swap! a assoc-in [:observations id] payload) s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact))

(defn seed-db
  "An empty MemStore."
  []
  (->MemStore (atom {:observations {} :ledger []})))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────

(def ^:private schema
  {:mikurabe.observation/id  {:db/unique :db.unique/identity}
   :mikurabe.ledger/seq      {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

;; The store talks to its backend ONLY through the langchain.db `:db-api`
;; map {:q :transact! :db :pull :entid}. langchain.db/api (in-process EAVT)
;; and langchain.kotoba-db/kotoba-api (kotoba-server XRPC, e.g.
;; kotobase.net) both implement it, so the same record runs on either by
;; construction.

(defn- q* [{:keys [api conn]} query & inputs]
  (apply (:q api) query ((:db api) conn) inputs))
(defn- tx* [{:keys [api conn]} txd] ((:transact! api) conn txd))

(defrecord DatomicStore [api conn]
  Store
  (observation [this id]
    (dec* (q* this '[:find ?p . :in $ ?id :where
                     [?e :mikurabe.observation/id ?id]
                     [?e :mikurabe.observation/payload ?p]]
              id)))
  (all-observations [this]
    (->> (q* this '[:find [?id ...] :where [?e :mikurabe.observation/id ?id]])
         (map #(observation this %)) (sort-by :cluster-id)))
  (ledger [this]
    (->> (q* this '[:find ?s ?f :where
                    [?e :mikurabe.ledger/seq ?s] [?e :mikurabe.ledger/fact ?f]])
         (sort-by first) (mapv (comp dec* second))))
  (commit-observation! [s id payload]
    (tx* s [{:mikurabe.observation/id id :mikurabe.observation/payload (enc payload)}]) s)
  (append-ledger! [s fact]
    (tx* s [{:mikurabe.ledger/seq (count (ledger s)) :mikurabe.ledger/fact (enc fact)}]) fact))

(defn datomic-store
  "DatomicStore on the in-process langchain.db EAVT backend (verifiable
  offline, no network). For the kotoba-server pod (kotobase.net), bind the
  same record to langchain.kotoba-db/kotoba-api — same record, different
  :db-api (see docs/adr/0001-architecture.md)."
  []
  (->DatomicStore d/api (d/create-conn schema)))
