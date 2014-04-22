(defproject ago "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"

  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-2138"]
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]]

  :plugins [[lein-cljsbuild "1.0.1"]]

  :source-paths ["src"]

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
