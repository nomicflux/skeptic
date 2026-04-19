# Releasing Skeptic (maintainers)

Library and plugin versions must stay aligned; CI runs `bash script/verify-monorepo-versions.sh` before builds.

## Workflows (GitHub Actions)

| Workflow | Role |
|----------|------|
| [**Release lifecycle**](https://github.com/nomicflux/skeptic/actions/workflows/release-lifecycle.yml) | **Main entry:** run one phase at a time — prepare stable → publish → begin next snapshot. Each phase calls the workflows below. |
| [**Change project versions**](https://github.com/nomicflux/skeptic/actions/workflows/change-project-versions.yml) | Reusable + manual: replace `from_version` → `to_version` in both `project.clj` files and the README install line, verify, `lein pom`. Optional PR. |
| [**Publish to Clojars**](https://github.com/nomicflux/skeptic/actions/workflows/publish-clojars.yml) | Reusable + manual: checks, then optional `lein deploy clojars` (library, then plugin). Same workflow whether you trigger it alone or from **Release lifecycle**. |

**Snapshot-only deploy** (e.g. ship `0.7.0-SNAPSHOT` without opening the next dev line): run **Publish to Clojars** only, with the repo already on the snapshot coordinates you want.

## Stable line example (`0.7.0`)

1. **Release lifecycle** → `prepare_stable_release` — e.g. `from_version` `0.7.0-SNAPSHOT`, `to_version` `0.7.0`. Merge the PR when CI is green, update [`CHANGELOG.md`](../CHANGELOG.md) for `0.7.0`, tag **`v0.7.0`**.
2. **Release lifecycle** → `publish_to_clojars` — `dry_run` **no**, `confirm` **`PUBLISH`** (needs `CLOJARS_USERNAME` / `CLOJARS_PASSWORD` secrets). Deploys **skeptic** then **lein-skeptic**. Or use **Publish to Clojars** alone with the same inputs.
3. **Release lifecycle** → `begin_next_development` — e.g. `from_version` `0.7.0`, `to_version` `0.8.0-SNAPSHOT`. Merge the PR when ready.

4. Publish a [GitHub Release](https://github.com/nomicflux/skeptic/releases) for `v0.7.0`; paste the changelog section for that version.

## Manual edits (without Actions)

Bump versions in [`skeptic/project.clj`](https://github.com/nomicflux/skeptic/blob/main/skeptic/project.clj) and [`lein-skeptic/project.clj`](https://github.com/nomicflux/skeptic/blob/main/lein-skeptic/project.clj), the install snippet in [`README.md`](../README.md), then `lein pom` in `skeptic/` and `lein-skeptic/`. Deploy with `lein deploy clojars` (library first, then plugin) or **Publish to Clojars**.
