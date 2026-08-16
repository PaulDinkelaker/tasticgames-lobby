# Cookie Clicker – Design & Balancing

Persistent open-world lobby minigame, Prestige 0–10. Domain engine: `de.tasticgames.lobby.cookie.domain`
(pure Java, 131 unit tests). Runtime/UI: `de.tasticgames.lobby.cookie`. Persistence: TasticGames API
(`/api/v1/lobby/cookie/**`, MariaDB `cookie_*` tables, optimistic locking, idempotent operations).

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

## Upgrades (41)
click power (reinforced_index_finger, carpal_tunnel, ambidextrous, thousand/million_fingers), 2 tiers per generator
(x2 at ≥1 / ≥10 owned), golden_luck/golden_glow, sugar_rush (combo), night_shift (offline), kitchen_synergy,
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
lucky_charms (+10 % golden chance/lvl, 5) · lasting_glow (+10 % golden duration/lvl, 5) · sugar_high (+10 % combo
duration/lvl, 5) · head_start (+1000 starting cookies/lvl, 10) · helping_hands (+1 starting cursor/lvl, 10). Cost `base · 2^level`.

## World / zones / POIs
`cookie-clicker.yml` defines the world (a flat development world is created when missing), 11 zone regions with
entry/gate-return points (min prestige from the catalog), POIs (stable ids `cookie.*`), NPCs and golden areas. Builders
replace coordinates without code changes (`/cookieadmin setspawn|setcookie|poi`).

## Golden cookies
Per player, per second: `1/300 s · chance multiplier`; visible only to the owner, 15 s lifetime; rewards LUCKY
(min(15 % bank, 15 min CPS)+13), FRENZY (CPS ×7 for 30 s·duration), CLICK_FRENZY (×777 for 13 s), CHAIN_BONUS (5 % bank).

## Achievements
first_cookie, clicks_100/1000/10000, lifetime_1m/1b, first_generator, generators_100/500, first_golden, golden_100,
first_prestige, prestige_5, prestige_10, all_zones_discovered + NPC quests (npc_quest_starter/explore/timed/prestige).
Rewards: cosmetics (`ACHIEVEMENT_<id>` unlock in `cosmetics.yml`), crumbs (quests).

## Admin tools
`/cookieadmin status|balance <player>` (effective click/CPS incl. every multiplier and per-generator contribution),
`addcookies|setcookies|setprestige|reset|unlock` (API `POST …/admin`, audited in `cookie_admin_audit`),
`setspawn|setcookie|poi|zone` builder helpers.

## Exploit protection
Server-side rewards only; click rate cap; bounded click history; optimistic locking (409 → reload); idempotent
prestige/offline/admin operations (operationId); saves flushed before server-side operations; interactions only inside
the cookie world; golden cookies validated per owner; no items represent progression.
