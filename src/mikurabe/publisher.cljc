(ns mikurabe.publisher
  "Publisher — the outbound surface for a mikurabe frameObservation record,
  injected so the network is a swap (MockPublisher default ‖ real app-aozora
  createRecord via `mikurabe.aozora`, present but unwired — same
  present-but-default-mock shape as tashikame). The graph never reaches the
  network directly; `:commit` calls `(publish! publisher record)` only after
  the NarrativeGovernor passed AND the phase allows publication AND (for a
  named-party record) the Council gate is open.

  record shape (what gets published):
    {:cluster-id :actor :observations :confidence :text (social-post body)
     :collection \"com.etzhayyim.apps.mikurabe.frameObservation\"}")

(def collection "com.etzhayyim.apps.mikurabe.frameObservation")

(defprotocol Publisher
  (publish! [p record] "publish one frameObservation record → {:uri :cid}"))

(defrecord MockPublisher [a]
  Publisher
  (publish! [_ record]
    (swap! a conj record)
    {:uri (str "at://mock/mikurabe/" (:cluster-id record))
     :cid (str "mock:" (:cluster-id record))}))

(defn mock-publisher
  "Deterministic in-memory publisher (default — records would-be posts).
  Optional atom arg lets a test read back what would have been published."
  ([] (->MockPublisher (atom [])))
  ([a] (->MockPublisher a)))
