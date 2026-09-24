---
name: material-uiux
description: Use when designing, building, or reviewing ANY Android screen or UI. Material Design 3 rules for DexWorks: foundations (color, type, shape, elevation), layout and adaptive breakpoints, components and patterns, motion, accessibility, and loading/empty/error states. Trigger on UI work, layout XML, Compose, theming, navigation, dialogs, search, or visual review.
---

# Material 3 UI/UX Skill (DexWorks)

You are implementing UI for an Android app that follows **Material Design 3 (M3, Material You)**. Obey this skill on every UI task. It is written for direct execution: rules are imperative, values are exact, and ambiguity is resolved in favor of the rule stated here.

## How to use this skill

1. Always apply the **Non-negotiable rules** below.
2. Load the reference file matching the task before writing code:
   - Color, type, shape, elevation → `references/foundations.md`
   - Layout, breakpoints, adaptive nav, dark theme, edge-to-edge → `references/layout-adaptive.md`
   - App bars, navigation, buttons, dialogs, lists, forms, search → `references/components.md`
   - Animation durations, easing, transitions → `references/motion.md`
   - Touch targets, contrast, content descriptions, focus, states → `references/accessibility-states.md`
3. Load only the reference you need. Do not load all files by default.
4. If a rule here conflicts with a third-party snippet (Stack Overflow, blog), the rule here wins. Verify against the cited official source only when the rule is silent.

## Non-negotiable rules

1. **M3 component names, not M2.** Navigation bar (not "bottom nav" as a concept), SearchBar + SearchView, ModalBottomSheet, ExtendedFAB. Use M3 styles (`Widget.Material3.*`), never AppCompat/M2 styles on new UI.
2. **Color roles, never raw hex, for UI surfaces and text.** Backgrounds use `surface` / `surfaceContainer*`; fills use `*Container`; text/icons use the matching `on*` role (`onPrimary` on `primary`, `onPrimaryContainer` on `primaryContainer`). Never pair arbitrary roles (e.g. never `tertiaryContainer` text on `primaryContainer`).
3. **One primary action per screen.** Highest emphasis = one Filled button (or one FAB for create/compose). Secondary = Tonal/Outlined. Tertiary = Text button. Never two competing filled buttons.
4. **Touch targets are 48x48dp minimum.** A 24dp icon gets padding to reach 48dp. Spacing between tappables is 8dp minimum.
5. **Labels are always visible on navigation items.** Selected item = filled icon + active indicator pill; unselected = outlined icon. Selection is never color-only.
6. **Text contrast: 4.5:1 normal text, 3:1 large text and UI graphics.** If you invent a color pairing, check it. Default scheme pairings already comply — prefer them.
7. **States are designed, not blank.** Every async screen has loading, empty, and error states defined before shipping. No raw blank screens, no per-item spinners, no auto-dismissing critical errors.
8. **Edge-to-edge is assumed.** Content draws behind system bars; apply window insets to scrolling content (`contentPadding`, never double-pad); never hardcode status/nav bar heights.
9. **Back always works.** Dialogs, sheets, SearchView, drawers all collapse/dismiss on system back. Never add a custom back handler that fights the component's built-in one.
10. **Keyboard never breaks layout.** Prefer `adjustNothing` with SearchView; otherwise `adjustResize`. Never let the keyboard cover the focused field or focused action.

## Project conventions (DexWorks)

- Views + XML + ViewBinding (not Compose). Theme is base `Theme.Material3.DayNight.NoActionBar`. Keep it; the base-M3 linear morph on SearchView is correct — do not switch to an Expressive theme for animation.
- Bottom navigation has exactly 3 top-level destinations (Browse / Projects / Tools); labels always visible.
- Settings use simple words; multi-choice preferences show the selected item as summary.
