(ns dysentery.dbserver
  "Parses and builds the fields, messages, and workflows that support
  interacting with (or offering) a database server for track
  information."
  (require [dysentery.finder :as finder]
           [dysentery.util :as util])
  (:require [taoensso.timbre :as timbre]))

(defn send-bytes
  "Send a vector of byte values to an output stream."
  [os v]
  (println "Sending " (vec (map #(format "%02x" (util/unsign %)) v)))
  (let [obuf (byte-array (map util/make-byte v))]
    (.write os obuf 0 (count obuf))
    (.flush os)))

(defn recv-bytes
  "Receive the specified number of bytes from an input stream,
  returning a vector of bytes read when successful, or nil if the
  socket was closed or the read timed out."
  [is size]
  (let [ibuf (byte-array size)]
    (try
      (loop [offset 0
             len    (.read is ibuf offset (- size offset))]
        (let [offset (+ offset len)]
          (cond (neg? len)
                (timbre/warn "Socket closed trying to read" size "bytes from dbserver port.")

                (= offset size)
                (let [result (vec ibuf)]
                  (print "recv[" size "]: ")
                  (doseq [b result]
                    (print (format "%02x " (util/unsign b))))
                  (println)
                  result)

                :else
                (recur offset (.read is ibuf offset (- size offset))))))
      (catch java.net.SocketTimeoutException e
        (timbre/warn "Timed out trying to read" size "bytes from dbserver port.")))))

(defn number->bytes
  "Splits a number with the specified byte size into its individual
  bytes, returning them as a vector."
  [n size]
  (loop [result (list (bit-and n 0xff))
         n (bit-shift-right n 8)
         remaining (dec size)]
    (if (zero? remaining)
      (vec result)
      (recur (conj result (bit-and n 0xff))
             (bit-shift-right n 8)
             (dec remaining)))))

(defn bytes->number
  "Given a byte vector, calculates the integer that the bytes
  represent."
  [bytes]
  (loop [left (rest bytes)
         result (util/unsign (first bytes))]
    (if (seq left)
      (recur (rest left) (+ (* result 256) (util/unsign (first left))))
      result)))

(defn number-field
  "Creates a field that represents a 4-byte number in a message. Can
  be created from just the number, or from the four individual bytes."
  ([n]
   {:type         :number
    :number       n
    :arg-list-tag 0x06
    :bytes        (into [0x11] (number->bytes n 4))})
  ([byte-1 byte-2 byte-3 byte-4]
   (number-field (bytes->number [byte-1 byte-2 byte-3 byte-4]))))

(defn message-type-field
  "Creates a 4-byte field which, as the third field in a message,
  identifies the type of the message, as well as the number of
  argument fields that it includes. The type is stored in the first
  two bytes of this field, followed by 0x0f, and the argument count."
  [message-type argument-count]
  {:type           :message-type
   :message-type   message-type
   :argument-count argument-count
   :bytes          (into [0x10] (concat (number->bytes message-type 2) [0x0f (bit-and argument-count 0xff)]))})

(defn blob-field
  "Creates a variable sized field containing bytes of data, prefixed
  by a 4-byte size."
  [data]
  {:type :blob
   :data (vec data)
   :arg-list-tag 0x03
   :bytes (into [0x14] (concat (number->bytes (count data) 4) data))})

(defn string-field
  "Creates a variable sized field containing a string, prefixed
  by a 4-byte size."
  [text]
  (let [bytes (.getBytes text "UTF-16BE")]
    {:type   :string
     :string text
     :arg-list-tag 0x02
     :bytes  (into [0x26] (concat (number->bytes (count bytes) 4) bytes))}))

;; TODO: Add string-field and a read-field implementation for it

;; TODO: These have to take the player object as well, so they can
;; read more data if needed, and return the buffer and offset where
;; reading of the next field should begin.

(defmulti read-field
  "Read a tagged field value from the input stream. If there is a
  communication or parsing problem, an explanatory error is logged,
  and `nil` is returned. The multimethod dispatches on the first byte
  read, which is the tag that identifies the type of the field."
  (fn [is]
    (when-let [tag (recv-bytes is 1)]
      (util/unsign (first tag)))))

(defmethod read-field nil  ; No data available
  [is]
  (timbre/error "Attempt to read field failed."))

(defmethod read-field 0x11  ; A number field
  [is]
  (if-let [bytes (recv-bytes is 4)]
    (number-field (bytes->number bytes))
    (timbre/error "Attempt to read number field failed.")))

(defmethod read-field 0x10  ; A message type field
  [is]
  (if-let [bytes (recv-bytes is 4)]
    (if (not= (get bytes 2) 0x0f)
      (timbre/error "Message type field does not have value 15 following message type, found" (get bytes 2))
      (message-type-field (bytes->number (take 2 bytes)) (util/unsign (get bytes 3))))
    (timbre/error "Attempt to read message type field failed.")))

(defmethod read-field 0x14  ; A blob field
  [is]
  (if-let [bytes (recv-bytes is 4)]
    (let [size (bytes->number bytes)]
      (if-let [body (recv-bytes is size)]
        (blob-field body)
        (timbre/error "Failed to read" size "bytes of blob field.")))
    (timbre/error "Attempt to read size of blob field failed.")))

(defmethod read-field 0x26  ; A string field
  [is]
  (if-let [bytes (recv-bytes is 4)]
    (let [size (bytes->number bytes)]
      (if-let [body (recv-bytes is size)]
        (string-field (String. (byte-array body) "UTF-16BE"))
        (timbre/error "Failed to read" size "bytes of string field.")))
    (timbre/error "Attempt to read size of string field failed.")))


(defmethod read-field :default  ; An unrecognized field
  [buffer index]
  (timbre/error "Do not know what kind of field corresponds to type tag" (aget buffer index)))

(def message-start-marker
  "The number field which is always used to identify the start of a new
  message."
  (number-field 0x872349ae))

(defn build-message
  "Puts together the fields that make up a message, with the specified
  transaction number, message type, and argument fields, as a map with
  the same structure returned by `read-message`."
  [transaction-number message-type & args]
  {:start          message-start-marker
   :transaction    (number-field transaction-number)
   :message-type   (message-type-field message-type (count args))
   :argument-types (blob-field (take 12 (concat (map :arg-list-tag args) (repeat 0))))
   :arguments      args})

(defn build-setup-message
  "Creates a message that can be used to set up communication with a
  player, given the player number we are pretending to be. This is a
  message with transaction ID `0xfffffffe` and a message type of 0,
  whose sole argument is a number field containing our player number."
  [player-number]
  (build-message 0xfffffffe 0 (number-field player-number)))

(defn send-message
  "Sends the bytes that make up a message to the specified player."
  [player message]
  (let [fields (concat (reduce #(conj %1 (message %2)) [] [:start :transaction :message-type :argument-types])
                       (:arguments message))]
    (send-bytes (:output-stream player) (vec (apply concat (map :bytes fields))))))

(defn- read-arguments
  "Reads the argument fields which end a message, validating that
  their types match what was decleared in the argument-types field,
  returning the completed message map on success, or `nil` on
  failure."
  [is message]
  (let [expected (get-in message [:message-type :argument-count])]
    (if (> expected 12)
      (timbre/error "Can't read a message with more than twelve arguments.")
      (if (= (count (:arguments message)) expected)
        message
        (if-let [arg (read-field is)]
          (let [next-tag (util/unsign (get-in message [:argument-types :data (count (:arguments message))]))]
            (if (= (:arg-list-tag arg) next-tag)
              (recur is (update message :arguments conj arg))
              (timbre/error "Found argument of wrong type when trying to read a message. Expected tag:"
                            next-tag "and found:" (:arg-list-tag arg))))
          (timbre/error "Unable to read an argument field when trying to read a message."))))))

(defn- read-argument-types
  "Reads the fourth field of a message, which should be a blob listing
  the argument types, and continues building the message map if
  successful, or returns `nil` on failure."
  [is message]
  (if-let [argument-types (read-field is)]
    (if (= :blob (:type argument-types))
      (read-arguments is (assoc message :argument-types argument-types))
      (timbre/error "Did not find argument-type blob field when trying to read a message."))
    (timbre/error "Unable to read argument-type blob field when trying to read a message.")))

(defn- read-message-type
  "Reads the third field of a message, which should be a special
  message-type field, and continues building the message map if
  successful, or returns `nil` on failure."
  [is message]
  (if-let [message-type (read-field is)]
    (if (= :message-type (:type message-type))
      (read-argument-types is (assoc message :message-type message-type))
      (timbre/error "Did not find message-type field when trying to read a message."))
    (timbre/error "Unable to read message-type field when trying to read a message.")))

(defn- read-transaction-id
  "Reads the second field of a message, which should be a number, and
  continues building the message map if successful, or returns `nil`
  on failure."
  [is message]
  (if-let [tx-id (read-field is)]
    (if (= :number (:type tx-id))
      (read-message-type is (assoc message :transaction tx-id))
      (timbre/error "Did not find numeric transaction ID field when trying to read a message."))
    (timbre/error "Unable to read transaction ID field when trying to read a message.")))

(defn read-message
  "Attempts to read a message from the specified player. Returns
  a map breaking down the fields that make up the message, or `nil` if
  a problem was encountered."
  [player]
  (let [is (:input-stream player)]
    (if-let [start (read-field is)]
      (if (= start message-start-marker)
        (read-transaction-id is {:start start
                                 :arguments []})
        (timbre/error "Did not find message start marker when trying to read a message."))
      (timbre/error "Unable to read first field of message."))))

(defn disconnect
  "Closes a connection to a player. You can not use it after this
  point."
  [{:keys [input-stream output-stream socket]}]
  (.close input-stream)
  (.close output-stream)
  (.close socket))

(def connect-timeout
  "The number of milliseconds after which we should give up attempting
  to connect to the dbserver socket."
  5000)

(def read-timeout
  "The numbe of milliseconds after which we should give up waiting for
  a response while communicating with a connected dbserver."
  2000)

(defn connect-to-player
  "Opens a database server connection to the specified player number,
  posing as the specified player number, which must be unused on the
  network. Returns a value that can be used with the other functions
  in this namespace to interact with the database server on that
  player.

  For simplicity, we go directly to port 1051 on the specified player,
  even though Beat Link already implements the correct mechanism of
  querying for the appropriate port. This is just quick and dirty
  hackery code to further our knowledge.

  Returns `nil` if the target player could not be found."
  [target-player-number pose-as-player-number]
  (when-let [device (finder/device-given-number target-player-number)]
    (let [sock     (java.net.Socket.)
          _        (.connect sock (java.net.InetSocketAddress. (:address device) 1051) connect-timeout)
          is       (.getInputStream sock)
          os       (.getOutputStream sock)
          player   {:socket        sock
                    :input-stream  is
                    :output-stream os}
          greeting (number-field 1)]  ; The greeting packet is a number field representing the number 1.
      (.setSoTimeout sock read-timeout)
      (send-bytes os (:bytes greeting))
      (if (not= (read-field is) greeting)
        (do (disconnect player)
            (timbre/error "Did not receive expected greeting response from player, closed."))
        (do
          (send-message player (build-setup-message pose-as-player-number))
          (clojure.pprint/pprint (read-message player))
          player)))))

