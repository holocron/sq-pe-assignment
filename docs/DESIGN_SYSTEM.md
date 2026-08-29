# Swissquote Corporate Design System

This is an **internal Swissquote tool**. The UI must read as Swissquote, not as a generic dashboard.
The tokens below were extracted from Swissquote's live production stylesheets (their `--cws--*`
design tokens), so they are the real brand values, not an approximation.

## Brand colours

| Token | Value | Use |
|---|---|---|
| `--sq-primary-50`  | `#ffebeb` | tint backgrounds |
| `--sq-primary-100` | `#fdccc0` | |
| `--sq-primary-200` | `#fdb4a2` | |
| `--sq-primary-300` | `#fc9178` | |
| `--sq-primary-400` | `#fb7c5d` | hover |
| **`--sq-primary-500`** | **`#fa5b35`** | **the Swissquote brand orange — primary CTAs, active nav, focus accent** |
| `--sq-primary-600` | `#e45330` | pressed |
| `--sq-primary-700` | `#b24126` | |
| `--sq-primary-800` | `#8a321d` | |
| `--sq-primary-900` | `#692616` | |
| **`--sq-black`** | **`#323232`** | Swissquote black — header/sidebar chrome, headings |
| `--sq-white` | `#ffffff` | |

Neutral scale (Swissquote grey):
`50 #fafafa · 100 #f9fafb · 200 #f3f4f6 · 300 #e5e7eb · 400 #98a2b3 · 500 #475467 · 600 #4b5563 · 700 #374151 · 800 #1f2937 · 900 #111827`

Secondary blue (used sparingly for informational, non-risk emphasis):
`500 #3b82f6 · 600 #2563eb · 700 #1d4ed8 · 900 #1e3a8a`

## Typography

Swissquote uses **GT America** (sans), **GT Sectra** (serif) and **SwissquoteCT** (display).
These are licensed and are NOT bundled here. Use a progressive stack so a real Swissquote
workstation renders the true brand font and everything else degrades cleanly:

```css
--sq-font-sans: "GT America", "Inter", "Helvetica Neue", Helvetica, Arial, sans-serif;
--sq-font-mono: "GT America Mono", ui-monospace, "SF Mono", Menlo, monospace;
```

Swiss typographic character: tight, confident headings; generous line-height in body copy;
**tabular figures for every number** (`font-variant-numeric: tabular-nums`).

## Shape and density

Restrained radii (Swissquote's scale is small): `xxs 2px · xs 4px · md 8px · xl 12px · 2xl 16px`.
Cards use `md`. Buttons use `xs`–`md`. Nothing is pill-shaped except badges.
Layout is dense and information-first — this is a working tool for operators, not a marketing page.
Prefer hairline borders (`grey-300`) over heavy shadows.

## The one hard rule: brand colour vs risk colour

The brand orange `#fa5b35` sits in the same hue family as "danger". To stop the UI lying to the
operator, colour is split by ROLE and the two never mix:

* **Chrome / interactive controls** — buttons, links, active nav, focus rings — use the brand orange
  and Swissquote black. Controls are NEVER coloured by risk.
* **Data / risk signalling** — always rendered as a filled **badge or status pill**, never as a
  button or a link:

| Risk level | Colour | Text |
|---|---|---|
| LOW | `#047857` emerald-700 | white |
| MEDIUM | `#b45309` amber-700 | white |
| HIGH | `#c2410c` orange-700 | white |
| CRITICAL | `#9f1239` rose-800 | white |

* Risk colour **always** ships with its text label and an icon, so colour is never the sole signal
  (accessibility, and it keeps HIGH distinguishable from the brand orange at a glance).
* Never use red or green decoratively anywhere else in the app.

## Chrome layout

* Left sidebar in Swissquote black (`#323232`) with the wordmark, white/grey nav labels, and the
  active item marked by a brand-orange left rail plus a lighter background.
* Slim top bar on a white surface: page title/breadcrumb on the left; user name, role badge,
  theme toggle and logout on the right.
* Page background `grey-100`, content surfaces white, hairline borders.
* Dark mode: invert surfaces to the grey-800/900 range, keep the brand orange as accent, and keep
  the risk badge colours legible (lighten them slightly rather than changing hue).

## Wordmark

Do not fabricate or embed the real Swissquote logo asset. Render a clean text wordmark —
"Swissquote" in the sans stack, tight tracking — with a small brand-orange mark beside it, and set
the product name ("Customer Activity Analytics") as a lighter secondary line. Keep it obviously a
placeholder for the real asset and say so in the README.
