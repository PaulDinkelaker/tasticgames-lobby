# TasticLobby 1.0 – Deployment

## Required on the lobby server
* Paper 1.21.11, Java 25
* `TasticCore-1.0.0` (unchanged), `tasticgames-lobby-1.0.0.jar`, LuckPerms; PlaceholderAPI + UltimateUI (scoreboard) + TAB (tablist);
  optional MythicMobs, ModelEngine, Citizens, ItemsAdder, HMCCosmetics, WorldEdit/FAWE, Multiverse-Core (see `docs/tasticlobby-integrations.md`)
* Worlds: lobby world `spawn` (spawn -23.5/37/-3.5), cookie open world `cookie` (created flat when missing)
* TasticGames API ≥ this release (Flyway `V13`–`V15` = cookie/cosmetics/preferences tables) reachable from the lobby
* API credentials: **none needed** when TasticCore is configured – the lobby reuses `plugins/TasticCore/config/api.yml`
  (service `tastic-core-lobby`). Overrides: `TASTIC_API_KEY`/`TASTIC_API_BASE_URL` or `config/api.yml`.
  `/tasticlobby status` → "API credentials" shows the source and whether the API rejected the key or is outdated.

## Order
1. Deploy the API (additive migrations `V5`–`V15`), verify `/api/v1/health`.
2. Deploy TasticProxy 1.0 (gateway transfers need `POST /api/v1/network/transfers` and the proxy command bus).
3. Backup `plugins/TasticLobby/` and the current lobby JAR to `backups/tasticlobby/<timestamp>/`.
4. Replace the JAR (exactly one `tasticgames-lobby-*.jar` in `plugins/`), start the server.
5. Configure `config/lobby.yml` (world `spawn`, spawn, launchpad/teleport regions – or `/tasticlobby region …`),
   `config/cookie-clicker.yml` (main cookie `-62.5 35 12.5` + second cookie `-62.5 35 -19.5`, MythicMobs type, NPCs, open world), `config/items.yml`
   (asset ids / ItemsAdder ids), `config/music.yml`, `config/cosmetics.yml` (`render: hmc:<id>` for HMCCosmetics).
   **Upgrading from 1.0.0:** delete `plugins/TasticLobby/config/cookie-clicker.yml` (old layout) so the new file is generated.
6. Restart or `/tasticlobby reload` (config + messages only).

## Startup verification (log)
`Starting TasticLobby 1.0.0`, `Connected to TasticCore API`, `Registered 9 lobby settings`, `Loaded lobby messages: … keys, 3 languages`,
`TasticGames API client initialized`, `API connection established`, `Cosmetic catalog loaded`, `Cookie world ready`
(or the flat-world creation notice), `Spawned 4 cookie NPC(s)`, `Cookie Clicker module started`,
`Integrations: LuckPerms=on, TAB=…`, `Main cookie spawned in spawn at -62,35,12 (backend mythicmobs|modelengine|native)`,
`Cookie NPCs: 4/4 spawned via Citizens|Paper`, `API authentication verified`, `Registered PlaceholderAPI expansion 'tastic'`,
`TasticLobby bootstrap finished`. No exceptions. Red flags: `API rejected the credentials`, `does not know the 1.0 endpoints`.

## Smoke test
Join → language dialog (new account) → welcome → hotbar items → `/lang de|en|hi` → `/settings` (every category opens,
music/visibility save) → slime block launch, gold block (inside a configured region) teleport → `/gateway` → `/profile` →
`/social` (friends/party/clan dialogs) → `/cosmetics` equip → click the big cookie at spawn (actionbar) → sneak-click →
buy cursor → rejoin → progress kept (`/cookieadmin status <name>`) → `/cookieadmin setprestige <name> 10` → menu "Enter the
Cookie Open World" → `/lobby` back → `/tasticlobby status` (integrations, main cookie, API credentials).

## Rollback
Stop server → restore JAR + `plugins/TasticLobby/` from the backup → start. API migrations are additive.

## PhoenixLobby
TasticLobby 1.0 replaces the hub functions (spawn, items, launchpads, protection, visibility). Run both only during the
first smoke test; then disable PhoenixLobby to avoid two plugins controlling the inventory/spawn.

## Blockers on the build machine
No MintServers/SFTP credentials are present – deployment must be executed by an operator (artifacts are built and tested).
Final builder map / ModelEngine models (`fv_cookie_clicker`, `mama_bakewell`, …) are optional: the native renderer and the
flat development world keep everything functional until they exist.
