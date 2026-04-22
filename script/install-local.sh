#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

rm -rf ~/.m2/repository/org/clojars/nomicflux/*skeptic

(cd "$ROOT/skeptic" && lein do clean, install)
(cd "$ROOT/lein-skeptic" && lein do clean, install)
