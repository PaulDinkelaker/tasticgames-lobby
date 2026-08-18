# Cookie Clicker – Design & Balancing

Persistent lobby minigame, Prestige 0–10. Domain engine: `de.tasticgames.lobby.cookie.domain`
(pure Java, 145 unit tests). Runtime/UI: `de.tasticgames.lobby.cookie`. Persistence: TasticGames API
(`/api/v1/lobby/cookie/**`, MariaDB `cookie_*` tables, optimistic locking, idempotent operations).

## Layout (config-version 4)
* **Main cookie in the lobby** (`spawn` at -62.5/35/12.5): MythicMobs mob `fv_cookie_clicker` → ModelEngine model
  `fv_cookie_clicker` → native item display. **Left click = bake, right click = cookie menu** (also the cookie hotbar
  item and `/cookie`). Actionbar shows cookies/CPS on every click and every second inside the cookie zone (8 blocks).
  The visual is spawned only after ModelEngine registered its models (`ModelRegistrationEvent FINISHED`); the binding
  result is logged explicitly (see docs/tasticlobby-integrations.md).
* **Sessions are per online player** (loaded after the lobby init, ticked everywhere, saved every 10 s, offline
  production claimed via dialog) – no hotbar swap, no separate cookie items.
* **Special cookies** trigger for players inside the lobby area around the main cookie (`main-cookie.special-cookies.areas`)
  and anywhere in the open world. `special-cookies.mode: auto` (default) activates them **for the player directly**
  (title, sound, particles, reward – nothing to find); `mode: spawn` places a clickable, rarity-coloured cookie nearby.
* **Quest NPCs** (baker `mama_bakewell`, merchant `gustave`) stand next to the main cookie (Citizens when installed, else
  native villagers). config-version 3 removed `babette` and `king_frosting`, config-version 4 renamed the
  `golden-cookies` sections to `special-cookies` and `balancing.golden` to `balancing.special`; existing files are
  migrated in place (the old keys keep working until they are).
* **Open world** (`cookie`, flat dev world created when missing) is the **prestige-10 endgame** (`open-world.required-prestige`):
  `/cookie world`, the menu button or fast travel; zones/gates/POIs/discovery live there. Leaving: `/lobby`, menu, world change.
* Old 1.0.0 files (`world:` root key) are detected: the bundled layout is used and a warning asks to delete the file.

## Currencies
* **Cookies** – active balance (reset on prestige)
* **Lifetime cookies** – never reset, drives prestige requirements and leaderboards
* **Heavenly crumbs** – permanent prestige currency: `floor(cbrt(lifetime / 1e6)) − crumbsEarnedTotal` on prestige;
  spent in the prestige tree. Never re-earned for the same lifetime progress.

All amounts are `BigDecimal` (`CookieAmount`, scale 10) and travel as decimal strings; the DB stores `DECIMAL(65,10)`.

## Core formulas
* Generator unit cost: `ceil(baseCost · 1.15^owned)`; bulk cost = exact sum of unit costs (MAX by log estimate + correction).
* Generator output: `baseCps · 2^(milestones reached at 10/25/50/100/200) · tier upgrades · prestige multiplier · tree · buffs`.
* Click: `clickValue = base(1 + upgrades) · CPS% additions · combo(1.0/1.1/1.25/1.5/2.0) · click buffs`,
  server-side cap `max-clicks-per-second` (extra clicks count but reward 0 → telemetry).
* Passive: `elapsedSeconds · effectiveCps` (time-based, lag/restart resistant).
* Offline: `min(offline, 8 h) · CPS · 50 % · (1 + night_shift/tree bonuses)`, claimed once (idempotent, `offlineClaimedUntil`).

## Generators (id · base cost · base CPS · unlock prestige)
cursor 15/0.05/0 · baker 100/0.3/0 · oven 1,100/2.5/0 · sugar_farm 12,000/47/1 · cocoa_mine 130,000/260/2 ·
cookie_factory 1.4M/1,400/3 · alchemy_kitchen 20M/7,800/4 · portal_bakery 330M/44,000/6 · time_oven 5.1B/260,000/7 ·
galactic_bakery 75B/1.6M/8 · reality_forge 1.2T/10M/9. (P0 CPS lowered vs. the first draft so P1 is not reached in
15 minutes – see `cookie-balancing.md`.)

## Upgrades (45)
click power (reinforced_index_finger, carpal_tunnel, ambidextrous, thousand/million_fingers), 2 tiers per generator
(x2 at ≥1 / ≥10 owned), golden_luck/golden_glow, the four rarity upgrades (refined_sugar, platinum_press,
diamond_cutter, master_recipe_book), sugar_rush (combo), night_shift (offline), kitchen_synergy,
9 prestige-gated "recipe" multipliers (bought each run) that make P10 reachable.

## Prestige 0–10 (lifetime requirement · total multiplier · zone · rewards)
| P | requirement | mult | zone | rewards |
|---|---|---|---|---|
| 0 | – | 1.00 | bakery_square | – |
| 1 | 1e6 | 1.25 | sugar_fields | sugar_trail, sugar_farm |
| 2 | 1e9 | 1.60 | cocoa_caverns | cocoa_profile_background, cocoa_mine |
| 3 | 1e12 | 2.10 | factory_district | factory_title, cookie_factory |
| 4 | 1e15 | 2.80 | arcane_pantry | arcane_aura, alchemy_kitchen |
| 5 | 1e18 | 3.80 | royal_frosting_keep | royal_frame |
| 6 | 1e22 | 5.20 | rift_bakery | rift_trail, portal_bakery |
| 7 | 1e26 | 7.20 | chrono_kitchen | chrono_back_item, time_oven |
| 8 | 1e29 | 10.00 | stellar_confectionery | stellar_aura, galactic_bakery |
| 9 | 1e33 | 14.00 | reality_crust | reality_background, reality_forge |
| 10 | 1e36 | 20.00 | ascendant_sanctum | ascendant_frame/background/title/aura/trail |

Reset: cookies, generators, run upgrades, buffs, combo. Kept: lifetime, crumbs, prestige tree, achievements, zones,
cosmetics, stats. P10 → P11 is impossible. Prestige is a two-step confirmation dialog and a single idempotent
API transaction (`POST …/prestige`, operationId, expectedVersion); cosmetics are unlocked server side.

Simulated time (reasonable active player): P1 ≈ 0.7 h, P3 ≈ 9 h, P5 ≈ 49 h, P8 ≈ 243 h, P10 ≈ 417 h.

## Prestige tree (crumbs)
iron_fingers (+10 % click/lvl, 10) · efficient_ovens (+5 % CPS/lvl, 20) · night_bakers (+10 % offline/lvl, 5) ·
lucky_charms (+10 % special chance/lvl, 5) · connoisseur (+10 % platinum+ weight/lvl, 5) · lasting_glow (+10 % buff
duration/lvl, 5) · sugar_high (+10 % combo duration/lvl, 5) · head_start (+1000 starting cookies/lvl, 10) ·
helping_hands (+1 starting cursor/lvl, 10). Cost `base · 2^level`.

## World / zones / POIs
`cookie-clicker.yml` defines the world (a flat development world is created when missing), 11 zone regions with
entry/gate-return points (min prestige from the catalog), POIs (stable ids `cookie.*`), NPCs and golden areas. Builders
replace coordinates without code changes (`/cookieadmin setspawn|setcookie|poi`).

## Special cookies (rarities)
**Rare events, not a per-second lottery.** Every session draws its own next-spawn instant
`now + uniform(15 min, 120 min) / chanceMultiplier`, clamped to at least `5 min`, redrawn after every activation and
whenever the player is outside a special cookie area. The timer lives in memory per session, so a relog restarts the
wait: a fresh draw is never shorter than `min/chance` (≥ 400 s with every chance upgrade) and never shortens a wait
already in progress, which is why restarting is not exploitable. `chanceMultiplier` keeps its old sources –
`golden_luck` (×1.5) and the `lucky_charms` tree node (+10 %/level) now **shorten the wait** instead of raising a
per-second chance.

Special cookies are a **prestige-1 unlock** (prestige 0 gets none). The rarity is drawn by weight over the unlocked set:

| rarity | prestige | weight | colour | rewards |
|---|---:|---:|---|---|
| SILVER | 1 | 60 | `#c8d2dc` | LUCKY min(5 % bank, 5 min CPS)+13 · FRENZY ×3/30 s · CLICK_FRENZY ×30/12 s |
| GOLDEN | 1 | 30 | `#ffd82b` | LUCKY min(15 %, 15 min)+13 · FRENZY ×7/30 s · CLICK_FRENZY ×777/13 s · CHAIN 5 % |
| PLATINUM | 2 | 7 | `#8ef6ff` | LUCKY min(25 %, 30 min) · FRENZY ×15/45 s · CLICK_FRENZY ×1 500/15 s · CHAIN 8 % |
| DIAMOND | 4 | 2.5 | `#5bf0c8` | LUCKY min(40 %, 45 min) · FRENZY ×30/60 s · CLICK_FRENZY ×5 000/18 s · BLESSING ×30 CPS + ×2 000 click/30 s |
| MASTER | 7 | 0.5 | `#ff5ecb` | LUCKY min(77 %, 90 min) · FRENZY ×77/77 s · CLICK_FRENZY ×7 777/25 s · BLESSING ×77 CPS + ×7 777 click/45 s |

`BLESSING` is one event carrying two buffs (a CPS-only and a click-only one), so the stats maths stays untouched.
Mode `auto` (default): the reward is applied immediately with a coloured title (`cookie.special.title` /
`cookie.special.activated`). Mode `spawn`: visible only to the owner, 15 s lifetime, ownership-validated click.
The persisted counter `goldenCookiesClicked` and the achievements `first_golden`/`golden_100` count every rarity;
the season pass scales its XP by rarity (SILVER 1× … MASTER 5× of `xp.per-golden`).

**Adjustable probabilities.** Cookie upgrades `refined_sugar` (P1, 5 M, GOLDEN ×1.5), `platinum_press` (P2, 500 M,
PLATINUM ×1.5), `diamond_cutter` (P4, 1e13, DIAMOND ×1.5) and `master_recipe_book` (P7, 1e24, MASTER ×2.0) plus the
crumb node `connoisseur` (5 crumbs, 5 levels, +10 %/level for PLATINUM and above) scale the weights per player;
`CookieStats.specialWeightMultipliers` exposes the result so the UI and `/cookieadmin balance` show the real chances.

## Achievements
first_cookie, clicks_100/1000/10000, lifetime_1m/1b, first_generator, generators_100/500, first_golden, golden_100,
first_prestige, prestige_5, prestige_10, all_zones_discovered + NPC quests (npc_quest_starter/explore/timed/prestige).
Rewards: cosmetics (`ACHIEVEMENT_<id>` unlock in `cosmetics.yml`), crumbs (quests).

## Admin tools
`/cookieadmin status|balance <player>` (effective click/CPS incl. every multiplier and per-generator contribution),
`addcookies|setcookies|setprestige|reset|unlock` (API `POST …/admin`, audited in `cookie_admin_audit`),
builder helpers (write cookie-clicker.yml + live reload): `setcookie` (main cookie here), `setspawn` (open-world entry),
`npc <id> here|link|unlink` (Citizens), `special add|remove <id>` (special cookie area, `golden` still accepted) and
`zone <id> fromselection|entry|gate` (WorldEdit selection), `poi <id> [type]`.

## Exploit protection
Server-side rewards only; click rate cap; bounded click history; optimistic locking (409 → reload); idempotent
prestige/offline/admin operations (operationId); saves flushed before server-side operations – a save completes only
after the new version was adopted on the main thread, and a prestige that still hits `VERSION_CONFLICT` (autosave/other
server bumped the version) syncs the server state and retries with the same operationId (3 attempts); interactions only
inside the cookie world; special cookies validated per owner and never granted below the interval floor (a relog
redraws a full interval instead of shortening the wait); no items represent progression.

## Shift orders
Three goals run next to the normal baking (`CookieOrderService`, `OrderBoard`): bake N cookies, click N times, buy N
generators, buy N upgrades, collect a special cookie. Every slot carries a different goal type, the targets are rolled
from the player's own production (a "bake N cookies" order asks for 3-6 minutes of production, at least 60 clicks worth)
and the reward is paid in seconds of that same production (150-300 s), so an order is worth the same relative amount at
prestige 0 and at prestige 10. A claimed order is replaced immediately; at most 8 orders per player and hour count
(`OrderBoard.CLAIMS_PER_HOUR`), which keeps the board a goal list instead of an income source. The board lives in the
session: it is a goal for the current shift, not a multi-day task, and is deliberately not persisted.

## Baking styles
From prestige 1 a run can be steered with exactly one style (250,000 cookies, exclusive group `baking_style`):
`style_artisan` (x4 click power), `style_industrial` (x1.4 production), `style_lucky` (x2 special cookie chance).
The engine hides the other options once one is bought (`UpgradeDefinition#exclusiveGroup`), and a prestige clears the
choice with the other upgrades - the next run can be played differently.

## Quest NPCs
Removed with config-version 5: the Cookie Clicker is played at the main cookie and through its dialogs. The
migration deletes `mama_bakewell` and `gustave` from an existing `cookie-clicker.yml` and sets `npcs.enabled:
false`; NPCs an operator added by hand stay in the file. The NPC quests (`npc_quest_*`) can no longer be started,
their crumb rewards are gone with them - shift orders took over that role.
