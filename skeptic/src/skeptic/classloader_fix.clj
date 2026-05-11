(ns skeptic.classloader-fix
  "JDK 9+/Leiningen-bootclasspath workaround for `clojure.instant`.

  Leiningen's launcher appends `leiningen-<v>-standalone.jar` to the JVM's
  `-Xbootclasspath/a:` (unless LEIN_USE_BOOTCLASSPATH=no is set in the
  environment — which we cannot require of end users). Clojure ends up
  loaded by the JVM bootstrap classloader. When any code transitively
  requires `clojure.instant`, its AOT-compiled `<clinit>` pushes
  `clojure.lang.Compiler/LOADER = (.getClassLoader thisLoadingFn) = null`
  (bootstrap). `RT.classForNameNonLoading(\"java.sql.Timestamp\")` then
  calls `Class.forName(\"java.sql.Timestamp\", false, null)` against the
  bootstrap loader. On JDK 9+, `java.sql` is defined to
  `PlatformClassLoader`, NOT bootstrap, so the call raises
  `ClassNotFoundException`. The four sibling `java.util.*` imports just
  before it succeed because `java.util` lives in `java.base`, which IS in
  bootstrap.

  Skeptic transitively pulls clojure.instant via cljs.analyzer.api →
  cljs.tagged-literals → cljs.instant → clojure.instant, so this triggers
  at plugin-load time and blocks the `skeptic` task from running at all.

  Fix: `clojure.lang.RT/classForName` bytecode short-circuits to
  `DynamicClassLoader/findInMemoryClass` BEFORE invoking
  `Class.forName(name, false, null)` when the passed loader is not itself a
  `DynamicClassLoader`. That short-circuit reads
  `DynamicClassLoader/classCache`, a static `ConcurrentHashMap` normally
  used by Clojure's in-memory class generation. We pre-populate it with
  `\"java.sql.Timestamp\" → java.sql.Timestamp.class`, then eagerly
  `(require 'clojure.instant)`. Its `<clinit>` runs immediately, hits our
  cache entry through the short-circuit, and never falls through to the
  broken `Class.forName(name, false, null)` path. The namespace is
  registered in `*loaded-libs*`, so the later transitive require from
  cljs.instant is a no-op and the unsafe `<clinit>` does not re-run.

  This namespace is intentionally required FIRST by
  `skeptic.cljs.analyzer-driver` — the earliest namespace on the cljs
  intake chain — so the cache is populated before any cljs require can
  drag clojure.instant in.")

(let [cache-field (.getDeclaredField clojure.lang.DynamicClassLoader "classCache")]
  (.setAccessible cache-field true)
  (let [cache ^java.util.concurrent.ConcurrentHashMap (.get cache-field nil)]
    (.put cache
          "java.sql.Timestamp"
          (java.lang.ref.SoftReference. java.sql.Timestamp))))

(require 'clojure.instant)
