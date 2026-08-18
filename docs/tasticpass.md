# TasticPass – the season pass in the lobby

TasticPass is a network-wide season pass. The **season** (100 tiers, quests, achievements, prices)
lives in the TasticGames API, TasticCore owns the runtime state of every player, and TasticLobby
contributes the Cookie Clicker progress plus the whole player-facing experience.

```
Cookie Clicker  →  PassXpService (lobby, batched)  →  PlayerPassService (TasticCore, batched)  →  /api/v1/pass
                                                          ↓ events
                                        PassEventListener (title · sound · chat)
```

The lobby never talks to the pass REST API for gameplay. Everything a player triggers goes through
TasticCore, which caches the season, batches XP and quest metrics, and fires the Bukkit events the
lobby reacts to. Only `/passadmin` uses the API directly – see *Administration*.

## Player view

`/pass` (aliases `/battlepass`, `/bp`) opens the overview. Subcommands jump straight into a view:
`/pass rewards`, `/pass quests`, `/pass premium`, `/pass leaderboard`. The pass service NPC in the
lobby opens the same overview.

| Dialog | Content |
|---|---|
| **Overview** | season name and days left, level with a unicode progress bar, premium state, the next free and premium reward, how many rewards are waiting |
| **Rewards** | the 100-tier track, 10 levels per page, both tracks side by side with `★` (claimable), `✔` (claimed) and `🔒` (locked / premium) markers, per-tier claim buttons and *claim all* |
| **Quests** | daily / weekly / season sections with progress bars, XP rewards and the reset hint of each scope |
| **Premium** | what premium contains, the season price formatted in the player's locale (`9,99 €`), and how to get it. **Never a payment form** – premium is an entitlement |
| **Leaderboard** | the top players of the season (size from `pass.yml`) |

Feedback outside the dialogs: a title, a sound and the unlocked rewards in chat on a level up, an
actionbar plus a chat line when a quest completes, and one chat line per reward a claim granted.

When the pass API is unavailable every dialog shows a localized *temporarily unavailable* notice,
the commands answer the same way, and the rest of the lobby is unaffected.

### Placeholders

`%tastic_pass_level%`, `%tastic_pass_xp%`, `%tastic_pass_xp_next%`, `%tastic_pass_progress%`,
`%tastic_pass_premium%`, `%tastic_pass_season%`, `%tastic_pass_quests_done%`,
`%tastic_pass_claimable%`, `%tastic_pass_total_xp%` (PlaceholderAPI and TAB).

The pass has no lobby item of its own, so the HUD shows the pass level in the **profile** context
(`hud.ctx.profile.objective`) and the profile dialog carries a `TasticPass` line.

## XP sources in the lobby

`config/pass.yml` configures what Cookie Clicker contributes. The lobby accumulates everything in
memory and flushes every `xp.flush-interval-seconds` (default 10 s) – a click storm produces one
report, not one call per click. Leftover clicks are carried into the next flush.

| Hook | Metric | XP source | Default rate |
|---|---|---|---|
| main cookie click | `cookie.clicks`, `cookie.cookies_baked` | `COOKIE_CLICKS` | 25 clicks = 1 XP |
| generator purchase | `cookie.generators_bought` | `COOKIE_PURCHASE` | 2 XP per unit |
| upgrade purchase | `cookie.upgrades_bought` | `COOKIE_PURCHASE` | 2 XP |
| prestige | `cookie.prestige` | `COOKIE_PRESTIGE` | 500 XP |
| special cookie | `cookie.golden` | `COOKIE_GOLDEN` | 15 XP × rarity (SILVER 1× … MASTER 5×) |
| zone discovered | `cookie.zone_discovered` | `COOKIE_ZONE` | 50 XP |
| NPC quest completed | `cookie.npc_quest` | `QUEST` | 100 XP |
| achievement unlocked | – | `ACHIEVEMENT` | 0 (the API grants the achievement's own 150/400 XP) |

Cookie achievements are forwarded as `cookie.<id>` and unlocked idempotently. The metric counts one
per special cookie regardless of rarity; only the XP scales (`per-golden × passXpMultiplier`, so a
MASTER cookie is worth 75 XP at the default rate). The API enforces the daily caps server side
(clicks 400, purchases 300, golden 200, zones 150 per UTC day), so the rates above cannot be abused
by a fast clicker.

## Administration

`/passadmin` (permission `tasticgames.pass.admin`, included in `tasticlobby.admin`):

| Command | Effect |
|---|---|
| `status` | season, tier/quest counts, price, loaded states, pending progress |
| `grant <player> [seasonKey]` | grants the premium entitlement (source `ADMIN`, no payment involved) |
| `revoke <player> [seasonKey]` | revokes it again |
| `addxp <player> <amount>` | adds pass XP through the admin endpoint (audited with the actor) |
| `setlevel <player> <level>` | sets the pass level |
| `reload` | re-reads `config/pass.yml` (rates and toggles apply immediately) |
| `season import <file>` | imports a season JSON from the plugin data folder (`plugins/TasticLobby/…`) |

Every mutation carries an `operationId` and is safe to retry. `season import` refuses paths that
leave the plugin data folder. Season *activation* and *ending* are network operations and live on
TasticProxy's `/passadmin` – the lobby only imports.

`/pass` is deliberately **not** registered on TasticProxy: Velocity executes registered commands
itself and would shadow the lobby command permanently.

## Configuration (`config/pass.yml`)

```yaml
enabled: true          # off = no XP reporting, dialogs answer "temporarily unavailable"
npc: true              # the pass service NPC
dialogs: true          # the pass UI
leaderboard-size: 10
xp:
  flush-interval-seconds: 10
  clicks-per-xp: 25
  per-purchase: 2
  per-prestige: 500
  per-golden: 15
  per-zone: 50
  per-achievement: 0
  per-npc-quest: 100
premium:
  store-url: "https://tasticgames.de/store/pass"
  info-key: "pass.premium.howto"
```

## Messages

All player-facing text lives under `pass.*` in `messages/lobby_en|de|hi.properties`. Reward lines
are resolved from the tier's `display_key` when the bundle knows it, otherwise from the reward type
(`pass.reward.cookies|crumbs|cosmetic|xp_boost|feature`). Cosmetic rewards reuse the cosmetic name
(`lobby.cosmetic.<id>.name`), features use `pass.feature.<key>`, quests `pass.quest.name.<key>`.
`MessageBundleTest` enforces that EN, DE and HI carry the same keys **and** the same placeholders.
