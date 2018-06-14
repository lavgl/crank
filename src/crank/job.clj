(ns crank.job
  (:require [clojure.tools.logging :as log]

            [crank.kafka :as kafka])
  (:import [org.apache.kafka.common.errors WakeupException]))


(defn record->message [record]
  {:topic     (.topic record)
   :partition (.partition record)
   :offset    (.offset record)
   :timestamp (.timestamp record)
   :key       (.key record)
   :value     (.value record)})


(defn run-loop [consumer stop {:keys [job-name func send-report]}]
  (send-report {:time     (System/currentTimeMillis)
                :job-name job-name
                :type     :start})
  (try
    (loop [messages nil]
      (when-not (nil? messages)
        (send-report {:time     (System/currentTimeMillis)
                      :job-name job-name
                      :type     :poll
                      :count    (count messages)}))

      (doseq [message messages]
        (when @stop
          (throw (ex-info "stop iteration" {:stop true})))

        (func message)

        (send-report {:time      (System/currentTimeMillis)
                      :job-name  job-name
                      :type      :message
                      :topic     (:topic message)
                      :offset    (:offset message)
                      :partition (:partition message)}))

      (when (seq messages)
        (.commitSync consumer))

      (if @stop
        (throw (ex-info "stop job" {:stop true}))
        (do
          (recur (->> (.poll consumer 100)
                      (mapv record->message))))))

    (catch Exception e
      (if (or (:stop (ex-data e))
              (and @stop
                   (instance? WakeupException e)))
        (do
          (send-report {:time     (System/currentTimeMillis)
                        :job-name job-name
                        :type     :stop})
          (log/infof "Stopping job %s" job-name))
        (do
          (log/errorf e "Job %s died" job-name)
          #_(send-report {:time      (System/currentTimeMillis)
                        :job-name  job-name
                        :type      :exception
                        :exception e})
          (throw e))))))


(defn start-job
  ([{:keys [kafka] :as config}]
   (start-job config
     (kafka/make-consumer kafka)))

  ([{:keys [job-name topics] :as config} consumer]
   (log/infof "Starting job %s" job-name)
   (.subscribe consumer topics)

   (let [stop   (atom false)
         worker (doto (Thread. #(run-loop consumer stop config))
                  (.start))]
     {:config   config
      :worker   worker
      :consumer consumer
      :report   []
      :stop!    #(do (reset! stop true)
                     (.wakeup consumer))})))