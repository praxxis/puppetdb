(ns com.puppetlabs.cmdb.test.http.facts
  (:require [com.puppetlabs.cmdb.scf.storage :as scf-store]
            [com.puppetlabs.cmdb.http.facts :as facts]
            [com.puppetlabs.cmdb.http.server :as server]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql])
  (:use clojure.test
         ring.mock.request
         [com.puppetlabs.cmdb.testutils :only [test-db]]
         [com.puppetlabs.cmdb.scf.migrate :only [migrate!]]))

(def *app* nil)

(use-fixtures :each (fn [f]
                      (let [db (test-db)]
                        (binding [*app* (server/build-app {:scf-db db})]
                          (sql/with-connection db
                            (migrate!)
                            (f))))))

(def *content-type* "application/json")

(defn make-request
  "Return a GET request against path, suitable as an argument to a Clothesline
  app. Params supported are content-type and query-string."
  ([path] (make-request path {}))
  ([path {keys [:query-string :content-type]
          :or {:query-string "" :content-type *content-type*} :as params}]
     (let [request (request :get path (:query-string params))
           headers (:headers request)]
       (assoc request :headers (assoc headers "Accept" (:content-type params))))))

(deftest fact-set-handler
  (let [certname_with_facts "got_facts"
        certname_without_facts "no_facts"
        facts {"domain" "mydomain.com"
               "fqdn" "myhost.mydomain.com"
               "hostname" "myhost"
               "kernel" "Linux"
               "operatingsystem" "Debian"}]
    (scf-store/add-certname! certname_without_facts)
    (scf-store/add-certname! certname_with_facts)
    (scf-store/add-facts! certname_with_facts facts)
    (testing "for an absent node"
      (let [content-type "application/json"
            request (make-request "/facts/imaginary_node")
            response (*app* request)]
        (is (= (:status response) 404))
        (is (= (get-in response [:headers "Content-Type"]) content-type))
        (is (= (json/parse-string (:body response) true)
               {:error "Could not find facts for imaginary_node"}))))
    (testing "for a present node without facts"
      (let [content-type "application/json"
            request (make-request (format "/facts/%s" certname_without_facts))
            response (*app* request)]
        (is (= (:status response) 404))
        (is (= (get-in response [:headers "Content-Type"]) content-type))
        (is (= (json/parse-string (:body response) true)
               {:error (str "Could not find facts for " certname_without_facts)}))))
    (testing "for a present node with facts"
      (let [request (make-request (format "/facts/%s" certname_with_facts))
            response (*app* request)]
        (is (= (:status response) 200))
        (is (= (get-in response [:headers "Content-Type"]) *content-type*))
        (is (= (json/parse-string (:body response))
               {"name" certname_with_facts "facts" facts}))))))
