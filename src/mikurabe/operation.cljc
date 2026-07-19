(ns mikurabe.operation
  "NarrativeActor — one topic-cluster comparison = one supervised actor run,
  expressed as a langgraph-clj StateGraph. narrative-llm (the contained
  intelligence node) is sealed into :advise; its proposal is ALWAYS routed
  through the NarrativeGovernor (:govern) before anything commits to the
  SSoT or publishes to app-aozora. Mirrors the containment + independent-
  governor + append-only-ledger topology (tashikame.operation /
  yosoku.operation).

  Everything the actor depends on is injected (each a swap, not a rewrite):
    - the Store     (MemStore | DatomicStore | kotoba-server)  — `store` arg
    - the Advisor   (mock narrative-llm | real-LLM on Murakumo) — :advisor opt
    - the Publisher (Mock | real app-aozora createRecord)      — :publisher opt
    - the Phase     (0 observe → 1 autonomous-publish-non-named →
                      2 council-gated-named-party)             — ctx :phase

  One run = intake → advise → govern → decide → commit | hold | escalate.
  NO unbounded inner loop. Two independent human-in-the-loop / authorization
  mechanisms, borrowed from tashikame and yosoku respectively and combined
  here because mikurabe needs both:

    - The NarrativeGovernor's HARD violations (tashikame-style: no
      per-post prior restraint beyond the governor itself) are the only
      thing that withholds an ordinary, non-named-party observation from
      publication.
    - A NAMED-PARTY observation (`mikurabe.phase/observations-name-party?`)
      is a SEPARATE, additional gate (yosoku-style `interrupt-before` on
      :request-approval) — even a governor-clean named-party proposal
      cannot commit without an explicit approval AND the phase-2 Council
      gate being open. At R0/R1 the default phase is 0, so nothing
      auto-publishes at all, named-party or not (ADR-2607197800 honesty
      ladder).

    intake → advise(narrative-llm) → govern(NarrativeGovernor) → decide ─┬ commit ────────▶ END
                                                                          ├ escalate ──────▶ request-approval [interrupt-before]
                                                                          │                    resume ─▶ commit | hold
                                                                          └ hold ──────────▶ END"
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [mikurabe.advisor :as advisor]
            [mikurabe.governor :as governor]
            [mikurabe.phase :as phase]
            [mikurabe.publisher :as publisher]
            [mikurabe.store :as store]))

(defn- post-body [request proposal]
  (str "【見比べ】" (:cluster-id request) " — "
       (str/join " / " (map (comp name :technique) (:observations proposal)))))

(defn- record [request context proposal verdict]
  {:cluster-id   (:cluster-id request)
   :actor        (:actor-id context)
   :observations (:observations proposal)
   :confidence   (:confidence proposal)
   :collection   publisher/collection
   :text         (post-body request proposal)
   :warnings     (:warnings verdict)})

(defn build
  "Compiles the mikurabe NarrativeActor graph bound to `store`. opts:
    :advisor      — a `mikurabe.advisor/Advisor` (default: mock-advisor)
    :publisher    — a `mikurabe.publisher/Publisher` (default: mock-publisher)
    :checkpointer — langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor publisher checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    publisher    (publisher/mock-publisher)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; injected actor-id / phase
         :proposal    {:default nil}
         :verdict     {:default nil}   ; NarrativeGovernor result
         :disposition {:default nil}   ; :commit | :hold | :escalate
         :record      {:default nil}   ; the observation record to commit/publish
         :approval    {:default nil}   ; {:status :approved|:rejected :by ..}
         :published   {:default nil}   ; {:uri :cid} when published
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; narrative-llm (contained intelligence) — proposal only.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-frame advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      ;; NarrativeGovernor — independent censor (separate system than narrative-llm).
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal)}))

      ;; Decide: HARD violation → :hold. Governor-clean + names a party →
      ;; :escalate (Council gate). Governor-clean + non-named-party → :commit.
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (case (governor/verdict->disposition verdict)
            :hold
            {:disposition :hold
             :audit [(governor/hold-fact request context verdict)]}

            :commit
            (if (phase/observations-name-party? (:observations proposal) (:items request))
              {:disposition :escalate
               :audit [{:t :escalate-request :op (:op request)
                        :cluster-id (:cluster-id request)
                        :reason [:named-party] :confidence (:confidence verdict)}]}
              {:disposition :commit
               :record (record request context proposal verdict)}))))

      ;; Approval handoff — paused by interrupt-before; a Council reviewer
      ;; resumes with :approval. Even an :approved resume still needs the
      ;; phase-2 Council gate OPEN (mikurabe.phase/council-gate-open?) —
      ;; two independent locks, neither sufficient alone.
      (g/add-node :request-approval
        (fn [{:keys [request context proposal verdict approval]}]
          (cond
            (not= :approved (:status approval))
            {:disposition :hold
             :audit [(assoc (governor/hold-fact
                             request context
                             (update verdict :violations conj {:rule :approver-rejected}))
                           :t :approval-rejected)]}

            (not (phase/council-gate-open? (:phase context phase/default-phase)))
            {:disposition :hold
             :audit [(assoc (governor/hold-fact
                             request context
                             (update verdict :violations conj
                                     {:rule :named-party-gate-closed
                                      :detail "approved, but phase < 2 — named-party Council gate not open"}))
                           :t :approval-rejected)]}

            :else
            {:disposition :commit
             :record (record request context proposal verdict)
             :audit [{:t :approval-granted :op (:op request)
                      :cluster-id (:cluster-id request) :by (:by approval)}]})))

      ;; Commit — the ONLY node that writes the SSoT + audit ledger, and
      ;; (when the phase allows) publishes to app-aozora.
      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (let [ph       (:phase context phase/default-phase)
                publish? (and (phase/publish-allowed? ph)
                              (= :assessment (:effect proposal)))
                pub      (when publish? (publisher/publish! publisher record))
                f        {:t           :committed
                          :op          (:op request)
                          :actor       (:actor-id context)
                          :cluster-id  (:cluster-id request)
                          :disposition :commit
                          :published?  (boolean pub)
                          :pub         pub
                          :warnings    (:warnings record)
                          :observations (:observations proposal)}]
            (store/commit-observation! store (:cluster-id request) (dissoc record :warnings))
            (store/append-ledger! store f)
            {:published pub :audit [f]})))

      ;; Hold — write the rejection to the ledger; no SSoT mutation, no publish.
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer checkpointer
        :interrupt-before #{:request-approval}})))
