#!/usr/bin/env bash
# Ensure skeptic + lein-skeptic project.clj versions and cross-refs stay aligned.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKEPTIC_PC="${ROOT}/skeptic/project.clj"
PLUGIN_PC="${ROOT}/lein-skeptic/project.clj"
ROOT_PC="${ROOT}/project.clj"

die() { echo "verify-monorepo-versions: $*" >&2; exit 1; }

[[ -f "$SKEPTIC_PC" ]] || die "missing ${SKEPTIC_PC}"
[[ -f "$PLUGIN_PC" ]] || die "missing ${PLUGIN_PC}"
[[ -f "$ROOT_PC" ]] || die "missing ${ROOT_PC}"

# (defproject org.clojars.nomicflux/skeptic "VERSION"
lib_line="$(grep -m1 '^(defproject org.clojars.nomicflux/skeptic ' "$SKEPTIC_PC" || true)"
[[ -n "$lib_line" ]] || die "expected defproject org.clojars.nomicflux/skeptic in skeptic/project.clj"
lib_ver="$(sed -n 's/^.*org\.clojars\.nomicflux\/skeptic "\([^"]*\)".*/\1/p' <<<"$lib_line")"
[[ -n "$lib_ver" ]] || die "could not parse library version from: $lib_line"

# (defproject org.clojars.nomicflux/lein-skeptic "VERSION"
plug_line="$(grep -m1 '^(defproject org.clojars.nomicflux/lein-skeptic ' "$PLUGIN_PC" || true)"
[[ -n "$plug_line" ]] || die "expected defproject org.clojars.nomicflux/lein-skeptic in lein-skeptic/project.clj"
plug_ver="$(sed -n 's/^.*org\.clojars\.nomicflux\/lein-skeptic "\([^"]*\)".*/\1/p' <<<"$plug_line")"
[[ -n "$plug_ver" ]] || die "could not parse plugin version from: $plug_line"

# :plugins [[org.clojars.nomicflux/lein-skeptic "VERSION"]]
prof_line="$(grep 'org.clojars.nomicflux/lein-skeptic' "$SKEPTIC_PC" | grep ':plugins' || true)"
[[ -n "$prof_line" ]] || die "expected :plugins [[org.clojars.nomicflux/lein-skeptic \"…\"]] in skeptic/project.clj"
prof_ver="$(sed -n 's/.*lein-skeptic "\([^"]*\)".*/\1/p' <<<"$prof_line")"
[[ -n "$prof_ver" ]] || die "could not parse :skeptic-plugin lein-skeptic version from: $prof_line"

[[ "$plug_ver" == "$prof_ver" ]] || \
  die ":skeptic-plugin pins lein-skeptic \"$prof_ver\" but plugin project is \"$plug_ver\""

# (def ^:const skeptic-version "VERSION") in lein-skeptic launcher source.
# The launcher resolves [skeptic <skeptic-version>] at task time, so this
# const must equal the library version. Replaces the old check that grep'd
# [skeptic …] from lein-skeptic/project.clj's :dependencies (now empty —
# the hermetic launcher has zero transitive deps).
PLUGIN_SRC="${ROOT}/lein-skeptic/src/leiningen/skeptic.clj"
[[ -f "$PLUGIN_SRC" ]] || die "missing ${PLUGIN_SRC}"
const_ver="$(grep -A4 'def \^:const skeptic-version' "$PLUGIN_SRC" \
              | tail -1 \
              | sed -n 's/.*"\([^"]*\)".*/\1/p')"
[[ -n "$const_ver" ]] || die "could not parse (def ^:const skeptic-version ...) literal from ${PLUGIN_SRC}"
[[ "$lib_ver" == "$const_ver" ]] || \
  die "launcher skeptic-version const is \"$const_ver\" but library is \"$lib_ver\""

# Production-path fixture plugin coordinates: every dev-resources fixture
# that pins lein-skeptic must pin the current plugin version.
fixture_count=0
for fixture_pc in "${ROOT}"/lein-skeptic/dev-resources/*/project.clj; do
  [[ -f "$fixture_pc" ]] || continue
  fixture_line="$(grep 'org.clojars.nomicflux/lein-skeptic' "$fixture_pc" | head -1 || true)"
  [[ -n "$fixture_line" ]] || die "expected lein-skeptic plugin in ${fixture_pc}"
  fixture_ver="$(sed -n 's/.*lein-skeptic "\([^"]*\)".*/\1/p' <<<"$fixture_line")"
  [[ "$plug_ver" == "$fixture_ver" ]] || \
    die "fixture ${fixture_pc} pins lein-skeptic \"$fixture_ver\" but plugin is \"$plug_ver\""
  fixture_count=$((fixture_count + 1))
done
[[ "$fixture_count" -ge 1 ]] || die "no dev-resources fixture project.clj found"

# (defproject skeptic "VERSION" in root project.clj
root_line="$(grep -m1 '^(defproject skeptic ' "$ROOT_PC" || true)"
[[ -n "$root_line" ]] || die "expected defproject skeptic in root project.clj"
root_ver="$(sed -n 's/^(defproject skeptic "\([^"]*\)".*/\1/p' <<<"$root_line")"
[[ -n "$root_ver" ]] || die "could not parse root project version from: $root_line"
[[ "$lib_ver" == "$root_ver" ]] || \
  die "root project.clj version \"$root_ver\" does not match library \"$lib_ver\""

# Skeptic library coord pinned in skeptic.host.deps/host-deps. The launcher
# reads this vector out of the resolved skeptic jar and resolves it to build
# the host JVM's -cp; a stale pin here makes the host run an OLDER skeptic
# while the launcher is current.
HOST_DEPS_CLJ="${ROOT}/skeptic/src/skeptic/host/deps.clj"
[[ -f "$HOST_DEPS_CLJ" ]] || die "missing ${HOST_DEPS_CLJ}"
host_deps_line="$(grep 'org.clojars.nomicflux/skeptic' "$HOST_DEPS_CLJ" || true)"
[[ -n "$host_deps_line" ]] || die "expected [org.clojars.nomicflux/skeptic …] in ${HOST_DEPS_CLJ}"
host_deps_ver="$(sed -n 's/.*org\.clojars\.nomicflux\/skeptic[^"]*"\([^"]*\)".*/\1/p' <<<"$host_deps_line")"
[[ -n "$host_deps_ver" ]] || die "could not parse skeptic version from: $host_deps_line"
[[ "$lib_ver" == "$host_deps_ver" ]] || \
  die "skeptic.host.deps/host-deps pins skeptic \"$host_deps_ver\" but library is \"$lib_ver\""

# Skeptic library coord pinned in skeptic/deps.edn :host alias (mirrors
# skeptic.host.deps/host-deps for ad-hoc `clj -A:host` invocations).
DEPS_EDN="${ROOT}/skeptic/deps.edn"
[[ -f "$DEPS_EDN" ]] || die "missing ${DEPS_EDN}"
deps_edn_line="$(grep 'org.clojars.nomicflux/skeptic' "$DEPS_EDN" | grep ':mvn/version' || true)"
[[ -n "$deps_edn_line" ]] || die "expected org.clojars.nomicflux/skeptic {:mvn/version …} in ${DEPS_EDN}"
deps_edn_ver="$(sed -n 's/.*:mvn\/version "\([^"]*\)".*/\1/p' <<<"$deps_edn_line")"
[[ -n "$deps_edn_ver" ]] || die "could not parse skeptic version from: $deps_edn_line"
[[ "$lib_ver" == "$deps_edn_ver" ]] || \
  die "skeptic/deps.edn :host alias pins skeptic \"$deps_edn_ver\" but library is \"$lib_ver\""

# README install snippets pin the library / plugin to a current version. The
# snapshot-version snippets are what users copy-paste when trying a pre-release;
# they must match the in-tree version.
README="${ROOT}/README.md"
if [[ -f "$README" ]]; then
  readme_lein_ver="$(sed -n 's/.*lein-skeptic "\([^"]*\)".*/\1/p' "$README" | sort -u | tail -1)"
  if [[ -n "$readme_lein_ver" ]]; then
    [[ "$plug_ver" == "$readme_lein_ver" ]] || \
      die "README lein-skeptic snippet pins \"$readme_lein_ver\" but plugin is \"$plug_ver\""
  fi
  readme_lib_ver="$(grep 'org.clojars.nomicflux/skeptic ' "$README" \
                    | sed -n 's/.*:mvn\/version "\([^"]*\)".*/\1/p' \
                    | sort -u | tail -1)"
  if [[ -n "$readme_lib_ver" ]]; then
    [[ "$lib_ver" == "$readme_lib_ver" ]] || \
      die "README skeptic snippet pins \"$readme_lib_ver\" but library is \"$lib_ver\""
  fi
fi

echo "verify-monorepo-versions: OK (skeptic ${lib_ver}, lein-skeptic ${plug_ver})"
