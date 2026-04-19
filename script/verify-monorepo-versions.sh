#!/usr/bin/env bash
# Ensure skeptic + lein-skeptic project.clj versions and cross-refs stay aligned.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKEPTIC_PC="${ROOT}/skeptic/project.clj"
PLUGIN_PC="${ROOT}/lein-skeptic/project.clj"

die() { echo "verify-monorepo-versions: $*" >&2; exit 1; }

[[ -f "$SKEPTIC_PC" ]] || die "missing ${SKEPTIC_PC}"
[[ -f "$PLUGIN_PC" ]] || die "missing ${PLUGIN_PC}"

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

# [org.clojars.nomicflux/skeptic "VERSION"]
dep_line="$(grep '\[org.clojars.nomicflux/skeptic ' "$PLUGIN_PC" | head -1 || true)"
[[ -n "$dep_line" ]] || die "expected [org.clojars.nomicflux/skeptic \"…\"] in lein-skeptic/project.clj"
dep_ver="$(sed -n 's/.*\[org\.clojars\.nomicflux\/skeptic "\([^"]*\)".*/\1/p' <<<"$dep_line")"
[[ -n "$dep_ver" ]] || die "could not parse skeptic dependency version from: $dep_line"

# :plugins [[org.clojars.nomicflux/lein-skeptic "VERSION"]]
prof_line="$(grep 'org.clojars.nomicflux/lein-skeptic' "$SKEPTIC_PC" | grep ':plugins' || true)"
[[ -n "$prof_line" ]] || die "expected :plugins [[org.clojars.nomicflux/lein-skeptic \"…\"]] in skeptic/project.clj"
prof_ver="$(sed -n 's/.*lein-skeptic "\([^"]*\)".*/\1/p' <<<"$prof_line")"
[[ -n "$prof_ver" ]] || die "could not parse :skeptic-plugin lein-skeptic version from: $prof_line"

[[ "$lib_ver" == "$dep_ver" ]] || \
  die "lein-skeptic depends on skeptic \"$dep_ver\" but library is \"$lib_ver\""
[[ "$plug_ver" == "$prof_ver" ]] || \
  die ":skeptic-plugin pins lein-skeptic \"$prof_ver\" but plugin project is \"$plug_ver\""

echo "verify-monorepo-versions: OK (skeptic ${lib_ver}, lein-skeptic ${plug_ver})"
