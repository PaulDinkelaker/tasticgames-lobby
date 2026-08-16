# TasticLobby 1.0

The TasticGames hub: onboarding, lobby gameplay (launchpads, teleport pads, void rescue, protection),
lobby items, player visibility, settings, TasticGateway, profiles, social UI (friends/party/clan),
cosmetics, HUD, music and the persistent open-world **Cookie Clicker** (Prestige 0–10).

* Paper 1.21.11 · Java 25 · TasticCore 1.0 (backend platform) · TasticProxy 1.0 (network) · TasticGames API
* Version `1.0.0`

Docs: [Architecture](docs/tasticlobby-architecture.md) · [Deployment](docs/tasticlobby-deployment.md) ·
[Cookie Clicker](docs/cookie-clicker.md)

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
| `lobby.yml` | world name/spawn, void rescue Y, fixed time/weather, mob spawning, collision, join/quit messages, staff flight, launchpads (material, velocities, cooldown, directional pads), teleport pads (material, everywhere/regions, cooldown), double jump, HUD, telemetry |
| `items.yml` | hotbar slots/materials/asset ids for lobby items and the cookie-world hotbar |
| `music.yml` | playlists (sound key, duration, weight) for lobby/cookie world |
| `api.yml` | TasticGames API (env: `TASTIC_API_BASE_URL`, `TASTIC_API_SERVICE`, `TASTIC_API_KEY`, `TASTIC_LOBBY_SERVER_ID`) |
| `cosmetics.yml` | cosmetic catalog (id, category, rarity, unlock, render data) |
| `cookie-clicker.yml` | cookie world layout (zones, POIs, NPCs, golden areas), runtime, balancing overrides |

Secrets belong in environment variables; `CHANGE_ME` disables the API integration (degraded mode).

## Commands

| Command | Permission | Description |
|---|---|---|
| `/lobby` (`/hub`, `/l`), `/spawn` | – | back to spawn (also leaves the cookie world) |
| `/profile [player]` | – | profile dialog |
| `/settings`, `/gateway` (`/play`), `/cosmetics`, `/social` (`/friends`, `/party`, `/clan`) | – | dialogs |
| `/cookie [stats|leaderboard|world|shop|prestige]` | – | cookie clicker |
| `/tasticlobby status|reload|player <name>|setspawn|build|cosmetic catalog|inspect|grant|revoke` | `tasticlobby.status/reload/admin/setspawn/build/cosmetic.admin` | administration |
| `/cookieadmin status|balance <player>`, `addcookies|setcookies <player> <amount>`, `setprestige <player> <0-10>`, `reset <player>`, `unlock <player> <achievement|zone> <id>`, `setspawn`, `setcookie`, `poi <id> [type]`, `zone` | `tasticlobby.cookie.admin` | cookie administration + builder tools |

Permissions: `tasticlobby.admin` (parent), `tasticlobby.status`, `tasticlobby.reload`, `tasticlobby.build`,
`tasticlobby.setspawn`, `tasticlobby.cookie.admin`, `tasticlobby.cosmetic.admin`, `tasticlobby.fly`,
`tasticlobby.visibility.always`.

## Settings (TasticCore SettingRegistry)

Core: `music.enabled`, `music.volume`, `sounds.enabled`, `sounds.volume`, `language`, `ui.animations`,
`accessibility.reduced-effects`, `notifications.events`, `chat.private-messages`, `social.friend-requests`,
`social.party-invites`, `cosmetics.visible`.
Lobby: `lobby.items.enabled`, `lobby.player-visibility` (ALL/FRIENDS/PARTY/FRIENDS_AND_PARTY/NONE),
`lobby.launchpads.enabled`, `lobby.teleport-pads.enabled`, `lobby.double-jump.enabled`, `lobby.hud.enabled`,
`lobby.cookie-clicker.hud`, `lobby.cookie-clicker.effects`, `lobby.cookie-clicker.notifications`.

## Lobby items (default hotbar)

0 Gateway · 1 Profile (player head) · 2 Social · 4 Cookie Clicker · 6 Cosmetics · 7 Settings · 8 Visibility (dye).
Cookie world: 0 Stats · 1 Generators · 2 Upgrades · 4 Prestige · 7 Fast travel · 8 Back to lobby.
Items are identified via PersistentDataContainer, cannot be dropped/moved/swapped and refresh on language/setting changes.

## Gateway

The lobby only requests logical targets (`SURVIVAL`, …) through `POST /api/v1/network/transfers`; TasticProxy routes
(region/health/capacity) and party leaders can take their party. Mode availability comes from the network registry snapshot.

## Placeholders (PlaceholderAPI, optional)

`%tastic_lobby_party%`, `%tastic_lobby_clan%`, `%tastic_lobby_visibility%`, `%tastic_lobby_cookie_balance%`,
`%tastic_lobby_cookie_cps%`, `%tastic_lobby_cookie_prestige%`.

## Troubleshooting

* "TasticGames API is not configured" → set `api.yml`/`TASTIC_API_KEY`; the lobby runs but cookie/cosmetics/social/gateway are unavailable.
* "Cookie world … not found – creating a flat development world" → expected until the builder map exists (`world.name` in `cookie-clicker.yml`).
* Missing translation warnings list the keys per language – EN fallback is used.
* `/tasticlobby status` shows Core/API health, loaded players, HUD/music sessions, telemetry backlog, cookie profiles/dirty/saving, world state.
