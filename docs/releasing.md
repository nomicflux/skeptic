# Releasing Skeptic (maintainers)

Library and plugin versions must stay aligned; CI runs `bash script/verify-monorepo-versions.sh` before builds.

## Snapshot vs stable

**Development** stays on **`*-SNAPSHOT`** coordinates. A **non-`SNAPSHOT` version** is the **exact stable** you ship for that instant (artifacts, tags, docs that pin a release).

**Release lifecycle** enforces that: the branch must already be **`${release_version}-SNAPSHOT`**, you enter the **bare** stable to publish (`release_version`, e.g. `0.7.0`) and the **bare** next dev line (`next_dev_version`, e.g. `0.8.0`); the workflow moves the tree to **`0.7.0`**, publishes, then to **`0.8.0-SNAPSHOT`**.

## `GITHUB_TOKEN` permissions (release lifecycle)

**Release lifecycle** declares **`permissions: contents: write`** so jobs that call [**change-project-versions**](https://github.com/nomicflux/skeptic/actions/workflows/change-project-versions.yml) can push. A caller that only granted **`contents: read`** would block the nested job from **`contents: write`**: GitHub documents that **`GITHUB_TOKEN` permissions can only be the same or more restrictive in nested workflows** ([Reusing workflow configurations — nested permissions](https://docs.github.com/en/actions/reference/reusable-workflows-reference#access-and-permissions-for-nested-workflows)).

The **publish** job uses **`secrets: inherit`** so [**publish-clojars**](https://github.com/nomicflux/skeptic/actions/workflows/publish-clojars.yml) receives repository Actions secrets ([`secrets: inherit`](https://docs.github.com/en/actions/using-workflows/workflow-syntax-for-github-actions#jobsjob_idsecretsinherit), [Reuse workflows — passing secrets](https://docs.github.com/en/actions/using-workflows/reusing-workflows#passing-inputs-and-secrets-to-a-reusable-workflow)).

## Leiningen deploy auth (`:deploy-repositories` + env)

[`leiningen.org` deploy](https://leiningen.org/deploy.html#credentials-in-the-environment) documents **`:username`/`:password`** with **`:env/...`** on **`:repositories`** and notes that **`:env`** forms are *only discussed there* for `:repositories`. For **Leiningen 2.11.2** (pinned in [publish-clojars.yml](https://github.com/nomicflux/skeptic/blob/main/.github/workflows/publish-clojars.yml)), the **deploy** task resolves repo settings the same way for **`:deploy-repositories`**: `repo-for` merges deploy repo settings and passes them through **`classpath/add-repo-auth`** → **`user/resolve-credentials`**, which resolves **`:env/VAR`** via `System/getenv` — see [deploy.clj `repo-for`](https://raw.githubusercontent.com/technomancy/leiningen/2.11.2/src/leiningen/deploy.clj), [`classpath.clj` `add-repo-auth`](https://raw.githubusercontent.com/technomancy/leiningen/2.11.2/leiningen-core/src/leiningen/core/classpath.clj), and [`user.clj` `resolve-credential`](https://raw.githubusercontent.com/technomancy/leiningen/2.11.2/leiningen-core/src/leiningen/core/user.clj). **`lein deploy`** with no repo name uses **`snapshots`** vs **`releases`** by version ([deploy — Deployment](https://leiningen.org/deploy.html#deployment)).

## Why chained jobs need an explicit SHA

Each reusable workflow run is a **separate** runner checkout. Pushing in job A does **not** move `github.sha` in the parent run ([`github.sha`](https://docs.github.com/en/actions/learn-github-actions/contexts#github-context) is the commit that triggered the workflow). After the first bump pushes, **`change-project-versions`** records `git rev-parse HEAD` and exposes it as workflow output **`commit_sha`** ([outputs from reusable workflows](https://docs.github.com/en/actions/using-workflows/reusing-workflows#using-outputs-from-a-reusable-workflow)). **Release lifecycle** passes that value into **`publish-clojars`** (`commit_sha`) and into the second **`change-project-versions`** call (`checkout_ref`), so publish and the next bump see the exact tree that was pushed.

## Workflows (GitHub Actions)

| Workflow | Role |
|----------|------|
| [**Release lifecycle**](https://github.com/nomicflux/skeptic/actions/workflows/release-lifecycle.yml) | **One dispatch:** repo must be on **`${release_version}-SNAPSHOT`**. Inputs are **bare** `release_version` (stable to publish) and **bare** `next_dev_version` (next dev line; workflow sets **`${next_dev_version}-SNAPSHOT`**). Composes **only** [**Change project versions**](https://github.com/nomicflux/skeptic/actions/workflows/change-project-versions.yml) and [**Publish to Clojars**](https://github.com/nomicflux/skeptic/actions/workflows/publish-clojars.yml): bump to release (checkout `github.sha`) → publish at **`commit_sha`** → bump to next snapshot (same **`commit_sha`** checkout). |
| [**Change project versions**](https://github.com/nomicflux/skeptic/actions/workflows/change-project-versions.yml) | Reusable + manual: replace `from_version` → `to_version` in both `project.clj` files and the README install line, verify, regenerate POMs, **commit**, **`git tag v<to_version>`**, **push branch and tag**. **`workflow_call`** requires **`checkout_ref`**. **`workflow_dispatch`** uses default checkout (inputs: `from_version`, `to_version` only). |
| [**Publish to Clojars**](https://github.com/nomicflux/skeptic/actions/workflows/publish-clojars.yml) | Verify versions, local `lein install`, then `lein deploy` for **skeptic** then **lein-skeptic** (Leiningen picks **snapshots** vs **releases** from each `project.clj` by version). Credentials come from **repository Actions secrets** mapped to `CLOJARS_*` **environment variables** in the job. **`workflow_call`** requires **`commit_sha`** (checkout that SHA). **`workflow_dispatch`** uses default checkout for a standalone publish of the ref you run the workflow from. |

**Snapshot-only deploy** (e.g. ship `0.7.0-SNAPSHOT` without advancing the release lifecycle): run **Publish to Clojars** via **workflow_dispatch** with the repo already on the snapshot coordinates you want.

### Repository secrets (Actions deploy)

Under **Settings → Security → Secrets and variables → Actions**:

| Secret | Used for |
|--------|----------|
| **`CLOJARS_USERNAME`** | Clojars username (both deploy steps). |
| **`CLOJARS_SKEPTIC_TOKEN`** | Deploy token for **`org.clojars.nomicflux/skeptic`** (`lein deploy` in `skeptic/`). |
| **`CLOJARS_LEIN_SKEPTIC_TOKEN`** | Deploy token for **`org.clojars.nomicflux/lein-skeptic`** (`lein deploy` in `lein-skeptic/`). |

Deploy steps read Clojars auth from **`CLOJARS_*` environment variables** set from those secrets (see each `project.clj` `:deploy-repositories`). If one Clojars token is valid for both artifacts, set the same value on both token secrets.

## Stable line example (`0.7.0`)

1. Ensure the repo is on **`0.7.0-SNAPSHOT`** everywhere the coordinated files require (see verify script). **Release lifecycle** validates that before any bump.
2. **Release lifecycle** — **Run workflow** once: **`release_version`** `0.7.0`, **`next_dev_version`** `0.8.0`. Requires the three Action secrets in the table above. The first bump pushes the release commit and tag **`v0.7.0`**; publish runs against that commit; the second bump moves the tree to **`0.8.0-SNAPSHOT`** and pushes. **Branch protection:** the default `GITHUB_TOKEN` must be allowed to push to the branch you run against (often `main`), or the push steps will fail.
3. Optionally create a [GitHub Release](https://github.com/nomicflux/skeptic/releases) pointing at tag **`v0.7.0`** (already pushed by the first bump).

## Manual edits (without Actions)

Bump versions in [`skeptic/project.clj`](https://github.com/nomicflux/skeptic/blob/main/skeptic/project.clj) and [`lein-skeptic/project.clj`](https://github.com/nomicflux/skeptic/blob/main/lein-skeptic/project.clj), the install snippet in [`README.md`](../README.md), then `lein pom` in `skeptic/` and `lein-skeptic/`. Deploy with `lein deploy` from each project directory (library first, then plugin) after exporting `CLOJARS_USERNAME` and the matching token env vars, or use **Publish to Clojars** (dispatch), which sets those from Actions secrets.
