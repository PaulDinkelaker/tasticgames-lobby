# TasticLobby – Plugin-Integrationen

TasticLobby arbeitet über die offiziellen APIs mit den folgenden Plugins zusammen. Alle Integrationen sind
**Soft-Dependencies**: fehlt ein Plugin (oder schlägt der Hook fehl), läuft die Lobby mit dem nativen Fallback
weiter und `/tasticlobby status` zeigt pro Integration `hooked (Version)` / `not installed` / `hook failed`.

| Plugin | Zweck in TasticLobby | Fallback ohne Plugin |
|---|---|---|
| **TasticCore** (Pflicht) | Spieler, Settings, Sprache (de/en/hi), Onboarding, API-Zugangsdaten (`plugins/TasticCore/config/api.yml` wird automatisch mitbenutzt) | – |
| **LuckPerms** | Rang (Primary Group, Display-Name, Prefix/Suffix, Weight) für Profil & Placeholders; Permission-Node-Grants (Cosmetic-Unlocks → HMCCosmetics-Rechte) | `group.<name>`-Permissions |
| **UltimateUI** | Scoreboard/Sidebar – TasticLobby rendert **kein** eigenes Scoreboard mehr, sondern liefert Werte als PlaceholderAPI-Placeholders `%tastic_…%` | – |
| **TAB** | Tablist/Nametags – dieselben Werte als native TAB-Placeholders `%tastic_…%` (ohne PlaceholderAPI) | PlaceholderAPI |
| **PlaceholderAPI** | Expansion `tastic` (siehe unten) | – |
| **MythicMobs** | Visual des Main-Cookies (Mob-Typ `CookieClicker` mit ModelEngine-Modell) | ModelEngine → nativer Item-Display |
| **ModelEngine** | Modell auf unsichtbarer Basis-Entity (Main-Cookie ohne MythicMobs) und NPC-Modelle; Klicks auf die Modell-Hitbox (`BaseEntityInteractEvent`) | nativer Item-Display |
| **Citizens** | Cookie-Quest-NPCs (eigenes In-Memory-Registry, oder Verknüpfung mit `/npc create`-NPCs über `citizens-id`) | native Villager |
| **ItemsAdder** | Custom-Items für die Lobby-Hotbar (`itemsadder: namespace:id` in `items.yml`), Neuvergabe nach `ItemsAdderLoadDataEvent` | Vanilla-Material |
| **HMCCosmetics** | Rendering von Cosmetics: Katalog-Einträge mit `render: hmc:<hmc-id>` werden über die HMCCosmetics-API an-/abgelegt (Besitz bleibt in der TasticGames-API) | nativer Renderer |
| **WorldEdit / FastAsyncWorldEdit** | Regionen aus der `//wand`-Auswahl: `/tasticlobby region …`, `/cookieadmin golden add …`, `/cookieadmin zone <id> fromselection` | Regionen manuell in YAML |
| **Multiverse-Core** | Welten `spawn` und `cookie` müssen vor TasticLobby geladen sein (Softdepend) | Bukkit-Weltladung |

## Placeholders (`%tastic_<key>%`)

Rohwerte ohne Farben – das Display-Plugin (UltimateUI/TAB) formatiert.

| Key | Wert |
|---|---|
| `rank`, `rank_display`, `rank_prefix`, `rank_suffix` | LuckPerms Primary Group / Display-Name / Prefix / Suffix |
| `language`, `language_code` | Sprache des Spielers (Deutsch/English/हिन्दी, de/en/hi) |
| `visibility` | ALL / FRIENDS / PARTY / FRIENDS_AND_PARTY / NONE |
| `server` | Backend-ID dieser Lobby (`api.yml server-id`) |
| `online` | Spieler auf diesem Server |
| `friends`, `friends_online` | Anzahl Freunde / davon online (Netzwerk) |
| `party_size`, `party_leader` | Party-Größe / Leader-Name |
| `clan`, `clan_tag` | Clan-Name / Kurzform |
| `in_open_world` | true/false – Spieler ist in der Cookie-Open-World |
| `ctx`, `ctx_title`, `ctx_label_1..4`, `ctx_value_1..4`, `ctx_hint`, `ctx_objective` | kontextabhängige HUD-Zeilen (Kontext = gehaltenes Item: LOBBY, COOKIE, SOCIAL, GATEWAY, PROFILE, COSMETICS, SETTINGS, VISIBILITY), lokalisiert – fertiges Top-Screen-HUD: `docs/ultimateui/stats_display.yml` |
| `held_item`, `held_slot` | gehaltenes Lobby-Item (GATEWAY, PROFILE, SOCIAL, COOKIE, COSMETICS, SETTINGS, VISIBILITY, NONE) / Hotbar-Slot 0-8 – für kontextabhängige Scoreboards |
| `kills`, `deaths` | Vanilla-Statistik dieses Servers |
| `playtime`, `playtime_hours`, `playtime_minutes`, `session_playtime` | Spielzeit (Vanilla-Statistik) formatiert / Stunden / Minuten, aktuelle Session |
| `cookies`, `cps`, `prestige` | Kurzformen von `cookie_balance`, `cookie_cps`, `cookie_prestige` |
| `cookie_in_zone`, `cookie_producing` | true/false – in der Cookie-Zone / Generatoren aktiv |
| `cookie_balance`, `cookie_balance_raw`, `cookie_cps`, `cookie_prestige`, `cookie_prestige_title`, `cookie_lifetime`, `cookie_crumbs`, `cookie_combo`, `cookie_buff`, `cookie_generators` | Cookie-Clicker-Werte (formatiert / roh) |

Legacy-Aliase aus 1.0.0 (`lobby_party`, `lobby_clan`, `lobby_visibility`, `lobby_cookie_*`) funktionieren weiter.

### UltimateUI – Beispiel-Scoreboard

```yaml
# plugins/UltimateUI/scoreboards/lobby.yml (Struktur je nach UltimateUI-Version anpassen)
title: "&6&lTasticGames"
lines:
  - "&7Rang: &f%tastic_rank_display%"
  - "&7Sprache: &f%tastic_language%"
  - ""
  - "&7Freunde online: &f%tastic_friends_online%"
  - "&7Party: &f%tastic_party_size%"
  - "&7Clan: &f%tastic_clan%"
  - ""
  - "&6Cookies: &f%tastic_cookies% &7(&f%tastic_cps% CPS&7)"
  - "&dPrestige %tastic_prestige%"
  - ""
  - "&7Kills: &f%tastic_kills% &7Tode: &f%tastic_deaths%"
  - "&7Spielzeit: &f%tastic_playtime%"
  - ""
  - "&etasticgames.de"
conditions:
  world: spawn
```

### TAB – Beispiel

```yaml
# plugins/TAB/config.yml (Auszug) – %tastic_…% ist als natives TAB-Placeholder registriert
tablist-name-formatting:
  enabled: true
  tabprefix: "%tastic_rank_prefix% "
  tabsuffix: " &7[&d%tastic_cookie_prestige%&7]"
header-footer:
  header: "&6&lTasticGames &7| &f%tastic_online% online"
  footer: "&7Cookies: &6%tastic_cookie_balance%"
```

Der Refresh-Intervall der TAB-Placeholders steht in `lobby.yml` (`placeholders.refresh-millis`, Standard 1000).

## LuckPerms

`RankProvider` (LuckPerms) liefert `rank(Player)` aus den gecachten Metadaten (main-thread-sicher). Ohne LuckPerms
wird der Rang aus `group.<name>`-Permissions abgeleitet. Grants (`grantPermission`) sind idempotent und werden nur
mit LuckPerms ausgeführt – z. B. um HMCCosmetics-Rechte für freigeschaltete Cosmetics zu setzen.

## HMCCosmetics

In `config/cosmetics.yml` verweist ein Eintrag mit `render: "hmc:<hmccosmetics-cosmetic-id>"` auf ein
HMCCosmetics-Cosmetic (Slot/Modell kommen aus der HMCCosmetics-Konfiguration). Beim Ausrüsten über den
TasticGames-Cosmetics-Dialog (Besitz = TasticGames-API) ruft die Lobby `HMCCosmeticsAPI.equipCosmetic(...)`,
beim Ablegen `unequipCosmetic(slot)`. Alle anderen Einträge rendert weiterhin der native Renderer.
`/tasticlobby status` meldet Einträge, deren HMCCosmetics-ID nicht existiert.

## MythicMobs + ModelEngine (Main-Cookie)

`config/cookie-clicker.yml`:

```yaml
main-cookie:
  world: "spawn"
  location: { x: -62.5, y: 35.0, z: 12.5 }
  mythicmobs-type: "fv_cookie_clicker"   # 1. Wahl: MythicMobs-Mob (trägt das ModelEngine-Modell)
  click-skill: "fv_cookie_clicker_hit"   # MythicMobs-Skill pro Klick (Hit-Animation), Damage blockt die Lobby
  model: "fv_cookie_clicker"             # 2. Wahl: ModelEngine-Modell auf unsichtbarer Basis-Entity
  hitbox: { width: 2.2, height: 2.4 }
```

Reihenfolge: MythicMobs → ModelEngine → nativer Item-Display. Klicks werden über die Interaction-Hitbox,
die Basis-Entity und `BaseEntityInteractEvent` erkannt (**Linksklick = backen, Rechtsklick = Menü**).
Die Basis-Entity des MythicMobs-Mobs (z. B. das Schwein) wird per ModelEngine-API + Invisible ausgeblendet –
sauberer ist zusätzlich `Options: Invisible: true` im Mob. Manuell gespawnte Mobs dieses Typs am Cookie-Standort
entfernt das Plugin beim Start (es besitzt den Main-Cookie). Der `~onDamaged`-Skill des Mobs feuert nicht, weil die
Lobby jeden Schaden blockt – stattdessen castet sie `click-skill` bei jedem Klick.
Der Chunk des Cookies wird per Plugin-Ticket geladen gehalten; verschwundene Entities werden alle 5 s neu gespawnt.
**Cookie-Zone** (`main-cookie.zone-radius`, 8 Blöcke): Generatoren produzieren nur, während der Spieler in der Zone
(oder in der Open-World) steht; außerhalb pausieren sie (Actionbar-Hinweis), Offline-Produktion ist standardmäßig aus.

## Citizens (NPCs)

```yaml
npcs:
  enabled: true
  list:
    mama_bakewell:
      quest: starter
      location: { x: -66.5, y: 35.0, z: 8.5, yaw: -45.0 }
      entity-type: PLAYER     # Spieler-NPC mit eigenem Skin (mitgeliefert: Bäckerin, Händler, Ritterin, König)
      skin-value: "…"         # Base64-Textur (MineSkin) – alternativ skin: "<Spielername>"
      skin-signature: "…"
      model: "mama_bakewell"  # optional ModelEngine (überdeckt den Skin)
      # citizens-id: 12       # vorhandenen /npc create-NPC verknüpfen (wird nie gelöscht)
```

`/cookieadmin npc <id> here` setzt die Position auf den Spielerstandort, `/cookieadmin npc <id> link` verknüpft den
mit `/npc select` gewählten Citizens-NPC. Eigene NPCs liegen im In-Memory-Registry `tasticlobby` (nicht in
Citizens' saves.yml).

## ItemsAdder

`config/items.yml`: `itemsadder: "tasticgames:gateway_compass"` pro Item; solange die Daten nicht geladen sind
(oder das Item fehlt), wird das Vanilla-Material verwendet. Nach `ItemsAdderLoadDataEvent` (Start, `/iazip`) wird
die Hotbar aller Spieler neu vergeben.

## WorldEdit / FAWE

* `/tasticlobby region teleport add <id>` – Goldblock-Teleporter-Region aus der Auswahl
* `/tasticlobby region launchpad add <id> [vx vy vz]` – direktionales Slime-Pad
* `/cookieadmin golden add <id>` – Golden-Cookie-Bereich (Lobby oder Open-World, je nach aktueller Welt)
* `/cookieadmin zone <id> fromselection|entry|gate` – Open-World-Zone

Alle Befehle schreiben in `lobby.yml` / `cookie-clicker.yml` und laden das Layout sofort neu.
