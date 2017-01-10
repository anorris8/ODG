(ns odg.annotation
  (:import (org.neo4j.graphdb Transaction)
           (org.neo4j.unsafe.batchinsert BatchInserterIndex))
  (:require clojure.java.io
            clojure.string
            [clojure.core.reducers :as r]
            [odg.util :as util]
            [odg.db :as db]
            [biotools.gff :as gff]
            [biotools.gtf :as gtf]
            [odg.batch :as batch]
            [taoensso.timbre :as timbre]
            [co.paralleluniverse.pulsar.core :as p]))

(timbre/refer-timbre)

; Need to make individual get-gene-node type fn's return a promise and be put into a fiber
; so that nothing gets held up and no fiber is allowed to go for too long..

; reminder for debugging

(defn generic-entry
  [id type start end strand phase landmark species version note additional-data]
  (merge
    additional-data ; Should always be overwritten by passed arguments
    {:id id
     :type type
     :start start
     :end end
     :strand strand
     :phase phase
     :landmark landmark
     :note note
     :species species
     :version version}))



(p/defsfn get-exon-node
  "Checks for the existence of the exon node and creates it, if necessary.
  If it is created it is connected to its parent transcript/mRNA node as well"
  [create-fn idx-query entry transcript]
  (let [exon-id (str (:oid entry) "_exon_" (:exon_number entry))
        db-entry (idx-query exon-id)]
    (if-not @db-entry
      (let [exon (create-fn
                   ; Create the gene entry
                   (generic-entry
                     exon-id
                     "exon"
                     (:start entry)
                     (:end entry)
                     (:strand entry)
                     (:phase entry)
                     (:landmark entry)
                     (:species entry)
                     (:version entry)
                     "Autogenerated from GTF import"
                     entry))]
        ;(batch/create-rel transcript exon (:PARENT_OF db/rels))
        exon)
      db-entry)))

; UPDATED FOR ACTOR SYSTEM
; BUT UNTESTED
(defn import-gtf
  "Batch import GTF file into existing database. Differs very little over the GFF parser.
  Has additional support for creating gene records, however. Designed for cufflinks created GTF files at this time."
  [species version filename]
  
  (let [species-label (batch/dynamic-label species)
        version-label (batch/dynamic-label (str species " " version))
        labels (partial into [species-label version-label])
        species_ver_root 1
        ]

    (println "Importing GTF annotation for" species version "from" filename)
    
    (with-open [rdr (clojure.java.io/reader filename)]
      (let [all-entries 
            (group-by
              (memoize (fn [x] (util/remove-transcript-id (:oid x))))
              (vec (gtf/parse-reader-reducer rdr)))]
        (merge 
          {:indices [(batch/convert-name species version)]}
          (apply 
            merge-with 
            concat
            (for [[gene-id entries] all-entries]
              (when (and gene-id (seq entries))
                {:nodes-create-if-new
                 (distinct
                   (concat
                     ; Create "gene" node (if necessary)
                     (list
                       (let [entry (first entries)]
                         [{:id gene-id
                           :start (apply min (remove nil? (map :start entries)))
                           :end (apply max (remove nil? (map :end entries)))
                           :note "Autogenerated from GTF file"
                           :strand (:strand entry)
                           :phase (:phase entry)
                           :landmark (:landmark entry)
                           :species species
                           :version version
                           :cufflinks_gene_id (:gene_id entry)
                           }
                          (labels [(:GENE batch/labels) (:ANNOTATION batch/labels)])
                  
                          ; optional rels to create if node is created
                          (for [landmark (util/get-landmarks 
                                           (:landmark entry) 
                                           (apply min (remove nil? (map :start entries))) 
                                           (apply max (remove nil? (map :end entries))))]
                            [(:LOCATED_ON db/rels) gene-id landmark])
                          ]))
               
                     ; Create transcript nodes if necessary
                     (distinct
                       (for [[transcript-id transcript-entries] (group-by :oid entries)]
                         [{:id transcript-id
                           :start (apply min (map :start entries))
                           :end (apply max (map :end entries))
                           :species species
                           :version version
                           :note "Autogenerated from GTF import"
                           :cufflinks_gene_id (:gene_id (first entries))
                           :transcript_id (:transcript_id (first entries))}
                          (labels [(:MRNA batch/labels) (:ANNOTATION batch/labels)])
                          [(:PARENT_OF db/rels) gene-id transcript-id]]))))
             
                 ; TODO: Also check or add exon nodes from GTF files! Not priority
                 }))))))))

(defn import-gtf-cli
  "Import annotation - helper fn for when run from the command-line (opposed to entire database generation)"
  [config opts args]
  
;  (batch/connect (get-in config [:global :db_path] (:memory opts)))
  (import-gtf (:species opts) (:version opts) (first args)))


(defn create-missing-mRNA
  [entries]
  ; Check for existence of CDS but not mRNA entries...
  ; Then change the type and re-run
  (let [types (set (map :type entries))]
    (if (and
          (not (get types "mRNA"))
          (get types "CDS"))
    nil)
    ; do stuff here..
    
    ))

; NEW - actor based system
(defn import-gff
  "Batch import GFF file into existing database."
  [species version filename]

  (info "Importing annotation for" species version filename)

  ; Keep the reader outside to facilitate automatic closing of the file
  (let [species-label (batch/dynamic-label species)
        version-label (batch/dynamic-label (str species " " version))
        labels (partial into [species-label version-label])
        species_ver_root 1 ; TODO: Fix me!
        ]

    (with-open [rdr (clojure.java.io/reader filename)]
      (let [current-ids (atom (hash-set))
            entries (doall (map #(batch/gen-id current-ids %) (gff/parse-reader rdr))) ; make unique IDs, when necessary
            final-ids (set (map :id entries)) ; get the final list of IDs
            ]

        {:species species
         :version version
         :nodes (into
                  []
                  (for [entry entries]
                    [(reduce-kv #(assoc %1 %2 (if (string? %3)
                                                (java.net.URLDecoder/decode %3)
                                                %3)) {} (merge entry {:species species :version version}))
                     (labels [(:ANNOTATION batch/labels) (batch/dynamic-label (:type entry))])]))
         
         
         :rels (into
                 []
                 (concat
                   ; Parental relationships
           (for [entry (filter :parent entries) ; GFF entries with a parent attribute
                 parent-id (clojure.string/split (:parent entry) #",")] 
                     [(:PARENT_OF db/rels) parent-id (:id entry)])
                   
                   ; Located_on relationships

                   (for [entry entries ; Connect to landmarks (when appropriate)
                         landmark (util/get-landmarks (:landmark entry) (:start entry) (:end entry))
                         :when (not (:parent entry))] ; Store a LOCATED_ON relationship when there is no parental relationship
                     [(:LOCATED_ON db/rels) (:id entry) landmark])))
         
         :indices [(batch/convert-name species version)]
         }))))

(defn import-gff-cli
  "Import annotation - helper fn for when run from the command-line (opposed to entire database generation)"
  [config opts args]
  
  ;(batch/connect (get-in config [:global :db_path]) (:memory opts))
  (import-gff (:species opts) (:version opts) (first args)))

; This fn does not run in batch mode.
; CYPHER does not work on batch databases; this is still fast enough of an operation to run independently.
; change anything
(defn create-gene-neighbors
  "Works on all species in the database at once. Does not function in batch mode. Incompatabile with batch operation database."
  ;;; [config opts args]
  [config opts _]
  (println "Creating NEXT_TO relationships for all genomes")
  (db/connect (get-in config [:global :db_path]) (:memory opts))
  ; Get ordered list by chr, ignore strandedness, sort by gene.start
  (db/query (str "MATCH (x:Landmark)
                    <-[:LOCATED_ON]-
                      (:LandmarkHash)
                    <-[:LOCATED_ON]-(gene)
                  WHERE gene:gene OR gene:Annotation
                  RETURN x.species, x.version, x.id, gene
                  ORDER BY x.species, x.version, x.id, gene.start") {}
   (println "Results obtained, now creating relationships...")
   (doseq [[[species version id] genes] (group-by (fn [x] [(get x "x.species") (get x "x.version") (get x "x.id")]) results)]
     (doseq [[a b] (partition 2 1 genes)]
       ; We are in a transaction, so don't use db/create-relationship here!
       (.createRelationshipTo (get a "gene") (get b "gene") (:NEXT_TO db/rels))
       ))))