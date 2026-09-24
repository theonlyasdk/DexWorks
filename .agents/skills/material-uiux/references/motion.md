# Motion

Sources: M3 motion/transition guidance, Material motion tokens, M3 Expressive physics.

## 1. Duration and easing tokens (legacy system, still current for transitions)

Durations in milliseconds: short `50 / 100 / 150 / 200`; medium `250 / 300 / 350 / 400`; long `450 / 500 / 550 / 700`; extra-long `900 / 1000–1400`.

Easing curves (Android `PathInterpolator`, CSS `cubic-bezier` fallback in parentheses):
- Standard `0.2, 0, 0, 1` — begin and end on screen, 300ms.
- Standard-Decelerate `0, 0, 0, 1` — enter on screen, 250ms.
- Standard-Accelerate `0.3, 0, 1, 1` — exit screen, 200ms.
- Emphasized `0.05, 0 → 0.13, 0.06 → 0.17, 0.4 → 0.21, 0.82 → 0.25, 1 → 1, 1` — begin and end on screen, 500ms (CSS fallback: Standard).
- Emphasized-Decelerate `0.05, 0.7, 0.1, 1` — enter, 400ms.
- Emphasized-Accelerate `0.3, 0, 0.8, 0.15` — exit, 200ms.

Pairing rules: enter uses Decelerate, exit uses Accelerate; begin-and-end-on-screen uses Standard/Emphasized. Small areas animate short, large areas long (constant perceived speed). Exits are always shorter than entrances — exiting elements get less attention.

## 2. Transition patterns

Choose by the relationship between the two surfaces:
- Container transform: a persistent container (card, list row, FAB, chip, search bar) morphs size/position/shape into the next surface, content fading in sequence. Strongest perceived relation. Use for cards/lists/galleries → detail, FAB → sheet, chip → detail, search bar → search view, sheet expand.
- Forward/Backward: hierarchy descent/ascent (inbox → thread).
- Lateral: peer swipe between equals.
- Top-level: root destination switch (bottom nav tabs — subtle, fast).
- Enter/Exit: simple appear/disappear for small elements.
- Skeleton loaders: placeholder shimmer while content loads.
- Legacy M2 names you may see in code: shared-axis (x/y/z for spatial or navigational relation), fade-through (unrelated surfaces), fade (small elements).

Rule: if a persistent container exists on both sides, use container transform. Never animate a root switch with a slow expressive spring; never use a 500ms emphasized curve on a 24dp icon.

## 3. M3 Expressive physics

Expressive replaces fixed easing/duration with spring schemes, swappable at product level:
- `Expressive` scheme: bouncy overshoot for hero/key moments.
- `Standard` scheme: minimal bounce for utilitarian motion.

Token families `md.sys.motion.spring.{fast, default, slow}.{spatial, effects}`:
- Spatial (position, rotation, size, corners): overshoots. Expressive fast damping 0.6 / stiffness 800; default 0.8 / 380; slow 0.8 / 200. Standard: 0.9 / 1400, 0.9 / 700, 0.9 / 300.
- Effects (color, opacity): never overshoot. Expressive 1.0 / 3800, 1.0 / 1600, 1.0 / 800.
- Speed mapping: fast = small elements (switch/button color); default = partial-screen motion (bottom sheet, rail opacity) and all component motion; slow = fullscreen transitions.

Rule: spatial springs may overshoot; effects springs never do. Do not hand-tune spring constants per component — pick the scheme (Expressive vs Standard) once per product.
