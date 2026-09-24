# Foundations: Color, Typography, Shape, Elevation

Sources: M3 color/type/shape/elevation guidance, Material Color Utilities, Material Components Android.

## 1. Color system

Token layers flow one way: `ref` palette tones → `sys` roles → `comp` component mappings. In code you consume `sys` roles only.

- Five tonal palettes derive from the seed color: `primary, secondary, tertiary, neutral, neutralVariant`, plus static `error`. Each palette has tones `0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100` (0 = black, 100 = white, luminance-based via HCT: Hue / Chroma 0–120 / Tone).
- Full `ColorScheme` role set you must use exactly:
  - Accents (×4 each): `primary / onPrimary / primaryContainer / onPrimaryContainer`, same pattern for `secondary*` and `tertiary*`.
  - Fixed accents (identical tone in light and dark): `primaryFixed / primaryFixedDim / onPrimaryFixed / onPrimaryFixedVariant`, same for `secondaryFixed*`, `tertiaryFixed*`.
  - Error (static, does not shift with dynamic color): `error / onError / errorContainer / onErrorContainer`.
  - Surfaces: `surface / onSurface / onSurfaceVariant / surfaceVariant / surfaceTint / surfaceDim / surfaceBright`; containers `surfaceContainerLowest / Low / Container / High / Highest`; `background / onBackground`; `inverseSurface / inverseOnSurface / inversePrimary`; `outline / outlineVariant`; `scrim` (32% opacity).
- Role semantics (no exceptions):
  - `*Container` = fill behind actionable/foreground elements (buttons, FAB, chips, cards). Never put text directly in a `*Container` color; never use a container as a text color.
  - `on*` = text and icons drawn on the paired parent only (`onPrimary` on `primary`, `onPrimaryContainer` on `primaryContainer`).
  - `surface*` = backgrounds and large low-emphasis areas. Nest containers with ascending `surfaceContainer*` roles; do not fake nesting with elevation alone.
  - `outline / outlineVariant` = borders/dividers only.
- Baseline static scheme (fallback when dynamic color is unavailable; purple seed). Memorize the pairings, not just the hex:
  - Light: primary `#6750A4` (tone 40) / onPrimary `#FFFFFF` / primaryContainer `#EADDFF` (90) / onPrimaryContainer `#4F378B` (30). Secondary `#625B71`/`#FFFFFF`/`#E8DEF8`/`#4A4458`. Tertiary `#7D5260`/`#FFFFFF`/`#FFD8E4`/`#633B48`. Error `#B3261E`/`#FFFFFF`/`#F9DEDC`/`#410E0B`. Background/surface `#FEF7FF`, onBackground/onSurface `#1D1B20`. Surface containers: dim `#DED8E1`, lowest `#FFFFFF`, low `#F7F2FA`, container `#F3EDF7`, high `#ECE6F0`, highest `#E6E0E9`. Outline `#79747E`, outlineVariant `#CAC4D0`.
  - Dark: primary `#D0BCFF` (80) / onPrimary `#381E72` (20) / primaryContainer `#4F378B` (30) / onPrimaryContainer `#EADDFF` (90). Secondary `#CCC2DC`/`#332D41`/`#4A4458`/`#E8DEF8`. Tertiary `#EFB8C8`/`#492532`/`#633B48`/`#FFD8E4`. Error `#F2B8B5`/`#601410`/`#8C1D18`/`#F9DEDC`. Background/surface `#141218`, onBackground/onSurface `#E6E0E9`. Containers: dim `#141218`, lowest `#0F0D13`, low `#1D1B20`, container `#211F26`, high `#2B2930`, highest `#36343B`.
- Dynamic color (Material You, Android 12/API 31+): apply with `DynamicColors.applyToActivitiesIfAvailable()` in Application. Wallpaper/content yields a seed → 5 key colors → light + dark + contrast schemes. Variants: `TONAL_SPOT` (default), `VIBRANT`, `EXPRESSIVE`, `SPRITZ`, `RAINBOW`, `FRUIT_SALAD`. Contrast levels: 0.0 default, 0.5 medium, 1.0 high, -1.0 reduced.
- Rules: always ship a static fallback (baseline scheme); never hardcode brand hex into components — harmonize custom colors with `HarmonizedColors`; never use a saturated tone-500 accent on dark backgrounds (use the 80-tone roles); never use pure `#000000` app surfaces (reserve for OLED system surfaces).
- Contrast enforcement: container/onContainer pairings comply by construction. Button container vs its background must be ≥3:1. Do not invent pairings; if you must, verify 4.5:1 normal text / 3:1 large text and graphics.

## 2. Typography

Type scale = 15 baseline (Regular/Medium) + 15 emphasized (Medium) = 30 styles. Tokens: `display / headline / title / body / label` × `large / medium / small`. Views: `TextAppearance.Material3.*`. Default faces: `brand` for Display/Headline, `plain` for Body/Label (both Roboto by default).

Exact baseline (Roboto, size/line-height/tracking):
- `displayLarge 57/64/-0.25 Regular`, `displayMedium 45/52/0 Regular`, `displaySmall 36/44/0 Regular`
- `headlineLarge 32/40/0 Regular`, `headlineMedium 28/36/0 Regular`, `headlineSmall 24/32/0 Regular`
- `titleLarge 22/28/0 Medium`, `titleMedium 16/24/+0.15 Medium`, `titleSmall 14/20/+0.1 Medium`
- `bodyLarge 16/24/+0.5 Regular`, `bodyMedium 14/20/+0.25 Regular`, `bodySmall 12/16/+0.4 Regular`
- `labelLarge 14/20/+0.1 Medium`, `labelMedium 12/16/+0.5 Medium`, `labelSmall 11/16/+0.5 Medium`
- Emphasized variants keep size/line-height, weight becomes Medium.

When to use which (exact):
- Display L/M/S: hero/marketing single expressive lines, numerals. Never body copy.
- Headline L/M/S: short high-emphasis headings.
- Title L: dialog titles, top app bar, medium-emphasis subtitles. Title M/S: list headers, subtitles.
- Body L: default long-form content. Body M: list secondary text. Body S: captions, footnotes.
- Label L: buttons, tabs, dialog/card actions. Label M/S: badges, overlines, annotations on imagery.
- Emphasized: selection, actions, editorial hierarchy only.

Component mapping: TopAppBar → TitleLarge; ListItem title → TitleMedium, secondary → BodyMedium; Card title + Body; NavigationBar item → LabelMedium; Button → LabelLarge; Dialog title → HeadlineSmall, message → BodyMedium; TextField input → BodyLarge.

Rules: 40–60 characters per line for reading; never shrink type to force a fit; never truncate labels or error text; scale line-height for scripts (small Latin/Cyrillic/Greek/Hebrew at base; medium scripts +~7%; large +~30%).

## 3. Shape scale

Ordered scale: `None 0dp, ExtraSmall 4dp, Small 8dp, Medium 12dp, Large 16dp, LargeIncreased 20dp, ExtraLarge 28dp, ExtraLargeIncreased 32dp, ExtraExtraLarge 48dp, Full 50% (pill/circle)`. Per-corner override via `cornerSizeTopLeft/TopRight/BottomRight/BottomLeft` and `shapeAppearanceOverlay`. Shape family is rounded or cut (`shapeCornerFamily`), View default rounded.

Mandatory defaults (change only for a documented brand reason):
- Buttons, button groups, segmented buttons, FAB menu items → Full.
- Chips → Small 8dp.
- All cards → Medium 12dp.
- Dialogs, date/time pickers → ExtraLarge 28dp.
- Modal bottom/side sheets → ExtraLarge on top corners only.
- Text fields → ExtraSmall 4dp on top corners.
- Menus → ExtraSmall 4dp.
- SearchBar container → Full. Navigation bar indicator → Full. Navigation drawer → 16dp top/bottom. FAB → Large 16dp. ExtendedFAB → Large.

## 4. Elevation

Levels: `Level0 0dp, Level1 1dp, Level2 3dp, Level3 6dp, Level4 8dp, Level5 12dp`. Resting states use 0–3 only; 4–5 are hover/drag/focus. Hover raises any button/FAB exactly one level (FAB 3→4). M3 expresses elevation with primary-tinted tonal color plus shadow.

Resting map (exact):
- Level3: FAB, ExtendedFAB, FAB-menu close button, modal dialogs, date/time pickers, Search.
- Level2: scrolled app bar, toolbar, menu, navigation bar, rich tooltip.
- Level1: elevated button/card/chips, banner, modal bottom sheet, modal navigation drawer, modal side sheet.
- Level0: unscrolled app bar, filled/tonal/outlined buttons, filled/outlined cards, filled/outlined chips, carousel, fullscreen dialog, list, navigation rail, tabs, sliders, icon buttons, switches.

Rules: never exceed Level3 at rest; never rely on shadow alone to separate overlapping containers — use different `surfaceContainer*` roles.
