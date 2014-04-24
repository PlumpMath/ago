(defproject ago "0.1.0-SNAPSHOT"
  :description "snapshoting and rewinding built on top of clojurescript core.async"
  :url "https://github.com/steveyen/ago"
  :scm {:url "git@github.com:steveyen/ago.git"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-2138"]
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]]

  :hooks [leiningen.cljsbuild]
  :plugins [[lein-cljsbuild "1.0.1"]
            [com.cemerick/clojurescript.test "0.3.0"]]

  :source-paths ["src"]

  :clojurescript? true
  :cljsbuild {
    :builds [{:id "ago"
              :source-paths ["src"]
              :compiler {
                :output-to "ago.js"
                :output-dir "out"
                :optimizations :none
                :source-map true}}]}

  :main ^:skip-aot ago.test
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
