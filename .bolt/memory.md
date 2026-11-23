# Project memory

## Product intent & audience

- Mobile-first app named **Magic** — a roulette/spin tracking tool.
- Core features: enter a spin number (0–36), record **Pettan number**, set **bet multiplier** ("KITNA GUNA", e.g. 1x/2x), track HIT/MISS counts, total units, and last 5 spins.
- **Pin mode**: collapses the full app into a small, draggable floating widget containing the essential controls (Enter Spin, Pettan, multiplier) plus summary stats; unpin restores the full screen.
- Spin history persisted (480 seed spins saved to database/Supabase) so data survives across sessions.
- Primary user communicates in Hindi/Hinglish; UI labels may mix English and Hindi (e.g. "KITNA GUNA").

## Brand personality & voice

- App identity: **Magic** — golden, premium, casino/roulette aesthetic.
- Response language: assistant replies in Hindi/Hinglish to match the user.

## Visual design & UX direction

- Golden MAGIC branding in the header with an animated roulette-wheel visual (CSS/SVG) used as the hero/branding image because the Pexels photo service was unavailable.
- Premium, non-cookie-cutter styling; production-worthy polish.
- Pin/expand toggle in header; pinned state = compact floating card, draggable anywhere on screen.
- Pinned card still shows last spin, HIT/MISS counts, total unit, and last 5 spins.
- Stack: Vite + React, JSX, Tailwind CSS, Lucide React icons; `@/` path alias for `src/` imports.

## Decisions, constraints & rationale

- Replaced intended Pexels photo with animated CSS/SVG roulette wheel due to Pexels downtime.
- Spin history moved to database (Supabase) for cross-session persistence; 480 seed spins inserted.
- Undo and Reset functionality retained from prior implementation.
- No extra UI/icon packages installed beyond the template defaults (Tailwind, Lucide React).

## Open questions & assumptions

- "Pettan number" meaning/semantics inferred from context but not formally defined by user.
- Photo integration via Pexels deferred until service is available; unclear if user wants a real photo later or prefers the animated wheel.
- Multiplier options currently 1x/2x; full desired range and semantics of bet multiplier not specified.