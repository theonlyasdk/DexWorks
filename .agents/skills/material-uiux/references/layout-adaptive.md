# Layout, Adaptive, Dark Theme, Edge-to-Edge

Sources: M3 layout/breakpoints guidance, applying-layout, WindowSizeClass, edge-to-edge and insets guidance.

## 1. Spacing

Base unit 8dp with a linear scale: `2, 4, 6, 8, 10, 12, 16, 20, 24, 32, 40dp…`. Categories: `padding` (inside a container), `gap` (between elements in a container), `margins` (outside, vs parent/screen). Prefer padding + gaps; margins only at layout/parent level.

Fixed rules: 4dp baseline grid for small elements (icons are 24dp; micro steps 2/4/6/10dp); 8dp grid for layout and components. Never place tappables closer than 8dp apart. Screen side margins in compact layouts are 16dp.

## 2. Breakpoints (window size classes)

M3 breakpoints are opinionated window widths, not devices. Design for the window, because fold/unfold, rotation, split-screen, and multi-window change the class at runtime.

Exact width table:
- Compact: under 600dp — phone portrait. Single pane. Navigation bar or modal expanded rail.
- Medium: 600–839dp — tablet portrait, unfolded foldable portrait. 1 pane (recommended) or 2 low-density. Navigation bar or (collapsed/modal expanded) rail.
- Expanded: 840–1199dp — phone landscape, tablet landscape, unfolded foldable landscape, desktop. 2 panes recommended. Modal or standard expanded rail.
- Large: 1200–1599dp — desktop. 2 panes. Standard or modal expanded rail.
- Extra-large: 1600dp+ — desktop, ultrawide. Up to 3 panes. Standard expanded rail.

Height matters independently: compact height under 480dp (phone landscape) forbids two-pane even when width says Medium. Never show rail + bar together. Never two primary navigations on one screen. Under 3 destinations use Tabs, not a bar.

Pane rules: compact + medium = single pane; expanded + large = two panes; extra-large = three panes recommended. Never force two dense panes at Medium. Swap navigation across breakpoints (bar → rail → drawer), do not merely resize.

Canonical scaffolds: `NavigationSuiteScaffold` (auto bar↔rail), `ListDetailPaneScaffold` (list+detail side by side when expanded, single + navigation otherwise), `SupportingPaneScaffold` (main + supporting). Use `BoxWithConstraints`/maxWidth for presentational reflow; `WindowSizeClass` only for app-level canonical decisions.

Foldables: handle `Flat / Book / Tabletop` postures via folding features; never place content or controls under a fold or hinge — treat it as a divider. Test unfolded portrait (Medium) vs landscape (Expanded).

## 3. Navigation mapping summary

- 3–5 top-level same-hierarchy destinations on phones/tablets → Navigation bar (compact + medium).
- 3–7 destinations on tablets/desktop → Navigation rail (collapsed by default; expanded replaces the legacy drawer).
- 5+ destinations or 2+ hierarchy levels on large screens → standard navigation drawer; modal drawer (scrim, opened by menu icon) on any size, primarily compact/medium.
- Tabs = secondary navigation for related content inside one page (e.g. Library → Playlists/Artists/Albums). Tabs complement navigation; they never replace it for distinct destinations.

## 4. Dark theme

Same roles, swapped tones — generate light + dark from one seed (Material Theme Builder) and ship both. Views: `Theme.Material3.DayNight.*`. Reflect the system setting; an in-app toggle is optional; never lock to light.

Rules: dark accents are desaturated and luminous (primary-80 not saturated-500); dark surfaces come from neutral tones 4–24 (surface `#141218`, lowest `#0F0D13`, bright `#3B383E`); no bright/bold surfaces in dark; test `onSurface`/`onSurfaceVariant` legibility on every container level because tonal elevation changes the surface; verify red/green safety for color-blind users.

## 5. Edge-to-edge + window insets

Android 15 / targetSdk 35 draws edge-to-edge by default; back-compat via `enableEdgeToEdge()` in `Activity.onCreate` before `setContent`. Status and gesture bars are transparent; the 3-button nav bar gets a translucent scrim (disable with `window.isNavigationBarContrastEnforced = false` on SDK 29+ to extend bar color). Icon contrast follows light/dark automatically via ComponentActivity.

Inset types: `systemBars, displayCutout, systemGestures, ime, tappableElement, captionBar`. Use composites: `safeDrawing` (no drawing under obstructing UI — the common case), `safeGestures` (sheets, carousels, games), `safeContent` (both).

Rules: pass insets into scrolling content as `contentPadding` so lists scroll behind bars; consume insets once (`consumeWindowInsets`) so siblings are not double-padded; `RecyclerView` gets `clipToPadding=false`; `AppBarLayout` gets `fitsSystemWindows=true`; M3 bars/rails/drawers/sheets handle their own insets — do not add manual padding on top of them; never hardcode status/nav bar heights; read insets in the layout phase only.
