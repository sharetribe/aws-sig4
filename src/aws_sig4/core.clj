(ns aws-sig4.core)

(defn canonical-request [request])

(defn string-to-sign [crequest])

(defn signature [str-to-sign])

(defn with-signature [request sig])
