# Cookie Clicker – Balancing Notes

All numbers below come from `de.tasticgames.lobby.cookie.domain.sim.CookieSimulator`
(deterministic, no special cookies, no offline production). Run `CookieSimulatorTest` to
reproduce; `SimulationReport.toMarkdownTable()` prints the table.

## Player model used by the simulator

| Profile | Clicks | Shop | Notes |
|---|---|---|---|
| **active** (default, `SimulationConfig.activePlayer()`) | 5 clicks/s at max combo (x2) for the first **10 min of every run**, then idle | opens the shop every **30 s**, buys best payback item(s) | what the tests assert on |
| relentless (`relentlessClicker()`) | 5 clicks/s forever | buys the instant something is affordable | upper bound of progression speed |
| low activity | 1 click/s for the first 10 min | every 30 s | |

Purchase heuristic: score = `timeToAfford + cost / ΔCPS`, lowest wins (generators, generator
tiers, global upgrades, click upgrades while clicking). Special-cookie/combo/offline upgrades are
bought when they cost ≤ 5 % of the bank. Crumbs are spent greedily on the cheapest tree node after
each prestige. Prestige happens the moment it is possible.

## Estimated game hours per prestige (default catalog + balancing)

| Prestige | active: reached after (h) | active: level duration (h) | relentless (h, cumulative) | 1 click/s (h, cumulative) |
|---|---:|---:|---:|---:|
| P1  | 0.67 | 0.67 | 0.55 | 0.93 |
| P2  | 2.22 | 1.55 | 1.88 | 2.67 |
| P3  | 8.80 | 6.58 | 7.38 | 9.41 |
| P4  | 24.78 | 15.98 | 20.68 | 25.48 |
| P5  | 49.78 | 25.00 | 41.48 | 50.52 |
| P6  | 111.41 | 61.62 | 92.79 | 112.17 |
| P7  | 173.62 | 62.21 | 144.57 | 174.40 |
| P8  | 244.11 | 70.49 | 203.25 | 244.91 |
| P9  | 323.98 | 79.88 | 269.75 | 324.81 |
| P10 | 417.53 | 93.55 | 347.65 | 418.37 |

Test assertions: P0→P1 within 0.5 h … 6 h of active play (actual ≈ 40 min; ≈ 33 min even for a
relentless clicker), P10 reachable in < 5000 game hours (actual ≈ 417 h).

## What was kept exactly as specified

* Prestige thresholds (1e6 … 1e36) and multipliers (1.00 … 20.00), themes, zones, cosmetics.
* Generator ids, order, base costs, unlock levels, milestones (10/25/50/100/200, x2 each), cost growth 1.15.
* Base CPS of every generator from `sugar_farm` upwards.
* Crumb formula `floor(cbrt(lifetime / 1e6))` with the earned-total baseline.
* Combo stages, click cap, offline rules.

## Deviations from the numbers given in the specification (and why)

### 1. P0 generators produce less: cursor 0.05 (was 0.1), baker 0.3 (was 1), oven 2.5 (was 8)

With the classic values the P0 economy (three generators, doubling milestones at 10 and 25 owned,
two x2 tier upgrades) explodes exponentially and reaches the 1e6 lifetime threshold in ~15 min –
regardless of how the player is modelled (0.23 h relentless, 0.27 h with a 30 s shop cadence,
0.30 h with a 60 s cadence, 0.44 h even with milestones removed). Clicking is not the driver: the
result barely changes between 1 and 5 clicks/s. The only levers that reach the required
"≥ 30 min" window without touching the (fixed) milestones or the cost ladder are the P0 base CPS
values. Costs were kept because they are what the player sees first and they continue smoothly
into `sugar_farm` (12 000). Effect on later levels is negligible because every later run is
dominated by the newest generator plus the recipes below.

### 2. Added prestige-gated "recipe" upgrades (GLOBAL_CPS_MULTIPLIER)

The prestige thresholds grow by x1000 per level (x10 000 at P5→P6 and P6→P7) while a new generator
only adds ~x5.5 CPS for ~x10 cost and the prestige multiplier tops out at x20. Without an
additional multiplicative source the given generator table cannot reach 1e36 within any
practical time (rough estimate: > 1e20 game hours). Instead of rewriting the generator table
(which would have needed CPS values 1e17 times larger at the top end) one global upgrade per
prestige level was added. They are cleared on prestige like every upgrade and re-bought each run
(cost = the threshold of the level that unlocked them / 50), so the early part of every run is a
quick climb through the already-known content:

| Level | Upgrade id | Multiplier | Cost |
|---|---|---:|---:|
| P1 | `sugar_awakening_recipe` | x2 | 2e4 |
| P2 | `cocoa_frontier_recipe` | x12 | 2e7 |
| P3 | `industrial_recipe` | x20 | 2e10 |
| P4 | `arcane_recipe` | x60 | 2e13 |
| P5 | `royal_recipe` | x900 | 2e16 |
| P6 | `dimensional_recipe` | x900 | 2e20 |
| P7 | `chrono_recipe` | x90 | 2e24 |
| P8 | `stellar_recipe` | x900 | 2e27 |
| P9 | `reality_recipe` | x90 | 2e31 |

The uneven pattern mirrors the uneven threshold jumps and generator unlocks: P5 has no new
generator but a x1000 threshold jump, P6/P7 have x10 000 jumps, P8/P9 have x1000 jumps with strong
generators. Multipliers were tuned so that each level takes longer than the previous one
(≈ 0.7 h → 1.6 h → 6.6 h → 16 h → 24 h → 62 h → 62 h → 70 h → 80 h → 94 h).

### 3. Extra upgrades beyond the "~25" examples

36 non-recipe upgrades: 4 click upgrades + `million_fingers` (P1), 22 generator tiers
(owned ≥ 1 for 10x base cost, owned ≥ 10 for 100x base cost, x2 each), `golden_luck`,
`golden_glow`, the four rarity upgrades (`refined_sugar`, `platinum_press`, `diamond_cutter`,
`master_recipe_book`), `sugar_rush`, `night_shift`, `kitchen_synergy`. Plus the 9 recipes = 45 upgrades.

## Special cookies

Special cookies are deliberately **outside** the simulated income (the simulator ignores them), so
they are a bonus on top of every number above instead of a balancing assumption. The design target
is one event per player every 15–120 minutes (mean 67.5 min, floor 5 min); with every chance source
bought (`golden_luck` ×1.5 and `lucky_charms` at level 5, together ×2.25) the window shrinks to
6.7–53 min. Expected value per event at the shipped weights (60/30/7/2.5/0.5 from prestige 7 on):
90 % of the draws are SILVER or GOLDEN, 0.5 % are MASTER, so the ×77 / ×7 777 numbers stay a
memorable rarity rather than an income source. The four rarity upgrades (×1.5, ×1.5, ×1.5, ×2.0)
and the `connoisseur` node (+50 % on PLATINUM+ at max level) raise the combined chance of
PLATINUM-or-better from 10 % to 17.9 % (weights 60/45/15.75/5.625/1.5) – MASTER alone goes from
0.5 % to 1.2 %. Noticeable, not run-defining, and `refined_sugar` deliberately trades a little of
that rare share for a better common draw.

## Knobs for designers

Everything above is plain data (`DefaultCatalog`, `CookieBalancing`); a plugin can load its own
values from config and pass them to `CookieEngine`. If the recipes feel too strong, halve every
recipe multiplier and expect roughly twice the hours per level from P2 on. If P0 feels too slow,
raise the P0 CPS values towards the classic 0.1 / 1 / 8 (each halving of the gap roughly halves
the P1 time again).

## Special cookies (measured, after the payout caps)

Cadence: one draw per player every `uniform(15 min, 120 min) / chanceMultiplier`, mean **4 050 s = 0.89
activations per hour**; with `golden_luck` (x1.5) and `lucky_charms` maxed (+50 %) it reaches **2.0/h**. The wait
is never rerolled because a player is outside the area, and it survives a relog, so short sessions add up.

| rarity | share (P7+) | one every | 50 % chance within | mean payout |
|---|---:|---:|---:|---:|
| Silver | 59.1 % | 1.9 h | 1.3 h | 208 s of CPS |
| Golden | 29.6 % | 3.8 h | 2.6 h | 708 s of CPS |
| Platinum | 6.9 % | 16.3 h | 11.3 h | 1 676 s of CPS |
| Diamond | 3.0 % | 38.1 h | 26.4 h | 3 090 s of CPS |
| Master | 1.5 % | 76.1 h | 52.8 h | 6 736 s of CPS |

`master_recipe_book` (x2 weight) halves the Master wait to a 50 % chance within 26.8 h, together with the
frequency upgrades 11.9 h.

**Income share:** 639 s of CPS per activation on average, i.e. **+15.8 % on top of passive production**
(+35.5 % with every frequency upgrade bought), assuming the player actually clicks at the rate cap during click
buffs — an idle player gets roughly half of that. This is a real bonus, not the negligible extra the previous
version of this document claimed, but it no longer skips prestige levels.

**Why the caps exist:** click power is ~2 % of CPS, and 15 clicks/s at combo x2 turn a click multiplier `m`
running for `T` seconds into `0.6 * m * T` seconds of production. The shipped x7 777 Master frenzy over 25 s was
worth **32 h of CPS in a single activation** — more than a whole late prestige level. Two rails fix that:
`balancing.special.click-clamp-cps-seconds: 60` (one click can never pay more than a minute of production) and
much shorter click-buff windows (3-5 s for the strong rarities). CHAIN_BONUS is now capped by a CPS window like
LUCKY, so hoarding a bank cannot be farmed either.
