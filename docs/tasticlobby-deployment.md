# TasticLobby 1.0 – Deployment

## Required on the lobby server
* Paper 1.21.11, Java 25
* `TasticCore-1.0.0` (unchanged), `tasticgames-lobby-1.0.0.jar`, LuckPerms; optional PlaceholderAPI, Multiverse-Core
* TasticGames API ≥ this release (Flyway `V13`–`V15` = cookie/cosmetics/preferences tables) reachable from the lobby
* API service credentials for `tastic-core-lobby` (or a dedicated `tastic-lobby` service registered in the API's
  `tasticgames.security.service-auth.services.*`), supplied via `TASTIC_API_KEY` or `config/api.yml`

## Order
1. Deploy the API (additive migrations `V5`–`V15`), verify `/api/v1/health`.
2. Deploy TasticProxy 1.0 (gateway transfers need `POST /api/v1/network/transfers` and the proxy command bus).
3. Backup `plugins/TasticLobby/` and the current lobby JAR to `backups/tasticlobby/<timestamp>/`.
4. Replace the JAR (exactly one `tasticgames-lobby-*.jar` in `plugins/`), start the server.
5. Configure `config/api.yml` (or env), `config/lobby.yml` (world name/spawn, launchpad/teleport regions),
   `config/cookie-clicker.yml` (world name/layout), `config/items.yml` (asset ids once the resource pack ships them),
   `config/music.yml` (custom sound keys).
6. Restart or `/tasticlobby reload` (config + messages only).

## Startup verification (log)
`Starting TasticLobby 1.0.0`, `Connected to TasticCore API`, `Registered 9 lobby settings`, `Loaded lobby messages: … keys, 3 languages`,
`TasticGames API client initialized`, `API connection established`, `Cosmetic catalog loaded`, `Cookie world ready`
(or the flat-world creation notice), `Spawned 4 cookie NPC(s)`, `Cookie Clicker module started`,
`Registered PlaceholderAPI expansion 'tastic'` (optional), `TasticLobby bootstrap finished`. No exceptions.

## Smoke test
Join → language dialog (new account) → welcome → hotbar items → `/settings` change music/visibility → slime block launch,
gold block (inside a configured region) teleport → `/gateway` (Survival available/coming soon) → `/profile` → `/social` →
`/cosmetics` equip → `/cookie` → enter cookie world → click main cookie → buy cursor → exit → rejoin → progress kept
(`/cookieadmin status <name>`), `/tasticlobby status`.

## Rollback
Stop server → restore JAR + `plugins/TasticLobby/` from the backup → start. API migrations are additive.

## PhoenixLobby
TasticLobby 1.0 replaces the hub functions (spawn, items, launchpads, protection, visibility). Run both only during the
first smoke test; then disable PhoenixLobby to avoid two plugins controlling the inventory/spawn.

## Blockers on the build machine
No MintServers/SFTP credentials are present – deployment must be executed by an operator (artifacts are built and tested).
Final builder map / ModelEngine models (`fv_cookie_clicker`, `mama_bakewell`, …) are optional: the native renderer and the
flat development world keep everything functional until they exist.
