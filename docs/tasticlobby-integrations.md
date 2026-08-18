# TasticLobby – Plugin-Integrationen

TasticLobby arbeitet über die offiziellen APIs mit den folgenden Plugins zusammen. Alle Integrationen sind
**Soft-Dependencies**: fehlt ein Plugin (oder schlägt der Hook fehl), läuft die Lobby mit dem nativen Fallback
weiter und `/tasticlobby status` zeigt pro Integration `hooked (Version)` / `not installed` / `hook failed`.

| Plugin | Zweck in TasticLobby | Fallback ohne Plugin |
|---|---|---|
| **TasticCore** (Pflicht) | Spieler, Settings, Sprache (de/en/hi), Onboarding, API-Zugangsdaten (`plugins/TasticCore/config/api.yml` wird automatisch mitbenutzt) | – |
| **LuckPerms** | Rang (Primary Group, Display-Name, Prefix/Suffix, Weight) für Profil & Placeholders; Permission-Node-Grants (Cosmetic-Unlocks → HMCCosmetics-Rechte) | `group.<name>`-Permissions |
| **(eigenes HUD)** | TasticLobby zeichnet ein kontextabhängiges Top-Screen-HUD über Bossbars – mit ItemsAdder-Icons/Boxen, siehe `docs/tasticlobby-hud.md`; Scoreboard/UltimateUI werden nicht benötigt | Unicode-Icons |
| **TAB** | Tablist/Nametags – dieselben Werte als native TAB-Placeholders `%tastic_…%` (ohne PlaceholderAPI) | PlaceholderAPI |
| **PlaceholderAPI** | Expansion `tastic` (siehe unten) | – |
| **MythicMobs** | Visual des Main-Cookies (Mob-Typ `fv_cookie_clicker` mit ModelEngine-Modell) | ModelEngine → nativer Item-Display |
| **ModelEngine** | Modell auf unsichtbarer Basis-Entity (Main-Cookie ohne MythicMobs) und NPC-Modelle; Klicks auf die Modell-Hitbox (`BaseEntityInteractEvent`); Bindung erst nach `ModelRegistrationEvent FINISHED` | nativer Item-Display |
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
| `ctx`, `ctx_title`, `ctx_label_1..4`, `ctx_value_1..4`, `ctx_hint`, `ctx_objective` | kontextabhängige HUD-Zeilen (Kontext = gehaltenes Item: LOBBY, COOKIE, SOCIAL, GATEWAY, PROFILE, COSMETICS, SETTINGS, VISIBILITY), lokalisiert – dieselben Daten wie das native Top-HUD (`docs/tasticlobby-hud.md`) |
| `held_item`, `held_slot` | gehaltenes Lobby-Item (GATEWAY, PROFILE, SOCIAL, COOKIE, COSMETICS, SETTINGS, VISIBILITY, NONE) / Hotbar-Slot 0-8 – für kontextabhängige Scoreboards |
| `kills`, `deaths` | Vanilla-Statistik dieses Servers |
| `playtime`, `playtime_hours`, `playtime_minutes`, `session_playtime` | Spielzeit (Vanilla-Statistik) formatiert / Stunden / Minuten, aktuelle Session |
| `cookies`, `cps`, `prestige` | Kurzformen von `cookie_balance`, `cookie_cps`, `cookie_prestige` |
| `cookie_in_zone`, `cookie_producing` | true/false – in der Cookie-Zone / Generatoren aktiv |
| `cookie_balance`, `cookie_balance_raw`, `cookie_cps`, `cookie_prestige`, `cookie_prestige_title`, `cookie_lifetime`, `cookie_crumbs`, `cookie_combo`, `cookie_buff`, `cookie_generators` | Cookie-Clicker-Werte (formatiert / roh) |

Legacy-Aliase aus 1.0.0 (`lobby_party`, `lobby_clan`, `lobby_visibility`, `lobby_cookie_*`) funktionieren weiter.

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

**Load-Order / ModelEngine-Readiness:** ModelEngine importiert und registriert seine Modelle erst *nach* dem Aktivieren
der Plugins (Import → Assets → Pack-Zip; `softdepend` garantiert nur die Plugin-Reihenfolge, nicht diese Runtime-Readiness).
TasticLobby wartet deshalb auf `ModelRegistrationEvent` mit Phase `FINISHED` (`ModelEngineModelProvider.modelsReady()`),
bevor es den MythicMobs-Mob spawnt bzw. ein Modell anhängt (Fallback: nach 90 s ohne Event wird trotzdem gespawnt).
Vor jedem Attach wird `ModelEngineAPI.getBlueprint(id)` geprüft; das Ergebnis wird explizit geloggt:
`Main cookie ModelEngine binding: OK (…)` bzw. `FAILED (…) – <Grund>` und `Cookie NPC models: x/y attached (…)` mit
aggregierter Fehlerliste (kein „further failures are silent“ mehr). Nach `/meg reload` (erneutes `FINISHED`) werden
NPC-Modelle idempotent neu gebunden und der Main-Cookie neu gespawnt, falls sein Modell verloren ging.
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
      entity-type: PLAYER     # Spieler-NPC mit eigenem Skin (mitgeliefert: Bäckerin mama_bakewell, Händler gustave)
      skin-value: "…"         # Base64-Textur (MineSkin) – alternativ skin: "<Spielername>"
      skin-signature: "…"
      model: "mama_bakewell"  # optional ModelEngine (überdeckt den Skin; gebunden sobald ModelEngine fertig registriert hat)
      # citizens-id: 12       # vorhandenen /npc create-NPC verknüpfen (wird nie gelöscht)
```

Seit `config-version 3` gibt es nur noch die Bäckerin und den Händler; bestehende Dateien werden beim Start migriert
(`npcs.list.babette` und `npcs.list.king_frosting` entfernt, Version hochgesetzt, Datei gespeichert – geloggt als
`config/cookie-clicker.yml migrated: …`). Fremde/eigene NPC-Einträge bleiben erhalten.

`/cookieadmin npc <id> here` setzt die Position auf den Spielerstandort, `/cookieadmin npc <id> link` verknüpft den
mit `/npc select` gewählten Citizens-NPC. Eigene NPCs liegen im In-Memory-Registry `tasticlobby` (nicht in
Citizens' saves.yml).

### Service-NPCs (`config/npcs.yml`)

Unabhängig von den Cookie-Quest-NPCs stehen fünf Service-NPCs in der Lobby: `pass` (TasticPass-Übersicht),
`creative`/`survival`/`duels` (Transfer über TasticProxy) und `games` (Cookie-Menü). Linke **und** rechte Maustaste
lösen dieselbe Aktion aus (kurzer Cooldown pro Spieler).

```yaml
enabled: true
look-close: true
npcs:
  pass:
    action: PASS            # PASS | TRANSFER | COOKIE | NONE
    world: "spawn"          # optional, sonst die Lobby-Welt
    x: -72.5
    y: 36.0
    z: 11.5
    yaw: 180.0              # 0 = Süd, 90 = West, 180 = Nord, -90 = Ost
    skin:
      value: "…"            # Base64-Textur; alternativ skin-name: "<Spielername>"
      signature: "…"
  survival:
    action: TRANSFER
    target: SURVIVAL        # SURVIVAL | CREATIVE | DUELS
    x: -76.5
    y: 36.0
    z: -3.5
    yaw: -90.0
    mirror-skin: true       # Citizens MirrorTrait: jeder Spieler sieht seinen eigenen Skin
```

Die Namensschilder kommen aus den Nachrichten `lobby.npc.<id>.name` (Citizens-Namen sind global – oben am NPC steht
der englische Text). Ist das Ziel offline oder das Netzwerk in Wartung, erscheint dieselbe lokalisierte Meldung wie
im Gateway-Dialog und es wird kein Transfer gestartet. Der `pass`-NPC beachtet zusätzlich `npc: false` aus
`config/pass.yml` (der eigene Schalter des Pass-Moduls). `/tasticlobby reload` liest `npcs.yml` neu und spawnt die
NPCs neu; `/tasticlobby status` zeigt sie unter `Service NPCs` neben den Cookie-NPCs. Ohne Citizens fällt die Lobby
auf native Entities zurück – die NPCs erscheinen dann als Villager ohne Skin.

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

## Cosmetic-Asset-Packs (automatische Installation)

Gekaufte Cosmetic-Packs werden **nicht** ins Plugin gebaut, sondern vom Betreiber in
`plugins/TasticLobby/cosmetic-packs/` abgelegt (ZIP, wie heruntergeladen). Beim Serverstart installiert
`CosmeticPackInstaller` daraus:

| Inhalt im Archiv | Ziel |
|---|---|
| `**/ItemsAdder[ Setup|Configs]/contents/<pack>/**` (auch die alte Schreibweise `resource_pack`) | `plugins/ItemsAdder/contents/<pack>/**` |
| `**/HMCCosmetics/cosmetics/*.yml` | `plugins/HMCCosmetics/cosmetics/` |
| `**/HMCCosmetics/menus/*.yml` | `plugins/HMCCosmetics/menus/` – **nur wenn die Datei fehlt** |
| `**/ModelEngine/blueprints/*.bbmodel` | `plugins/ModelEngine/blueprints/` |

Varianten für andere Plugins (Oraxen, Nexo, MagicCosmetics, CosmeticsCore, MCPets) und Setups für ältere
Minecraft-Generationen werden ignoriert; von mehreren Kandidaten gewinnt der innerste Treffer, damit
`HMCCosmetics 1.20.2 Setup or higher version/HMCCosmetics/cosmetics/x.yml` korrekt landet.

Eigenschaften: idempotent (SHA-256 je Archiv in `.installed.properties`; erneute Installation nur bei geänderter
Datei oder fehlenden Zieldateien), Zip-Slip-sicher, Menüs werden nie überschrieben, Fehler brechen den Start nie ab.
Nach einer Installation wird das ItemsAdder-Pack neu gebaut (`/iazip`, gemeinsam mit dem HUD-Export) und
anschließend `hmccosmetics reload` ausgeführt. Passwortgeschützte oder verschachtelte Archive (z. B. ein ZIP im ZIP)
müssen einmal von Hand entpackt werden; `.rar`/`.7z` bitte als ZIP neu packen.

Sichtbar werden die Cosmetics erst über `config/cosmetics.yml` – dort steht pro Eintrag
`render: "hmc:<hmccosmetics-id>"`. Die mitgelieferte Datei enthält bereits die 52 Cosmetics aus den vier
HMC-fähigen Packs (Samus Vol. 1, Cosmetics Expansion v1, Expansion Vol. 2 „Legends", Necros Set) – 18 im
Free-Track, 34 exklusiv im Premium-Pass.

## Soundtrack (ItemsAdder)
`MusicAssetInstaller` turns the TasticGames OST into ItemsAdder content on every server start:

1. drop the licensed `.ogg` files (Ogg Vorbis; 48 kHz stereo is fine) into `plugins/TasticLobby/ost/`,
2. the installer copies them to
   `plugins/ItemsAdder/contents/tasticgames/resourcepack/assets/tasticgames/sounds/ost/<id>.ogg`,
3. writes `assets/tasticgames/sounds.json` (`"category": "music"`, `"stream": true`) so every file becomes
   `tasticgames:ost.<id>`, and `assets/minecraft/sounds.json`, which replaces the 31 vanilla music events with nothing
   (`music.yml` -> `ost.silence-vanilla`) - vanilla and custom music can never play at the same time,
4. reads every track length from the Ogg header itself (`OggInfo`), so no durations have to be typed anywhere,
5. requests the `/iazip` rebuild through the same pending-pack marker the HUD and the cosmetic packs use.

The file name is the id and the title: `ES_Bohemian-Bed-Franz-Gordon.ogg` becomes `bohemian_bed_franz_gordon` /
"Bohemian Bed Franz Gordon". Players switch the music off with the existing `music.enabled` setting; with the
soundtrack off no music plays at all, because the vanilla tracks are replaced. The `.ogg` files are never bundled with
the plugin - they are the operator's licensed content.
