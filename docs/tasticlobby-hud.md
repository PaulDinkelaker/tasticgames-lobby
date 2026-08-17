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
  `configs/tasticgames_hud.yml` (Font-Images `tasticgames:hud_box_left|mid|right`, 14 px hoch),
  `resourcepack/assets/tasticgames/textures/hud/box_*.png` und **transparente Bossbar-Texturen**
  (`resourcepack/assets/minecraft/textures/gui/sprites/boss_bar/pink_*.png`, Farbe = `itemsadder.bossbar-color`).
  Danach einmal **`/iazip`** ausführen und neu verbinden. Bestehende Dateien werden nie überschrieben.
  Solange die Glyphen fehlen, rendert das HUD ohne Boxen (`/tasticlobby status` → „HUD: … boxes missing – run /iazip“).
* Layout: Boxbreite = Textbreite (Default-Font-Tabelle) + 2 × `box.padding`; Zellenabstand `box.gap`; Offsets über
  ItemsAdder (`FontImageWrapper.applyPixelsOffsetToString`).
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
  bossbar-color: PINK
  box: { left: "tasticgames:hud_box_left", middle: "tasticgames:hud_box_mid", right: "tasticgames:hud_box_right", padding: 6, gap: 8 }
  icons-map: { cookies: "toxlyusefuliconsvol4:coin1", rank: "cuboide:iconic_crown", ... }
colors: { title: "#ffd82b", label: "#aaaaaa", values: ["#24ff2b", "#c4e4ff", "#f94fff", "#f0d030"], hint: "#bbbbbb", objective: "#dbb039", status: "#ffffff" }
```
`/tasticlobby reload` liest hud.yml neu und exportiert fehlende Assets erneut.

## Placeholders
Dieselben Inhalte stehen weiterhin als `%tastic_ctx_*%` (PlaceholderAPI/TAB) bereit – z. B. für TAB-Header oder andere
Anzeigen; ein Scoreboard/UltimateUI wird nicht mehr benötigt.
