(ns skeptic.worker.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.worker.client :as client]))

(defn- queued-recv
  [msgs]
  (let [remaining (atom msgs)]
    (fn [_transport]
      (let [m (first @remaining)]
        (swap! remaining rest)
        m))))

(deftest recv-reply-merges-replies-until-done
  (let [t-recv (queued-recv [{:id "req-1" :part 1}
                             {:id "req-1" :part 2 :status #{:done}}])]
    (is (= {:id "req-1" :part 2 :status #{:done}}
           (#'client/recv-reply t-recv nil "an-op" "req-1")))))

(deftest recv-reply-throws-on-foreign-id
  (let [t-recv (queued-recv [{:id "req-1" :part 1}
                             {:id "someone-else" :payload :stray}])
        ex (try
             (#'client/recv-reply t-recv nil "an-op" "req-1")
             nil
             (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (re-find #"foreign request id" (ex-message ex)))
    (testing "the stray message is carried for diagnosis"
      (is (= {:id "someone-else" :payload :stray}
             (:stray-message (ex-data ex)))))))

(deftest recv-streaming-passes-each-reply-until-done
  (let [t-recv (queued-recv [{:id "req-1" :n 1}
                             {:id "req-1" :n 2}
                             {:id "req-1" :status #{:done}}])
        seen (atom [])]
    (#'client/recv-streaming t-recv nil "an-op" "req-1" #(swap! seen conj (:n %)))
    (is (= [1 2] @seen))))

(deftest recv-streaming-throws-on-foreign-id
  (let [t-recv (queued-recv [{:id "req-1" :n 1}
                             {:id "someone-else" :payload :stray}
                             {:id "req-1" :status #{:done}}])
        seen (atom [])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"foreign request id"
                          (#'client/recv-streaming t-recv nil "an-op" "req-1"
                           #(swap! seen conj (:n %)))))
    (testing "replies before the stray message were delivered"
      (is (= [1] @seen)))))

(deftest recv-streaming-throws-when-transport-closes
  (let [t-recv (queued-recv [{:id "req-1" :n 1}])
        seen (atom [])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"transport closed during streaming op"
                          (#'client/recv-streaming t-recv nil "an-op" "req-1"
                           #(swap! seen conj (:n %)))))))
