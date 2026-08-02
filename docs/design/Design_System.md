# blackbox — Developer Design System

> Reference for implementation. Derived from the Brand Book (final, unmodified) and Design System v1.0. This document translates philosophy into rules — no new content, no marketing copy.

---

## 0. Philosophy (implementation contract)

Every design/code decision resolves against one question:

> **"이 결정은 시간이 쌓이는 느낌을 주는가?" — Does this decision feel like time accumulating?**

Derived constraints (enforce in code review):

- `MUST NOT` compare user to other users. Only valid comparison axis: `self.past vs self.present`.
- `MUST NOT` use urgency patterns: badges-that-demand-action, streak-loss warnings, red dots for re-engagement.
- `MUST` treat every "memory unit" (bookmark) as permanent — no silent delete/archive by the system. User-initiated delete only.
- `MUST` let old content visually recede (opacity/scale/type-size), never disappear from the DOM/data.
- AI copy `MUST NOT` assert conclusions. Use hedged, discovery-framed language (see §7 Voice Guideline).

---

## 1. Design Tokens — Color

```css
--color-dusk:       #0F0E0B; /* primary dark surface / app background */
--color-sediment:   #3C3930; /* border/divider on dusk */
--color-fossil:     #8A7F6A; /* muted text, labels, icon default */
--color-thread:     #D3C9B0; /* headline text on dusk */
--color-parchment:  #F5F2EA; /* light surface / app background (light mode) */
--color-signal:     #C17F3E; /* accent — insight + reconnection moments ONLY */
--color-border-light: #E8E4D9; /* 0.5px borders on parchment */
--color-error:      #B5543E; /* form error only, not decorative */
```

Rules:
- 6 core colors total. No new hues without a design-system PR.
- `--color-signal` is reserved for: (a) AI-generated insight cards, (b) "reconnection" states (old↔now), (c) primary CTA fill, (d) focus ring. Not for generic emphasis.
- Dark surface (`dusk`) and light surface (`parchment`) are both first-class — implement both, tokens above already split by surface.

## 2. Design Tokens — Spacing

```css
--space-1: 4px;  --space-2: 8px;  --space-3: 12px; --space-4: 16px;
--space-5: 24px; --space-6: 32px; --space-7: 48px; --space-8: 64px; --space-9: 96px;
```
- Card internal padding default: `--space-6` (24px card padding — note: brand sheet uses 24px as "space-6" conceptually; keep 24px as the card default regardless of index labeling above).
- Section-to-section spacing: `--space-9` (96px) minimum.
- Never hardcode a px value outside this scale.

## 3. Font Tokens

```css
--font-display: 'Fraunces', serif;     /* emotional / narrative copy only */
--font-ui:      'Pretendard', -apple-system, sans-serif; /* everything else */

--text-display-lg: 40px/1.3  var(--font-display) 500;
--text-display-md: 26px/1.4  var(--font-display) 400 italic;
--text-heading:     18px/1.4  var(--font-ui) 600;
--text-body:        15px/1.6  var(--font-ui) 400;
--text-body-sm:      13px/1.6  var(--font-ui) 400;
--text-label:        11px/1.4  var(--font-ui) 500;  /* letter-spacing: .06em */
--text-micro:         9.5px/1.3 var(--font-ui) 500; /* letter-spacing: .1em */
```

Rules:
- `--font-display` (Fraunces) permitted ONLY on: brand story/philosophy copy, insight card sentence, tagline, empty-state message. Never on UI chrome, buttons, labels, nav.
- Minimum body size 15px, no exceptions (incl. mobile).
- Emphasis = color (`--color-signal`) or font-family switch (serif italic), never bold-only.

## 4. Grid & Layout

```
max-width: 1160px
columns:   12
gutter:    16px
margin:    80px desktop / 40px tablet / 20px mobile
reading-column-max: 640px  /* body text never exceeds this width */
```

Breakpoints:
```
desktop: ≥1024px  → 12 col, margin 80px, bookmark grid 3-col
tablet:  768–1023 → 8 col,  margin 40px, bookmark grid 2-col
mobile:  ≤767     → 4 col,  margin 20px, bookmark grid 1-col (full-width card)
```
Typography scales down exactly one step on mobile (`display-lg` 40→32px); never smaller. Compress spacing, not type.

## 5. Motion Tokens

```css
--duration-instant: 150ms;  /* button/input state change */
--duration-base:    300ms;  /* card mount, tab switch */
--duration-slow:    500ms;  /* past-memory fade-in (older = use up to 600ms) */
--ease-settle: cubic-bezier(.2,.8,.2,1);
```

Rules:
- Entrance animation is always `opacity 0→1` + `translateY(8px→0)`. No scale-pop, no bounce, no spring.
- Older items animate in slower (interpolate duration 400–600ms by age) — this is a functional requirement, not decoration.
- `prefers-reduced-motion: reduce` → keep opacity fade, strip all translateY/scale.

## 6. Radius & Elevation

```css
--radius-sm: 2px;  --radius-md: 3px;  --radius-lg: 4px;  --radius-xl: 6px; /* modal only */
```
- No 5th radius value. No `border-radius: 9999px` (pill) outside avatars/dots.
- **No `box-shadow` anywhere**, except: modal gets a single `0.5px solid #3C3930` top highlight border. That is the only elevation cue in the system.
- Layering = background-value steps only:
```css
--surface-0: #0F0E0B; /* base */
--surface-1: #161410; /* card */
--surface-2: #1E1B14; /* modal */
```

## 7. Voice Guideline (implements "Conversation System")

Tone rules for all copy (AI-generated or static UI strings):

| Situation | Rule | Do | Don't |
|---|---|---|---|
| First run | No explanation of mechanics; state that it waits | "북마크를 가져왔어요. 시간이 쌓이면 보이기 시작할 거예요." | "AI가 분석을 시작합니다" |
| Insight found | Frame as discovery, never verdict | "3개월 전에도 비슷한 시기가 있었어요." | "당신은 반복적인 패턴을 보입니다" |
| AI wrong / user corrects | Acknowledge briefly, no apology loop | "제가 잘못 읽었네요. 더 잘 볼게요." | "죄송합니다, 다시 시도하겠습니다" |
| No insight yet | Silence is valid | "아직은 잘 모르겠어요." | Force a fabricated insight |
| Re-engagement / win-back | No guilt, no streak language | "그동안 47개가 쌓였어요." | "N일째 안 오셨네요!" |
| Milestones | Reflect, don't celebrate | "1,000번째 흔적이 쌓였어요. 첫 번째 흔적은 —" | Confetti / "축하합니다🎉" |

Global constraints:
- Never use exclamation points for engagement bait.
- Never show raw stats without a narrative sentence attached.
- Numbers are secondary to meaning — lead sentence with story, number embedded (`<em>`/signal color), not headline-sized.

## 8. Component Rules

### 8.1 Button
- 4 variants only: `primary` (signal fill, parchment/dusk text per surface), `secondary` (1px `#D8D2C2` outline), `ghost` (text only), `disabled` (fossil @ 20% opacity fill).
- Heights: `sm 36px / md 44px / lg 52px`. Radius `--radius-md`.
- Hover: brightness −6%. Active: brightness −10%. Transition `--duration-instant` `--ease-settle`. No shadow, no scale.

### 8.2 Input
- Underline style only (`border-bottom: 1px solid`), never boxed/filled.
- Default border `--color-sediment` (dusk) / `#D8D2C2` (parchment). Focus border `--color-signal`. Error border `--color-error`.
- Placeholder color: `--color-fossil`. Transition `200ms`.

### 8.3 Card (base)
- Background: `#fff` on parchment / `--surface-1` on dusk. Border `.5px solid --color-border-light`. Radius `--radius-lg`. Padding `--space-6`.
- No shadow. Hover state changes border color to `--color-signal` only — no lift/scale.

### 8.4 Insight Card
- Always dusk surface regardless of app-wide light/dark mode (insight cards are a fixed "night" moment).
- Structure: signal-colored dot + label (`emotion/context` string) → Fraunces italic sentence → fossil-colored footer caption.
- This is the ONLY card type allowed to use `--font-display` for its primary sentence.

### 8.5 Bookmark Card
Required fields: relative timestamp, tag, 1–2 line body.

Time-decay mapping (implement as a pure function of age):
```
opacity:  now 100% → 8mo 72% → 3y+ 45% (floor, never below 45%)
fontSize: 13px → 12.5px → 12px (never below 12px)
```
- Never soft-delete or collapse a card out of the DOM due to age — decay is visual only.
- Sort order: newest first (top), unless explicitly in a "look back" / memory-ritual view.

### 8.6 Icon System
- Line icons only, no filled icons. `stroke-width: 1.5px`, canvas `24px` (`20px` for dense UI), round line caps.
- Color: `--color-fossil` or inherit text color. `--color-signal` reserved for exactly one icon concept: "reconnection/discovery" (arc + dot).

### 8.7 Illustration Rule
- No illustrations, no stock icon packs, no decorative character art, no gradients-as-backgrounds.
- Visual interest comes only from real content (bookmark text, insight sentences) and data diagrams built from the brand's own vocabulary: thin lines, dots, arcs — matching the logomark grammar.

### 8.8 Empty State
- No illustration/emoji. Pattern: one `6px` dot (`--color-border-light` fill) + one Fraunces italic sentence, centered, muted (`--color-fossil`/`#9C9284`).
- Never use urgency copy ("Get started now"). State the wait plainly.

### 8.9 Loading State
- No spinners. Single dot pulses opacity `25%→100%→25%` over `600ms`, ease-in-out, infinite.
- No rotation, no bounce — the motion should read as "thinking," not "processing."

## 9. Accessibility Rules

- Text contrast ≥ 4.5:1. On dusk, `--color-fossil` (#8A7F6A) is the minimum-allowed text color — do not go dimmer for body text.
- Touch targets ≥ 44×44px (achieve via button padding, not icon size).
- Focus ring: `1px solid var(--color-signal)`, `outline-offset: 2px`, always visible (no `outline: none` without replacement).
- `prefers-reduced-motion: reduce` → strip translateY/scale, keep opacity fades only.
- Time/age information must be conveyed by text label AND color/opacity — never opacity alone.
- All interactive elements need meaningful `aria-label`; decorative icons get `aria-hidden="true"`.

---

## 10. Non-negotiables (quick checklist for PR review)

- [ ] No `box-shadow` except modal top-highlight.
- [ ] No color outside the 6-token palette.
- [ ] No comparison-to-others UI copy or metric.
- [ ] No streak/urgency/guilt re-engagement pattern.
- [ ] Fraunces used only where emotional weight is intended (never in chrome/nav/buttons).
- [ ] Every animated entrance is fade+rise, no bounce/scale/spring.
- [ ] Bookmark age always expressed as opacity decay, never deletion.
