# TasticLobby 1.0

The TasticGames hub: onboarding, lobby gameplay (launchpads, teleport pads, void rescue, protection),
lobby items, player visibility, settings, `/lang`, TasticGateway, profiles, social UI (friends/party/clan),
cosmetics (HMCCosmetics), music, a native context-aware top-screen HUD (boss bars + ItemsAdder icons), TAB placeholders and the persistent **Cookie Clicker**
(main cookie at spawn, Prestige 0–10, open world at Prestige 10).

* Paper 1.21.11 · Java 25 · TasticCore 1.0 (backend platform) · TasticProxy 1.0 (network) · TasticGames API
* Version `1.0.0` (1.0.1 layout: main cookie in the lobby, plugin integrations)

Docs: [Architecture](docs/tasticlobby-architecture.md) · [Deployment](docs/tasticlobby-deployment.md) ·
[Cookie Clicker](docs/cookie-clicker.md) · [Integrations](docs/tasticlobby-integrations.md) · [HUD](docs/tasticlobby-hud.md)

## Build

```bash
cd tasticgames-api-client && ./gradlew clean build publishToMavenLocal   # shared client (composite build also works)
cd tasticgames-core/tasticgames-core && ./gradlew clean shadowJar        # TasticCore JAR (compileOnly dependency)
cd tasticgames-lobby && ./gradlew clean build                             # -> build/libs/tasticgames-lobby-1.0.0.jar
```
The Core JAR is resolved from `../tasticgames-core/tasticgames-core/build/libs/tasticgames-core-*.jar`
or `-PtasticCoreJar=<path>`. The api-client + Jackson are shaded and relocated to `de.tasticgames.lobby.libs`.

## Configuration (`plugins/TasticLobby/config/`)

| File | Content |
|---|---|
| `lobby.yml` | world `spawn` + spawn (-23.5/37/-3.5), void rescue Y, fixed time/weather, mob spawning, collision, join/quit messages, staff flight, launchpads (material, velocities, cooldown, directional pads), teleport pads (material, everywhere/regions, cooldown), double jump, placeholder refresh, telemetry |
| `items.yml` | hotbar slots/materials/asset ids/ItemsAdder ids for the lobby items (no cookie items) |
| `music.yml` | playlists (sound key, duration, weight) for lobby/open world |
| `api.yml` | TasticGames API – optional: without a key the lobby reuses `plugins/TasticCore/config/api.yml` (env overrides `TASTIC_API_BASE_URL`, `TASTIC_API_SERVICE`, `TASTIC_API_KEY`, `TASTIC_LOBBY_SERVER_ID`) |
| `cosmetics.yml` | cosmetic catalog (id, category, rarity, unlock, render data – `hmc:<id>` = HMCCosmetics) |
| `hud.yml` | native top-screen HUD: rows, refresh, ItemsAdder icons/boxes (content export), colours |
| `cookie-clicker.yml` | main cookie (world/location/MythicMobs type/model/hitbox/actionbar/golden areas), NPCs, prestige-10 open world (zones, POIs, golden areas), runtime, balancing |
| `npcs.yml` | lobby service NPCs (Citizens): position/yaw, action (`PASS`, `TRANSFER` + target, `COOKIE`, `NONE`), skin texture pair or `mirror-skin`, look-close |

Secrets belong in environment variables or TasticCore's api.yml; a lobby key is only needed when a dedicated service should be used.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/lobby` (`/hub`, `/l`), `/spawn` | – | back to spawn (also leaves the open world); TasticProxy forwards `/lobby` to the lobby while you are on a lobby server |
| `/lang [de|en|hi]` (`/language`, `/sprache`) | – | language dialog / direct switch (Deutsch, English, हिन्दी) |
| `/profile [player]` | – | profile dialog |
| `/settings`, `/gateway` (`/play`), `/cosmetics`, `/social` | – | dialogs (the text commands `/friend`, `/party`, `/clan` are TasticProxy commands) |
| `/cookie [menu|stats|leaderboard|world|shop|upgrades|prestige]` | – | cookie menu / open world (prestige 10) |
| `/tasticlobby status|reload|player <name>|setspawn|build|region <teleport|launchpad> <add|remove> <id>|cosmetic catalog|inspect|grant|revoke` | `tasticlobby.status/reload/admin/setspawn/build/cosmetic.admin` | administration (`region` uses the WorldEdit selection) |
| `/cookieadmin status|balance <player>`, `addcookies|setcookies <player> <amount>`, `setprestige <player> <0-10>`, `reset <player>`, `unlock <player> <achievement|zone> <id>`, `setcookie`, `setspawn`, `npc <id> here|link|unlink`, `golden add|remove <id>`, `zone [<id> fromselection|entry|gate]`, `poi <id> [type]` | `tasticlobby.cookie.admin` | cookie administration + builder tools (live reload) |

Permissions: `tasticlobby.admin` (parent), `tasticlobby.status`, `tasticlobby.reload`, `tasticlobby.build`,
`tasticlobby.setspawn`, `tasticlobby.cookie.admin`, `tasticlobby.cosmetic.admin`, `tasticlobby.fly`,
`tasticlobby.visibility.always`.

## Settings (TasticCore SettingRegistry)


Lobby (per player, `/settings`): `lobby.hud.enabled|mode|status-row|hints`, `lobby.items.enabled`, `lobby.launchpads.enabled`,
`lobby.teleport-pads.enabled`, `lobby.double-jump.enabled`, `lobby.player-visibility`, `lobby.join-messages`,
`lobby.cookie-clicker.hud|effects|notifications|actionbar|click-sounds|combo-popups|special-alerts|offline-prompt|confirm-prestige`.

Core: `music.enabled`, `music.volume`, `sounds.enabled`, `sounds.volume`, `language`, `ui.animations`,
`accessibility.reduced-effects`, `notifications.events`, `chat.private-messages`, `social.friend-requests`,
`social.party-invites`, `cosmetics.visible`.
Lobby: `lobby.items.enabled`, `lobby.player-visibility` (ALL/FRIENDS/PARTY/FRIENDS_AND_PARTY/NONE),
`lobby.launchpads.enabled`, `lobby.teleport-pads.enabled`, `lobby.double-jump.enabled`, `lobby.hud.enabled`,
`lobby.cookie-clicker.hud`, `lobby.cookie-clicker.effects`, `lobby.cookie-clicker.notifications`.

## Lobby items (default hotbar)

0 Gateway · 1 Profile (player head) · 2 Social · 4 Cookie Clicker (menu) · 6 Cosmetics · 7 Settings · 8 Visibility (dye).
The same hotbar is used inside the cookie open world. Items are identified via PersistentDataContainer, cannot be
dropped/moved/swapped, may come from ItemsAdder and refresh on language/setting changes.

## Gateway

The lobby only requests logical targets (`SURVIVAL`, …) through `POST /api/v1/network/transfers`; TasticProxy routes
(region/health/capacity) and party leaders can take their party. Mode availability comes from the network registry snapshot.

## HUD & Placeholders

The lobby renders a context-aware top-screen HUD with boss bars (`docs/tasticlobby-hud.md`) and no scoreboard/tablist. `%tastic_rank%`, `%tastic_rank_display%`, `%tastic_rank_prefix%`,
`%tastic_language%`, `%tastic_visibility%`, `%tastic_friends_online%`, `%tastic_party_size%`, `%tastic_clan%`,
`%tastic_online%`, `%tastic_cookie_balance%`, `%tastic_cookie_cps%`, `%tastic_cookie_prestige%`, … – full list and
sample UltimateUI/TAB configs in [docs/tasticlobby-integrations.md](docs/tasticlobby-integrations.md).

## Troubleshooting

* "TasticGames API is NOT configured" → no key in env, `config/api.yml` or `plugins/TasticCore/config/api.yml`; features answer "temporarily unavailable".
* "The TasticGames API rejected the credentials" → the service key does not match `tasticgames.security.service-auth.services.<service>` on the API.
* "does not know the 1.0 endpoints (404)" → the deployed API is older than 1.0 – deploy `tasticgames-api` with Flyway V5–V15.
* "Cookie open world … not found – creating a flat development world" → expected until the builder map exists (`open-world.world`).
* "cookie-clicker.yml uses the 1.0.0 layout" → delete the file, it is regenerated with the main cookie in the lobby.
* Missing translation warnings list the keys per language – EN fallback is used.
* `/tasticlobby status` shows Core/API health + credential source, integrations, loaded players, music sessions, telemetry backlog, main cookie/NPC/open-world state.
