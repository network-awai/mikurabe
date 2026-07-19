(ns mikurabe.aozora
  "Real app-aozora Publisher for mikurabe — creates a record in the
  com.etzhayyim.apps.mikurabe.frameObservation collection on an aozora PDS
  via the AT Protocol com.atproto.repo.createRecord XRPC, authenticated by a
  depth-1 self-minted CACAO (the actor's own did:key). Direct port of
  `tashikame.aozora` (itself ported from tsumugu.kotoba's CACAO mint + JDK
  http, and ossekai's app.bsky.feed.post publish path).

  I/O is injected: an http-fn (default JDK java.net.http, no dependency) and
  a JSON pair passed by the caller, so this namespace stays dependency-free.

  R0 status: STRUCTURALLY PRESENT, NOT WIRED BY DEFAULT. mikurabe's default
  Publisher is `mikurabe.publisher/mock-publisher`; `aozora-publisher` here
  is an opt-in seam an operator would inject explicitly — the same
  present-but-default-mock shape every sibling actor in this workspace
  ships at R0 (see MATURITY.md). Additionally, per ADR-2607197800, even
  when this publisher IS wired, mikurabe.operation's phase + named-party
  gates still decide whether any given record is ever handed to it at
  all — this namespace has no independent authority to bypass those gates."
  (:require [clojure.string :as str]
            [mikurabe.cacao :as cacao]
            [mikurabe.publisher :as publisher])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Instant]
           [java.util UUID]))

(def default-pds "https://pds.aozora.app")

(defn jvm-http-fn
  "host-caps :http-fn backed by the JDK HTTP client (no dependency)."
  [{:keys [url method headers body]}]
  (let [b (HttpRequest/newBuilder (URI/create url))]
    (doseq [[k v] headers] (.header b k v))
    (let [req  (-> b (.method (str/upper-case (name (or method :post)))
                             (if body
                               (HttpRequest$BodyPublishers/ofString body)
                               (HttpRequest$BodyPublishers/noBody)))
                   (.build))
          resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp)})))

(defn aozora-publisher
  "Returns a `mikurabe.publisher/Publisher` that creates frameObservation
  records on the aozora PDS. opts:
    :pds         PDS base URL (default default-pds)
    :identity    {:private-key :did …} from cacao/load-or-create-identity!
    :leash       a member CACAO b64 (the revocable off-switch); nil → record
                 attributed to the actor's own did:key (depth-1 self-mint)
    :json-write  :json-read  injected JSON fns (e.g. clojure.data.json)
    :http-fn     optional override (default jvm-http-fn)"
  [{:keys [pds identity json-write json-read http-fn]
    :or   {pds default-pds http-fn jvm-http-fn}}]
  (assert (:did identity) ":identity with :did is required (cacao/load-or-create-identity!)")
  (assert json-write ":json-write fn is required (e.g. clojure.data.json/write-str)")
  (assert json-read  ":json-read fn is required (e.g. clojure.data.json/read-str)")
  (reify publisher/Publisher
    (publish! [_ record]
      ;; app-aozora-pds auth (self-sovereign CACAO): mint a CACAO for the
      ;; actor's OWN did:key, exchange it at createSession for an HS256
      ;; session JWT, then createRecord with that JWT — the PDS enforces
      ;; session DID == repo DID, so the repo is addressed by the actor's
      ;; did:key.
      (let [now   (str (Instant/now))
            graph (cacao/canonical-graph (:did identity) cacao/default-db-name)
            cacao (cacao/mint identity
                              {:cap :cap/transact :scope graph}
                              {:aud pds :nonce (str (UUID/randomUUID))
                               :issued-at now
                               :expiry (str (.plusSeconds (Instant/now) 3600))})
            sess  (http-fn {:url     (str pds "/xrpc/com.atproto.server.createSession")
                            :method  :post
                            :headers {"Content-Type" "application/json"}
                            :body    (json-write {:cacao cacao})})
            sbody (json-read (:body sess))
            jwt   (get sbody "accessJwt")]
        (when-not (and (= 200 (:status sess)) jwt)
          (throw (ex-info "aozora createSession failed"
                          {:status (:status sess) :body (:body sess)})))
        (let [coll  (or (:collection record) publisher/collection)
              rec   (-> (dissoc record :rkey :collection)
                        (assoc :createdAt now :actor (:did identity)))
              resp  (http-fn {:url     (str pds "/xrpc/com.atproto.repo.createRecord")
                              :method  :post
                              :headers {"Content-Type" "application/json"
                                        "Authorization" (str "Bearer " jwt)}
                              :body    (json-write {:repo       (:did identity)
                                                    :collection coll
                                                    :rkey       (or (:rkey record) "self")
                                                    :record     rec})})
              rbody (json-read (:body resp))]
          (when-not (= 200 (:status resp))
            (throw (ex-info "aozora createRecord failed"
                            {:status (:status resp) :body (:body resp)})))
          {:uri (get rbody "uri") :cid (get rbody "cid")})))))
