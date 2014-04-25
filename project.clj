(defproject ago "0.1.0-SNAPSHOT"
  :description "snapshoting and rewinding using clojurescript core.async"
  :url "https://github.com/steveyen/ago"
  :scm {:url "git@github.com:steveyen/ago.git"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2202"]
                 [org.clojure/core.async "0.1.298.0-2a82a1-alpha"]]
  :plugins [[lein-cljsbuild "1.0.3"]]
  :hooks [leiningen.cljsbuild]

  :source-paths ["src"]

  :cljsbuild {
    :builds {:ago {:source-paths ["src"]
                   :compiler {:output-to "ago.js"
                              :output-dir "out"
                              :optimizations :none
                              :source-map true}}
             :ago-test {:source-paths ["src" "test"]
                        :compiler {:output-to "ago-test.js"
                                   :output-dir "out-test"
                                   :optimizations :none
                                   :source-map true}}}
     :test-commands {"test" ["phantomjs" "test/run.js" "index.html"]}})
