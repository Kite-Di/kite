# Images the site expects

Drop the files below into this directory. The pages already reference them, so a
missing file shows as a broken image and nothing else.

## Screenshots

Take these from a real debug build of the demo app — `./gradlew :app:assembleDebug`,
then open <http://localhost:8394>. Export at 2× for retina; PNG.

| File | What it should show |
|---|---|
| `board-overview.png` | The whole demo-app graph on the canvas, zoomed so node labels are readable. Used on the landing page and in the board guide. |
| `board-modules.png` | The same graph with module containers on (`m`), so each module reads as its own labeled, tinted backdrop. Used in the multi-module guide. |
| `board-decision-card.png` | A blocked build's decision card, offering the `@Bind` between two implementations. Used in the board guide. |

## Branding

| File | Notes |
|---|---|
| `mascot.png` | The mascot. Used as the nav logo (renders ~24px) and the hero image (~320px). Transparent background; works on light and dark. |
| `favicon.png` | 32×32 or 48×48. |
| `social.png` | 1200×630 Open Graph card — mascot plus the tagline. Linked absolutely as `https://kitedi.com/img/social.png` in the site config. |
