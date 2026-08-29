# Customer Activity Analytics — frontend

Customer-care console for reviewing customer activity (card / payment / crypto) and running
auditable AI risk analyses. React 19 + TypeScript + Vite 8 + Tailwind CSS 4 + React Router 7 +
TanStack Query 5.

The REST contract this app consumes is defined in [`../docs/BUILD_SPEC.md`](../docs/BUILD_SPEC.md)
section 5. Every screen reads from `src/api` — there is no mock or hard-coded data anywhere.

## Running it

```bash
npm install
npm run dev          # http://localhost:5173, proxies /api -> http://localhost:8080
```

| script | what it does |
|---|---|
| `npm run dev` | Vite dev server with the `/api` proxy (SSE passes through unbuffered) |
| `npm run build` | `tsc -b` then `vite build` into `dist/` |
| `npm run preview` | serves the production build |
| `npm test` | Vitest run, jsdom + Testing Library |
| `npm run test:watch` | Vitest in watch mode |
| `npm run lint` | oxlint |

The backend must be running on `:8080`. Seeded demo accounts (`admin/admin123`,
`operator1/operator123`) are listed on the sign-in screen.

## Structure

```
src/
  api/          axios client (JWT interceptor, 401 -> logout), typed endpoint modules,
                wire types + normalisers (Spring Page, ReAct trace, per-type activity detail)
  auth/         AuthContext, useAuth, ProtectedRoute, RoleGate, session storage
  components/   ui primitives (Button, Table, Badge, RiskBadge, Modal, Toast, ...),
                AppShell + Nav
  lib/          formatting, risk colour scale, rule-DSL helpers, theme
  pages/        one directory per feature area; page components are named exports
```

### Routes

| path | screen | access |
|---|---|---|
| `/login` | sign in | public |
| `/dashboard` | customer search, recent analyses, watchlist | signed in |
| `/customers/:customerId` | profile, aggregates, activity tabs, run analysis | signed in |
| `/customers/:customerId/analyses` | that customer's analysis history | signed in |
| `/analyses` | analysis history across customers | signed in |
| `/analyses/:assessmentId` | live ReAct trace, verdict, rule coverage | signed in |
| `/knowledge-search` | RAG search over the policy corpus | signed in |
| `/admin/rules` | visual rule editor for the threshold DSL | ADMIN |
| `/admin/knowledge` | upload / manage policy documents | ADMIN |
| `/admin/users` | read-only user directory | ADMIN |

Admin routes are wrapped in `RoleGate` and the admin nav section is hidden for operators.
Role checks are enforced server-side as well; the client gate is a courtesy, not a control.

## Design notes

* **Theming is token-driven.** `src/index.css` declares every colour as a CSS variable on
  `:root` / `.dark` and maps them into Tailwind through `@theme inline`. No component contains a
  raw palette class or a `dark:` variant, so light and dark stay in step by construction.
* **Risk colour has one meaning.** `LOW` emerald, `MEDIUM` amber, `HIGH` orange, `CRITICAL` rose,
  taken from `docs/DESIGN_SYSTEM.md`. They are only ever emitted by `lib/risk.ts` and rendered by
  `<RiskBadge />`, always as a filled pill carrying an icon **and** the level text, so colour is
  never the sole signal. Red and green are never used decoratively.
* **Numbers are tabular** (`font-variant-numeric: tabular-nums`), measures (money, counts, scores,
  durations, sizes) are right-aligned, dates and identifiers stay left-aligned, and money is only
  labelled with a currency code when the figure is genuinely single-currency.
* **Brand chrome, per `docs/DESIGN_SYSTEM.md`.** Swissquote-black sidebar (`#323232`) carrying the
  wordmark, brand-orange `#fa5b35` for CTAs, focus rings and the active-nav rail, white content
  surfaces on a grey-100 page, hairline borders and the restrained radius scale
  (`xxs 2 · xs 4 · md 8 · xl 12 · 2xl 16`). Uppercase micro-labels share one tracking step
  (`tracking-caption`).

### The wordmark is a placeholder

The licensed Swissquote logo asset is deliberately **not** bundled with this repository. The
sign-in screen and the sidebar render a text stand-in — "Swissquote" in the brand sans stack with
tight tracking, a brand-orange square mark beside it and "Customer Activity Analytics" as the
lighter secondary line (`Brand` in `src/components/layout/AppShell.tsx`, `Wordmark` in
`src/pages/LoginPage.tsx`). Swap in the real asset there; nothing else depends on it.

The type stack is progressive — `"GT America", "Inter", "Helvetica Neue", Helvetica, Arial` — so a
Swissquote workstation with the licensed face renders the true brand font and everything else
degrades cleanly.

### Documented contrast trade-off

White on the brand orange `#fa5b35` measures **3.2:1**, below the WCAG AA 4.5:1 required of 14px
text. It is kept for the primary CTA only — that pairing is the Swissquote brand button, and it
still clears the 3:1 required of the control itself. Darkening the fill until the label passes
lands between `#c94a2b` and `--sq-primary-700 #b24126`, which is visually the risk-HIGH fill
(`#c2410c`): the cure would break the design system's one hard rule, that brand chrome and risk
data are never confusable. Everywhere else the brand orange is constrained accordingly:

* brand orange as **text** on a surface always uses `--app-accent-strong` (`#b24126`, 5.7:1);
* smaller brand-marked controls (segmented controls, chips, active nav) use foreground-on-surface
  text with an orange rail instead of white-on-orange;
* every other text/background pair in the app was audited in both themes and clears AA.

## Backend expectations

Behaviour that depends on the backend beyond the plain contract:

1. `GET /api/analyses/{id}/stream` must accept the JWT as `?token=<jwt>` — `EventSource` cannot
   send an `Authorization` header. If the stream is unavailable the page degrades to polling every
   4s and says so.
2. `ruleEvaluations[]` must contain one row per rule in the coverage set, including non-triggered
   ones with `scoreContribution: 0.00`; the coverage table's completeness claim is computed from it.
3. `GET /api/customers/{id}/activity` must accept ISO-8601 instants for `from`/`to`
   (`2026-08-01T00:00:00.000Z`), and `size` up to 250 for the 30-day timeline.
4. There is no cross-customer analyses endpoint in the contract, so `/analyses` and the dashboard
   panel fan out over `GET /api/customers/{id}/analyses` for a bounded number of customers
   (`ANALYSES_FANOUT_CUSTOMER_LIMIT` in `src/api/analyses.ts`). A `GET /api/analyses` endpoint
   would collapse that to a single call.
5. `POST /api/rules` / `PUT /api/rules/{id}` send `thresholdLogic` as a JSON **object**; reads
   tolerate a JSON string because the column is `TEXT`.
6. Knowledge `KnowledgeChunk.score` is treated as similarity (higher is better). A pgvector
   *distance* must be converted server-side or the similarity meter reads inverted.
7. Uploads are rejected client-side above 20 MiB, which is the server's own
   `caa.rag.max-upload-bytes` (20971520). `spring.servlet.multipart.max-file-size` is
   deliberately larger (25 MB) so an oversized file is refused by the application with a
   `problem+json` body rather than by the servlet container.

## Placeholder assets

`public/favicon.svg` is a hand-drawn shield mark on the brand orange, **not** a licensed brand
asset. If this ships inside Swissquote it should be replaced with the real logo, along with the
sidebar/sign-in wordmark described above.
