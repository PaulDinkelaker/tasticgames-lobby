# TasticLobby – Top-Screen-HUD (nativ, ohne UltimateUI)

TasticLobby zeichnet das HUD selbst mit **Bossbars**: bis zu vier Zeilen oben zentriert – immer über dem Item in der
Hand (Bossbars sind GUI-Overlay, kein Welt-Rendering). Der Inhalt ist kontextabhängig (Item in der Hand):

| Kontext | Titel | Werte 1–4 | Ziel-Zeile |
|---|---|---|---|
| Lobby (nichts / sonstiges) | ☀ TasticGames | Rang · Spielzeit · Cookies · Online | Willkommen / Session |
| Cookie-Item | 🍪 Cookie Clicker | Cookies · CPS · Prestige · Generatoren | Fortschritt zum nächsten Prestige, Zone-Hinweis |
| Social | ☻ Social | Freunde online · Party · Clan · Anfragen | offene Anfragen / Party / Einladen |
| Gateway | ✈ Gateway | Survival · Modi · Lobby · Party | Survival-Status / Wartung |
| Profil | ★ Profil | Rang · Spielzeit · Kills · Tode | Prestige · Lifetime-Cookies |
| Cosmetics | ✎ Cosmetics | Hut · Aura · Trail · Titel | x/y freigeschaltet |
| Einstellungen | ⚙ Einstellungen | Sprache · Sichtbarkeit · Musik · Sounds | – |
| Sichtbarkeit | 👁 Sichtbarkeit | Modus · Freunde · Party · Online | – |

Zeilen: **Werte** (Titel + 4 Icon/Wert-Zellen), **Hinweis** (Steuerung/Befehle), **Ziel** (Fortschritt), **Status**
(Rang | Spielzeit | Online). Jede Zeile lässt sich in `config/hud.yml` abschalten; Spieler schalten das HUD in
`/settings → Lobby → Top-Screen HUD` (Setting `lobby.hud.enabled`).

## ItemsAdder-Styling
* **Icons**: `itemsadder.icons-map` ordnet semantische Keys (`cookies`, `rank`, `kills`, …) ItemsAdder-Font-Images zu
  (Standard: `cuboide:iconic_*`, `toxlyusefuliconsvol4:*`). Unbekannte IDs → Unicode-Icon (`unicode-icons`).
* **Boxen**: dunkle abgerundete Hintergründe hinter jeder Zelle. Dafür exportiert TasticLobby beim Start ein
  ItemsAdder-Content-Paket nach `plugins/ItemsAdder/contents/tasticgames/`:
  `configs/tasticgames_hud.yml` (Font-Images `tasticgames:hud_box_left|mid|right`, 16 px hoch), Texturen in `textures/hud/` (IA 3.x) und
  `resourcepack/assets/tasticgames/textures/hud/box_*.png` und **transparente Bossbar-Texturen**
  (`resourcepack/assets/minecraft/textures/gui/sprites/boss_bar/pink_*.png`, Farbe = `itemsadder.bossbar-color`).
  Zusätzlich wird die Offset-Font `assets/tasticgames/font/space.json` exportiert (vanilla `type: space`) – damit
  positioniert das HUD pixelgenau (ItemsAdders `applyPixelsOffsetToString` liefert für leere Strings nichts und
  hätte die Boxen neben statt hinter den Text gesetzt).
  Nach dem Export muss das Pack neu erzeugt werden – **`/iazip` läuft automatisch** (`itemsadder.auto-zip`), sobald
  ItemsAdder geladen ist; die Boxen und das Kopf-Modell schalten sich mit dem folgenden ItemsAdder-Reload-Event zu.
  Einmal neu verbinden für das aktualisierte Pack. Die Dateien sind versioniert (`.tasticlobby-assets`, aktuell Version 3):
  eigene Dateien werden bei einer neuen Asset-Version aktualisiert, fremde nie angefasst. Der Marker merkt sich außerdem,
  ob nach dem letzten Export bereits gezippt wurde (`3 zipped`) – ein Neustart dazwischen lässt das Pack also nicht veraltet
  zurück, `/iazip` wird beim nächsten Start nachgeholt. Solange die Dateien noch nicht im Pack sind, bleiben die Boxen
  bewusst aus (`/tasticlobby status` → „boxes missing“), damit nichts verschoben gerendert wird.
* **Profil-Item = eigener Skin**: das Profil-Item ist der Kopf des Spielers (`PLAYER_HEAD` mit seinem Profil). Der Export
  liefert dazu das Vanilla-Item-Modell `tasticgames:profile_head` (`items/profile_head.json` + `models/item/profile_head.json`,
  Special-Renderer `minecraft:head` kind `player`, GUI-Rotation 0/0/0, Skalierung 1.75, `gui_light: front`): der Kopf wird
  im Hotbar-Slot frontal und slotfüllend gezeigt – wirkt wie ein 2D-Icon, zeigt aber den echten Skin. Das Modell wird erst
  gesetzt, wenn das Pack die Dateien enthält (sonst Vanilla-3D-Kopf). Alternativ statisch: `itemsadder: tasticgames:profile_icon`
  (exportiertes 16×16-Icon `items/profile.png`). Gateway/Social/Cosmetics/Settings verwenden `cuboide:iconic_*`.
* Layout: Boxbreite = Textbreite (Default-Font-Tabelle) + 2 × `box.padding` (aufgerundet auf 8-px-Kacheln); Zellenabstand
  `box.gap`; der Text wird mittig in die Box gesetzt (Rück-Offsets aus der Space-Font). **Glyph-Advance**: ein über
  ItemsAdder gerendertes Font-Image bewegt den Cursor genau um die von ItemsAdder gemeldete Breite (`FontImageWrapper#getWidth`)
  plus `itemsadder.glyph-spacing` px (Standard 0 – gegen die exportierten Box-Glyphen mit bekannter Pixelbreite vermessen).
  Rendert der Client die Boxen schmaler als ihren Text (Text beginnt links vor der Box), `glyph-spacing: 1` setzen.
* Ohne ItemsAdder: reiner Text mit Unicode-Icons und `·`-Trennern; die Bossbar-Leiste bleibt sichtbar
  (`itemsadder.bossbar-color` bestimmt die Farbe).

## hud.yml (Auszug)
```yaml
enabled: true
refresh-ticks: 10
rows: { values: true, hint: true, objective: true, status: true }
itemsadder:
  icons: true
  boxes: true
  export-content: true
  auto-zip: true
  bossbar-color: PINK
  box: { left: "tasticgames:hud_box_left", middle: "tasticgames:hud_box_mid", right: "tasticgames:hud_box_right", padding: 6, gap: 6 }
  glyph-spacing: 0
  icons-map: { cookies: "toxlyusefuliconsvol4:coin1", rank: "cuboide:iconic_crown", ... }
colors: { title: "#ffd82b", label: "#aaaaaa", values: ["#24ff2b", "#c4e4ff", "#f94fff", "#f0d030"], hint: "#bbbbbb", objective: "#dbb039", status: "#ffffff" }
```
`/tasticlobby reload` liest hud.yml neu und exportiert fehlende Assets erneut.

## Placeholders
Dieselben Inhalte stehen weiterhin als `%tastic_ctx_*%` (PlaceholderAPI/TAB) bereit – z. B. für TAB-Header oder andere
Anzeigen; ein Scoreboard/UltimateUI wird nicht mehr benötigt.

## Script widths
Box sizes come from `FontWidths`. Only ASCII uses the vanilla bitmap widths (2-7 px); everything the client renders
with its bundled unifont advances a full cell: 9 px halfwidth (Devanagari, Cyrillic beyond the vanilla font) and 17 px
fullwidth (CJK, Hangul). The client does no Indic shaping, so matras and the virama are cells of their own - a Hindi
line needs roughly twice the pixels of the same text in Latin letters, and estimating it with the ASCII average is what
made Hindi HUD rows spill out of their boxes.

## Rows per player
`hud.yml` decides which rows exist at all (shipped: values, objective, status - the hint row is off, so the objective
row carries the hint when there is no objective). On top of that every player has `/settings` -> HUD: `HUD size`
(FULL = every configured row, COMPACT = no hints, MINIMAL = values only), `HUD status row` and `HUD hints`. The bar
count is rebuilt when the mode changes, so a MINIMAL player really gets one boss bar instead of an empty one.
