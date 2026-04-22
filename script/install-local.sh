#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

(cd "$ROOT/skeptic" && lein do clean, install)
(cd "$ROOT/lein-skeptic" && lein do clean, install)
