# Images the site expects

Drop the files below into this directory. The pages already reference them, so a
missing file shows as a broken image and nothing else.

## Screenshots

Take these from a real debug build of the demo app — `./gradlew :app:assembleDebug`,
then open <http://localhost:8394> in a light-theme browser. Export at 2× for retina;
PNG. Every shot shows the whole board — navbar, top bar, filter bar and canvas.

| File | What it should show |
|---|---|
| `board-overview.png` | 1600×1000. The whole demo-app graph, every module in its own box, the navbar listing all five modules. Used on the landing page and in the board guide. |
| `board-modules.png` | 1440×900. Closer in on `:feature:orders:impl`, `:feature:profile:impl` and `:core:analytics`: each module its own labeled, tinted box, wires crossing between them. Used in the multi-module guide. |
| `board-node-panel.png` | 1600×1000. `DefaultFirstPresenter` selected: its wires turn blue, its neighbours stay lit, and the details panel shows provided from / injected into / depends on. Used in the board guide. |
| `board-decision-card.png` | 1440×900. A blocked build: the warning banner with "Bind LogcatAnalytics" / "Bind NetworkAnalytics", the red badge on Decisions, and the amber ring on `Analytics`. Used in the board guide. |

The default layout stacks modules vertically, too tall to read at one zoom. For
the overview and modules shots, drag the module containers side by side first:
`:app` and `:core:network` on top, the features and `:core:analytics` below. To get
the blocked build, comment out the `@Bind` in `:core:analytics`'s `GraphRules.kt`,
build, take the shot, then restore the line.

## Branding

All three are cut from the README hero, `docs/hero_logo.png`.

| File | Notes |
|---|---|
| `mascot.png` | The mascot. Used as the nav logo (renders ~24px) and the hero image (~320px). Transparent background; works on light and dark. |
| `favicon.png` | 48×48 — the kite's diamond and face only; the full mascot is a smudge at tab size. |
| `social.png` | 1200×630 Open Graph card — the README hero (wordmark, tagline, mascot) centred on its backdrop. Linked absolutely as `https://kitedi.com/img/social.png` in the site config. |
