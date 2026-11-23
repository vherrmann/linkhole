# the-next-thing

Self-hosted link queue. While going through the org todo inbox, links (YouTube, web, …) are
pushed to a server. The Android app shows the **most recent unread** link (stable until it is
marked *read* or *uninteresting*) or a **random unread** one (re-rolled on every tap). The server
is the source of truth; Emacs can add links and list/manage them.

Status: server, Emacs package and Android app are implemented and tested (Go tests, ERT, Android unit
tests, Emacs↔server e2e run by hand). **Not yet verified:** `nix build .#server` / `nix flake check` (the
sandbox has the Nix store but no `nix` binary) — `vendorHash` in `flake.nix` is set (update it when Go deps change), but
`nix/module.nix` + the VM test are untested. Keep this file updated as components land.

## Decisions (from interview with the user)
- Server: **Go + SQLite**, deployable via **Nix flake + NixOS module**.
- Auth: **static bearer token read from a file** (agenix/sops-nix friendly).
- Uploads: Emacs (prompt for URL, link at point, all links in region), Android share sheet,
  plain curl/HTTP.
- Server fetches page titles in the background (YouTube via oEmbed, else HTML `<title>`/`og:title`).
- Android: offline cache + queued actions, synced when online.
- "Any other one" button = random unread, **re-rolls each tap** (not sticky).
- Emacs list buffer: filter by state, mark read/uninteresting/unread, open in browser.
- After uploading from the inbox, Emacs does **nothing** to the org entry.
- Android built with Gradle; Android SDK comes from the Nix devShell (`androidenv`).
- Undo: Snackbar undo in the app; any state can be reset to `unread` (API/Emacs).

## Layout (planned)
```
flake.nix          packages.{server,emacs-package}, nixosModules.default, devShells.default, checks
nix/module.nix     NixOS module services.the-next-thing
server/            Go module (cmd/the-next-thing, store, api, titles, auth)
emacs/the-next-thing.el
android/           Kotlin/Compose Gradle project
README.md
```

## Data model
`items(id INTEGER PK, url TEXT UNIQUE, title TEXT NULL, note TEXT NULL,
state TEXT CHECK (state IN ('unread','read','uninteresting')), created_at, updated_at,
title_fetched INT)`

**Core rule:** "latest" = newest unread by `created_at`. It is *derived, never stored*, so it
stays stable until marked, and the app can compute it identically from its cache offline.

## HTTP API
All under `/api`, header `Authorization: Bearer <token>`. `/healthz` is unauthenticated.

| Method | Path | Notes |
|---|---|---|
| POST | /api/items `{url, note?}` | 201 new; URL already exists → 200 with existing item (no dups) |
| GET | /api/items?state=unread\|read\|uninteresting\|all&updated_since= | default `unread`, newest first |
| GET | /api/items/latest | newest unread; 204 if none |
| GET | /api/items/random | random unread excluding latest (falls back to latest if it's the only one); 204 if none |
| PATCH | /api/items/{id} `{state}` | sets state + updated_at; resetting to unread allowed |
| DELETE | /api/items/{id} | hard delete |

## Server (Go)
- stdlib `net/http` (Go 1.22+ pattern routing), `modernc.org/sqlite` (pure Go, **no cgo** →
  simple `buildGoModule`), `golang.org/x/net/html` for title parsing. Prefer stdlib otherwise.
- Flags: `-listen` (default `127.0.0.1:8787`), `-db` (default `$STATE_DIRECTORY/items.db`),
  `-token-file` (required unless `-insecure-no-auth`). Constant-time token compare; never log the token.
- Title fetcher: background goroutine/channel; runs on insert and at startup for
  `title_fetched=0`. 10s timeout, 1 MiB body cap. Mark as fetched even on failure (clients fall back to URL).
- Tests: `httptest` table tests (add/dedupe/latest stability/random/patch/auth), store tests on a
  temp DB, title tests against a local httptest server.

## NixOS module `services.the-next-thing`
Options: `enable`, `package`, `listenAddress`, `port`, `tokenFile`, `openFirewall`.
systemd: `DynamicUser`, `StateDirectory=the-next-thing`, `LoadCredential=token:<tokenFile>` →
`-token-file $CREDENTIALS_DIRECTORY/token`, hardening (ProtectSystem=strict, NoNewPrivileges, …).
TLS via the user's nginx/Caddy (example in README). The flake includes a NixOS VM test that boots the
module and curls `/healthz` plus an authed add/latest.

## Emacs package (`emacs/the-next-thing.el`)
- **Built-ins only**: url.el (`url-retrieve`, async), json, tabulated-list, auth-source, org-element.
- Customs: `the-next-thing-server-url`; token from `auth-source-search :host <server host>`,
  falling back to the `the-next-thing-token` variable.
- Commands:
  - `the-next-thing-add` — prompt for a URL (default: URL at point / kill ring) plus an optional note.
  - `the-next-thing-add-at-point` — org link at point (`org-element-context`) or `thing-at-point 'url`.
  - `the-next-thing-add-region` — all URLs/org links in the region; reports added vs duplicates.
  - `the-next-thing-list` — `*the-next-thing*` tabulated list, columns State · Added · Title · URL.
    Keys: `RET` browse-url, `r` read, `x` uninteresting, `u` unread, `f` cycle filter
    (unread→read→uninteresting→all), `g` refresh, `d` delete (with confirmation).
- Nix: `emacsPackages.trivialBuild` → `packages.emacs-package`.

## Android app (`android/`)
- Kotlin, Jetpack Compose, Material 3, minSdk 26. Room (cache + pending ops),
  Retrofit/OkHttp + kotlinx.serialization, WorkManager (sync), DataStore (server URL + token).
- Deviation: sync pulls a **full** `state=all` snapshot (not `updated_since`) so server-side deletes are
  noticed; the server still supports `updated_since`.
- Sync: Room `items` mirrors the server; the `pending_ops` table holds ordered ADD (with a local temp
  id) and SET_STATE ops. `SyncWorker` (network constraint, unique work; triggered on app start,
  pull-to-refresh and after each action) flushes ops in order (ADD swaps the temp id for the
  server id), then `GET /api/items?state=all&updated_since=` and upserts. Last write wins (single user).
- Main screen: card (title, falling back to URL; host, note, age). Buttons: *Latest* (default mode),
  *Random* (re-roll, excludes latest), *Open* (`ACTION_VIEW` → YouTube app/browser), *Read*,
  *Uninteresting*. Marking updates local state, enqueues an op and shows a Snackbar *Undo*
  (enqueues a reset to unread), then the view returns to latest. Unread count, sync indicator, pull-to-refresh.
- `ShareActivity`: `ACTION_SEND text/plain` intent filter, extracts the first URL, enqueues an ADD,
  shows a Toast, finishes (no UI).
- Settings screen (server URL, token, test connection). List screen (tap to open, swipe to mark).

## Build & test
- Server: `cd server && go test ./...`; local run:
  `go run ./cmd/the-next-thing -token-file token.txt -db dev.db`, then exercise with curl.
- Nix: `nix build .#server .#emacs-package`, `nix flake check` (includes the VM test). Update
  `vendorHash` (set `lib.fakeHash`, build, copy the reported hash) after changing Go deps.
- Emacs: `emacs -Q --batch -f batch-byte-compile emacs/the-next-thing.el` (no warnings) plus ERT
  tests (URL extraction from regions/org links).
- Emacs tests: `emacs -Q --batch -L emacs -l emacs/test/the-next-thing-test.el -f ert-run-tests-batch-and-exit`.
- Android: `nix develop -c ./gradlew assembleDebug testDebugUnitTest` (unit tests: URL extraction,
  latest/random selection, op-queue flush with MockWebServer).

## Environment notes
- The sandbox sees the Nix store but has **no `nix` client**: ask the user to run nix commands, then
  `source .direnv/sbx-env.sh` (per Bash call) for Go/Gradle/Emacs/Android SDK. Maven/Go-proxy are reachable,
  so `./gradlew` and `go get` work directly (use `--no-daemon`).
- Nix only sees git-tracked files: `git add -N` new paths before `nix build`.
- The repo has no commits yet; branch `main`.
- Full implementation plan: `~/.claude/plans/create-an-android-app-iterative-wadler.md`
  (not in the repo; this file is the durable copy).

## Conventions
- Server is the source of truth; clients never invent state except queued offline ops.
- Keep dependencies minimal: stdlib-first Go, built-ins-only Emacs Lisp, pure-Go SQLite.
- Match the surrounding style; comments sparingly.
