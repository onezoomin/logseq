(ns frontend.modules.crdt.yjs
  (:require ["yjs" :as y]
            ["@logseq/y-websocket" :as y-ws]
            [goog.object :as gobj]
            [clojure.string :as string]
            [datascript.core :as d]
            [frontend.db :as db]
            [frontend.util :as util]
            [frontend.state :as state]
            [cljs-bean.core :as bean]
            [frontend.modules.crdt.outliner :as outliner]
            [frontend.modules.outliner.pipeline :as outliner-pipeline]
            [clojure.edn :as edn]
            [clojure.walk :as walk]))

(defonce *server-conn (atom nil))

;;; TODO:
;; 1. persistence
;; Use IndexedDB because it's supported on both Electron and Web.
;; Need a custom implementation for subdocs support

;; 2. end-to-end encryption

;;; Some tricky cases that need more thoughts
;; 1. A page has different uuid in two clients (same page name), how to merge their contents
;;    if both are just default content from the same template?
;; 2. Client A inserted some blocks after the block A, client B deleted the block
;;    concurrently, what should be the result?

;; `map` will be pages and those pages' corresponding subdocuments.
;; {graph {:local {:doc Y.Doc :map Y.Map}
;;         :remote {:doc Y.Doc :map Y.Map}}}
(defonce *state (atom {}))

(defonce YDoc (gobj/get y "Doc"))

(defn merge-doc [doc1 doc2]
  (let [s1 (y/encodeStateVector doc1)
        s2 (y/encodeStateVector doc2)
        d1 (y/encodeStateAsUpdate doc1 s2)
        d2 (y/encodeStateAsUpdate doc2 s1)]
    (y/applyUpdate doc1 d2)
    (y/applyUpdate doc2 d1)))

(defn sync-doc [local remote]
  (.on remote "update" (fn [update]
                         ;; TODO: applyupdate here doesn't work for subdocs
                         ((gobj/get y "logUpdate") update)
                         (y/applyUpdate local update)))
  (.on local "update" (fn [update]
                        (y/applyUpdate remote update))))

(defn get-local-doc
  [graph]
  (get-in @*state [graph :local :doc]))

(defn get-local-map
  [graph]
  (get-in @*state [graph :local :map]))

(defn get-remote-doc
  [graph]
  (get-in @*state [graph :remote :doc]))

(defn get-remote-map
  [graph]
  (get-in @*state [graph :remote :map]))

(defn unobserve-local-map!
  [graph f]
  (when-let [ym (get-local-map graph)]
    (.unobserve ym f)))

(defn observe-local-map!
  [graph f]
  (let [ym (get-local-map graph)]
    ;; Note: observeDeep not working for subdoc changes
    (.observeDeep ym f)))

(declare handle-local-updates!)
;; TODO: DRY
(defn- observe!
  [graph ^js doc-local]
  (let [ymap (.getMap doc-local)]
    (prn "observe local map")
    (.observeDeep ymap (handle-local-updates! graph ymap))))

(defn- merge-sync-and-observe!
  [graph doc-local doc-remote]
  (merge-doc doc-local doc-remote)
  (sync-doc doc-local doc-remote)

  (observe! graph doc-local))

(defn- load-and-observe-page-blocks-doc!
  [graph ^js doc-local]
  ;; Load it first
  (.load doc-local)
  (when-let [doc-remote (.getSubDoc ^js @*server-conn (.-guid doc-local))]
    (prn {:doc-remote doc-remote})
    (merge-sync-and-observe! graph doc-local doc-remote)))

(defn handle-local-updates!
  [graph ymap]
  (fn [events]
    (prn "handle-local-updates! root-map?" (= ymap (get-local-map graph)))
    (js/console.dir events)
    (doseq [event events]
      (when-not (.-local (.-transaction event)) ; ignore local changes
        (let [changed-keys (.-keysChanged event)
              changes (keep
                       (fn [uuid-str _item]
                         (when-let [v ^js (.get ymap uuid-str)]
                           (when-not (string? v) ; yjs subdoc
                             (load-and-observe-page-blocks-doc! graph v))
                           (when (string? v)
                             (if v
                               {:action :upsert
                                :block (edn/read-string v)}
                               {:action :delete
                                :block-id (uuid uuid-str)}))))
                       changed-keys)]
          (prn {:changes changes})
          (state/pub-event! [:graph/merge-remote-changes graph changes event]))))))

(defn- get-page-blocks-uuids [db page-id]
  (->> (d/datoms db :avet :block/page page-id)
       (map (fn [d] (:block/uuid (d/entity db (:e d)))))))

(defn- replace-db-id-with-block-uuid
  [tx-report block]
  (walk/postwalk (fn [f]
                   (if (and (map? f)
                            (= 1 (count f))
                            (:db/id f))
                     (let [block-uuid (or (:block/uuid (d/entity (:db-before tx-report) (:db/id f)))
                                          (:block/uuid (d/entity (:db-after tx-report) (:db/id f))))]
                       (if block-uuid
                         [:block/uuid block-uuid]
                         (throw (ex-info "Can't resolve entity in both db-before and db-after"
                                         {:block block
                                          :f f}))))
                     f))
                 block))

;; TODO: merge pages, page names are same but with different uuids
(defn- transact-blocks!
  [tx-report graph pages blocks]
  (let [ydoc (get-local-doc graph)
        ymap (get-local-map graph)]
    ;; bundle changes to minimize numbers of messages sent
    (.transact
     ydoc
     (fn []
       (doseq [page pages]
         (let [k (str (:block/uuid page))
               page (dissoc page :db/id)]
           (if (:db/deleted? page)
             (do
               (.unobserve ymap (handle-local-updates! graph ymap))
               (.delete ymap k)
               (when-let [page-blocks-doc (.get ymap (str (:block/uuid page) "-blocks"))]
                 (.destroy page-blocks-doc)))
             (.set ymap k (pr-str page)))))
       (doseq [block (util/distinct-by-last-wins :block/uuid (concat pages blocks))]
         (let [block' (->> (dissoc block :db/id)
                           (replace-db-id-with-block-uuid tx-report))
               k (str (:block/uuid block'))]
           (when-let [page (:block/page block')]
             (let [k' (str (second page) "-blocks")
                   blocks-doc (.get ymap k')
                   blocks-ymap (if blocks-doc
                                 (.getMap ^js blocks-doc)
                                 (let [doc ^js (new YDoc
                                                    ;; #js {:autoLoad true}
                                                    )
                                       _ (.set ymap k' doc)
                                       blocks-ymap (.getMap doc)]
                                   (load-and-observe-page-blocks-doc! graph doc)
                                   blocks-ymap))]
               (if (:db/deleted? block')
                 ;; delete non-page block
                 (.delete blocks-ymap k)
                 ;; FIXME: construct a Y.Map from `block`
                 ;; e.g. rich text editor
                 (let [block-value (pr-str block')]
                   (.set blocks-ymap k block-value)))))))))))

(defn save-db-changes-to-yjs!
  "Save datascript changes to yjs."
  [graph {:keys [pages blocks tx-report]}]
  ;; TODO: core.async batch updates
  (try
    (when-not (:skip-remote-sync? (:tx-meta tx-report))
      (transact-blocks! tx-report graph pages blocks))
    (catch :default e
      (js/console.error e))))

(defn register-db-listener!
  []
  (outliner-pipeline/register-listener! :save-db-changes-to-yjs save-db-changes-to-yjs!))

(defn start-yjs-docs! [graph]
  (let [{:keys [local remote]} @*state]
    (when-let [doc-remote (:doc local)]
      (.destroy doc-remote))
    (when-let [doc-local (:doc local)]
      (.destroy doc-local))

    (unobserve-local-map! graph (handle-local-updates! graph
                                                       (get-local-map graph)))

    (let [doc-local (new YDoc)
          doc-remote (new YDoc)]
      (reset! *state {graph {:local {:doc doc-local
                                     :map (.getMap doc-local graph)}
                             :remote {:doc doc-remote
                                      :map (.getMap doc-remote graph)}}})
      (register-db-listener!)
      (merge-doc doc-local doc-remote)
      (sync-doc doc-local doc-remote)
      (observe-local-map! graph (handle-local-updates! graph
                                                       (get-local-map graph))))))

;; doc map for each graph
;; {block-uuid {:block/uuid :block/name :block/parent :block/left :block/content}}

(defn setup-sync-server! [server-address graph user]
  (when (and (not (string/blank? server-address))
             (not (string/blank? graph))
             (not (string/blank? user)))
    (println "setup-sync-server! " {:server-address server-address
                                    :graph graph
                                    :user user})
    (when @*server-conn
      (.disconnect @*server-conn))

    (start-yjs-docs! graph)

    (reset! *server-conn (y-ws/WebsocketProvider. server-address graph (get-remote-doc graph)))))

(defn server-connected? []
  (and (some? @*server-conn)
       (.-wsconnected ^js @*server-conn)))

(defn serialize
  [ydoc]
  ((gobj/get y "encodeStateAsUpdate") ydoc))

(defn debug-sync!
  []
  ;; 1. start websocket server
  ;;    git clone git@github.com:logseq/y-websocket
  ;;    cd y-websocket && node bin/server.js
  ;; 2. change localhost to your local ip address to test concurrent edits
  ;; on both pc and mobile :)
  (let [server-address "ws://localhost:1234"]
    (setup-sync-server! server-address (frontend.state/get-current-repo)
                        (str (random-uuid)))))

(debug-sync!)

(comment
  (frontend.db/set-key-value (state/get-current-repo) :db-type :db-only)

  (defn- get-yjs-map-keys
    []
    (keys (cljs-bean.core/->clj (.toJSON (get-local-map (state/get-current-repo))))))
  )