# TasticLobby 1.0 – Architecture

## Lifecycle

`TasticLobbyPlugin` → `LobbyBootstrap` (composition root). Services implement TasticCore's `Service`
(`id/start/stop`), are started in dependency order and stopped in reverse; a start failure stops what runs.

Order: `LobbyConfigurationService` → `LobbySettingsRegistrar` (registers `lobby.*` SettingKeys in TasticCore) →
`LobbyMessages` (EN/DE/HI, MiniMessage) → `LobbyApiService` (shared `TasticApiClient`) → `LobbyPlayerService` →
`LobbyTelemetryService` → `SocialSnapshotService` → `LobbySpawnService` → `WorldEnvironmentService` →
`PlayerVisibilityService` → `LobbyItemService` → `LobbyPlayerInitializationService` → `CosmeticService` →
`LobbyHudService` → `MusicService` → `GatewayService` → `SocialActionService` → dialog services →
`CookieModule` (its own service graph, see below) → listeners → commands → PlaceholderAPI expansion → position sampler.

## Player pipeline

`PlayerJoinEvent` (join message suppressed, runtime created) → TasticCore loads the account →
`TasticPlayerReadyEvent` → onboarding check (`LanguageDialogService`, blocking, retry on failure) →
`LobbyPlayerInitializationService.initialize` (idempotent): adventure state, inventory normalization, items,
visibility, spawn; post-init hooks: HUD, music, cosmetics render, social snapshot, cookie preload →
first-join `WelcomeDialogService` (completes backend onboarding, `welcome.seen` preference).
`PlayerQuitEvent`: cookie unload/save, music stop, HUD hide, cosmetics cleanup, caches invalidated.

## Integration boundaries

* **TasticCore**: player accounts, settings (`SettingRegistry`, `PlayerSettingUpdateDispatcher`), localization of the
  account language, onboarding. Not duplicated.
* **TasticProxy / API**: friends, parties, clans, presence, routing/transfers, telemetry are consumed through the shared
  API client; the lobby renders and requests, never persists network state. Notifications to remote players are sent
  as `player.message` commands over the API command bus and rendered by the proxy.
* **API (lobby domain)**: cookie profiles (optimistic locking, idempotent prestige/offline/admin operations),
  cosmetics ownership/equipped state, lobby preferences, leaderboards.

## UI

Paper Dialog API everywhere (`DialogSupport`: menus, notices, confirmations, forms; callbacks validate the clicking
player and run on the main thread). Domain dialogs: language, welcome, settings, gateway, profile, social
(friends/party/clan), cosmetics, cookie (overview/shop/upgrades/prestige/tree/stats/leaderboard/travel/offline).

## Cookie module

`CookieModule` wires the pure domain engine (`de.tasticgames.lobby.cookie.domain`, no Bukkit) with runtime services:
`CookieRuntimeService` (sessions, 1 s production tick, dirty batching, save loop, version conflicts, pause on persistence
failure, prestige/offline through idempotent API operations), `CookieWorldService` (world resolution or flat dev world,
enter/leave, zone gates/discovery, POIs, fast travel), `CookieClickService` (Interaction + ItemDisplay main cookie,
server-side click validation, feedback), `GoldenCookieService` (player-specific spawns, ownership validation, cleanup),
`CookieNpcService` (native NPC villagers with quests), `CookieLeaderboardService` (cached), `CookieDialogService`,
`CookieAdminCommand` (mutations via API + builder tools writing `cookie-clicker.yml`).

## Caches

| Data | Cache | TTL / invalidation |
|---|---|---|
| Social snapshot (friends/party/clan/presence) | per player | 15 s, invalidated after every action / quit |
| Network snapshot (server types, maintenance) | global | refreshed every 15 s |
| Cosmetics ownership/equipped | per player | replaced on every mutation, cleared on quit |
| Cookie leaderboards | per type | `runtime.leaderboard-cache-seconds` |
| Cookie profile | session | API is the source of truth; saved every `save-interval-seconds` while dirty |

## Telemetry

Bounded queue → `POST /api/v1/telemetry/events` (source = backend server id). Events: `lobby.join/quit/ready`,
`lobby.item_use`, `lobby.launchpad_use`, `lobby.spawn_pad_use`, `lobby.void_rescue`, `lobby.gateway_open`,
`lobby.gateway.transfer_request`, `lobby.profile_open`, `lobby.social_open`, `lobby.social_action`,
`lobby.cosmetics_open`, `lobby.cosmetic_equip`, `lobby.admin_command`, `lobby.position_sample` (heatmap foundation),
`cookie.session_started/ended`, `cookie.clicked` (aggregated per minute), `cookie.generator_bought`,
`cookie.upgrade_bought`, `cookie.tree_node_bought`, `cookie.prestige_completed`, `cookie.zone_discovered`,
`cookie.poi_visited`, `cookie.golden_spawned/clicked`, `cookie.achievement_unlocked`, `cookie.offline_reward_claimed`,
`cookie.quest_completed`, `cookie.admin_mutation`.

## Daily rewards
`daily/` holds the streak feature: one claim per day (`/daily`, alias `/streak`), a seven day cycle and
milestones at 3/7/14/30/60/100 days. `DailyStreak` is pure date arithmetic (next day continues, a gap starts
over at day 1, the record is kept), `DailyRewardTable` reads `config/daily.yml`, `DailyRewardService` books
the rewards and `DailyDialogService` draws the cycle.

Cookie rewards are measured in seconds of the player's own production, so day 7 is worth the same relative
amount at prestige 0 and at prestige 10; every streak day adds 3 % on top, capped at +100 %. Crumbs,
cosmetics and special cookies are flat. The day rolls over at midnight in `reset-zone` for everybody, not in
local time, so "be there at reset" means the same moment for the whole network.

State is stored in the player's TasticCore settings (`lobby.daily.last-claim|streak|best-streak|total`), so
it travels with the account, needs no schema of its own and survives a server restart. The claim refuses to
run while the cookie profile is not loaded - that way a claim never hands out a shrunken reward and burns the
day.
