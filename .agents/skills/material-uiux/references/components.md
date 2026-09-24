# Components and Patterns

Sources: M3 component guidance, Material Components Android docs (`docs/components/Search.md`), API reference `com.google.android.material.search`, Stack Overflow #75996550 and #76253110.

Naming below uses M3 design names first, Views class in parentheses.

## 1. App bars

Variants: Search app bar, Small, Medium flexible, Large flexible. (Baseline Medium/Large non-flexible are deprecated; baseline Center-aligned is now a centered-text Small configuration.)

- Search app bar (Body Large): home screens where search is primary; opens SearchView.
- Small (Title Large, 1–2 essential actions): dense, scrolled, and subpages with back navigation.
- Medium flexible (Headline Medium): larger headline that collapses to small on scroll.
- Large flexible (Display Small): emphasized headline that collapses on scroll.
- Headline wraps to at most 2 lines in medium/large flexible only; small never wraps or truncates. Subtitles allowed but keep the bar uncrowded; at most 1–2 actions; never multiple filled/tonal buttons in a bar.
- On scroll the bar fills with `colorSurfaceContainer` (lift-on-scroll), no shadow. Views scrolling requires: bar as direct child of `CoordinatorLayout` + scrolling sibling with `@string/appbar_scrolling_view_behavior` + flags `scroll|enterAlways|snap` (quick return) or `scroll|exitUntilCollapsed` (medium/large). `MaterialToolbar` inside `AppBarLayout` assumes transparent background.
- Pitfall: forgetting the nested-scroll connection means the bar never collapses or lifts. Never set a custom `android:background` on `SearchBar` (it extends Toolbar).

## 2. Navigation components

- Navigation bar (`BottomNavigationView`): 3–5 destinations, compact + medium windows, labels always visible, one active indicator only, selected icon filled (+ pill indicator), unselected outlined.
- Navigation rail (`NavigationRailView`): 3–7 destinations, medium and up, collapsed by default; expanded rail replaces the drawer. Optional FAB/header. Never rail + bar simultaneously.
- Navigation drawer (`NavigationView` + `DrawerLayout`): standard on large screens with 5+ destinations or deep hierarchy; modal (scrim, menu-icon opened) on any size. Item tap checks the item and closes the drawer.
- Tabs (`TabLayout`): secondary in-page navigation for related content. Under 3 destinations use Tabs, not a bar.
- Badges: small dot = update available; numbered = count; upper-right of icon; 3:1 contrast.
- Pitfalls: `setOnNavigationItemSelectedListener` is deprecated — use `setOnItemSelectedListener`; each tab reselection must not duplicate the back stack (`popUpTo + saveState + restoreState + launchSingleTop`); drawer open/close are suspending — launch from a coroutine scope; disable drawer gestures where they conflict with swipe-to-dismiss or carousels.

## 3. Buttons, FAB, menus

Ten types: elevated / filled / tonal / outlined / text Button; toggle; IconButton; SplitButton; standard + connected ButtonGroup; small (40dp) / medium (56dp) / large (96dp) FAB; ExtendedFAB; FAB menu.

Hierarchy (highest to lowest): ExtendedFAB/FAB/FAB menu for the page's primary create/compose action → Filled button for the final Save/Confirm/Done → SplitButton for key action + options → ButtonGroup for Back/Pause/Next → Tonal for secondary → Elevated only to separate from a patterned background → Outlined for See-all/Add-to-cart/escape → Text button for lowest emphasis. One FAB per screen, 16dp margins, pinned so it stays visible on scroll, never overlapping the navigation bar. FAB menu: 2–6 items, always label + icon, matching color set, opened only from the same-place FAB.

Menus: `ExposedDropdownMenu` for selection inside a text field; dropdown/overflow menus for secondary actions; on mobile, long lists with icons/descriptions belong in a ModalBottomSheet, not an inline menu. `SearchBar` menus use `inflateMenu` + `setOnMenuItemClickListener` (not the ActionBar path unless `forceDefaultNavigationOnClickListener=true`); never fight its default navigation icon behavior.

## 4. Dialogs, sheets, snackbars

Priority ladder: Snackbar = low-stakes transient info with at most one action (+ optional dismiss), auto-dismisses, appears at bottom, one at a time. Dialog = high-stakes blocking confirmation requiring a decision. Never a dialog for low/medium info; never a Snackbar for critical errors that must not auto-dismiss.

- Dialogs (`MaterialAlertDialogBuilder`): basic (icon/title/message/actions) or fullscreen (mobile creation flows only). Title pinned top, buttons pinned bottom, avoid scrolling. Focus moves inside on open (first input) and returns to the invoker on close.
- Modal bottom sheet: mobile alternative to menus/simple dialogs for long lists. Initial height ≤50% of screen; dismiss via item tap, scrim, swipe-down, or close affordance; 48dp drag handle on top that is keyboard-focusable. Constrained max width on tablets; becomes a side sheet on desktop. Requires `CoordinatorLayout` for swipe/scrim behavior.
- Standard sheet/side sheet: coexists with content (e.g. music player). Side sheet max width 400dp, 24dp side padding (16dp with icon), close affordance mandatory.
- Pitfalls: a `SnackbarHost` missing from the scaffold swallows messages; queuing without the suspend `showSnackbar` drops them; a Snackbar nested inside a dialog is hidden — hoist the host.

## 5. Lists, cards, progress, search results

- Lists (`RecyclerView` + `ListItem` rows): continuous vertical index; one/two/three-line rows with leading avatar/icon and trailing control; dividers group sections. Do not force lists into cards when spacing, headlines, and dividers suffice.
- Cards (elevated / filled / outlined; container shape Medium 12dp): elevated = `surfaceContainerLow` + shadow; filled = highest tonal container; outlined = surface + `outlineVariant`. May hold media, chips, checkboxes, icon buttons, overflow menus. Tap navigates via container transform to detail.
- Progress: instant (<200ms) = no indicator; short (200ms–5s) = morphing loading indicator that grabs attention; long (>5s) = determinate linear/circular progress that must be accurate. Linear on the container edge or top of screen; circular centered on the container/button/card. One indicator per group, same variant for the same process everywhere. Never a spinner per list item; never an indefinite skeleton without timeout.

## 6. Text fields, forms, selection controls

- Filled `TextField` (`surfaceContainerHighest`, more emphasis, short forms/dialogs) vs outlined (`OutlinedTextField`, less emphasis, long forms). Anatomy: container + always-visible floating label + input + caret + active indicator + leading/trailing icons + supporting/error text. Labels and errors are short and never truncated. Dropdown arrow means nested selection.
- Forms: single column, related fields grouped, required marked, validate on blur/submit with inline error + supporting text, submit via a filled button.
- Selection: Checkbox = multi-select or on/off in a list (with indeterminate tri-state); Radio = exactly one of a set (pre-select the most common); Switch = standalone setting with immediate effect, always with an inline label describing the ON state — never a CTA replacement (use a Button), never batch-save (use Checkboxes); Slider = ranged value with immediate effect, keyboard-accessible alternative, optional synced text field; Chips = assist / filter / input / suggestion.
- Pitfall: a Switch that does not apply immediately confuses users — that pattern needs a Button + Checkbox instead.

## 7. Search (SearchBar + SearchView) — exact recipe

`SearchBar` (persistent floating pill: container + leading icon + supporting text + optional avatar/trailing) morphs into `SearchView` (fullscreen modal: header + leading/trailing + input + divider). Requires Material library 1.8.0+; project uses 1.14.0.

Mandatory wiring (morph animation is free only with this setup):
- Place both in a top-level `CoordinatorLayout`; the `SearchView` is `match_parent`/`match_parent` with `app:layout_anchor="@id/search_bar"`. This auto-hooks tap-to-show plus expand/collapse morph. Without `CoordinatorLayout`, call `searchView.setupWithSearchBar(searchBar)` in code instead (Stack Overflow #75996550).
- Never use `androidx.appcompat.widget.SearchView` or `android.widget.SearchView` for this pattern (those are the old iconified/collapseActionView widgets).
- Never call `searchBar.expand(...)`/`collapse(...)` for the search morph — those APIs are for contextual Toolbar transitions (e.g. multi-select), not SearchView.
- Standalone `SearchView` without a `SearchBar` only slides up/down; the morph requires the pair.
- Modes: fixed (bar on top of content, no scrolling behavior) vs scroll-away (`@string/searchbar_scrolling_view_behavior` on the scrolling child; makes AppBarLayout transparent so the bar floats) vs lift-on-scroll (`@string/appbar_scrolling_view_behavior` + `liftOnScroll=true` + `liftOnScrollColor`). Use fixed unless the design demands scrolling.
- Keyboard: recommended `windowSoftInputMode` with SearchView is `adjustNothing` (avoids `adjustResize` glitches during the morph; SearchView staggers the keyboard with the animation). If the mode changes at runtime, mirror it via `searchView.setSoftInputMode(...)`. Suppress auto-keyboard with `app:autoShowKeyboard="false"`.
- Edge-to-edge: wrap the `SearchBar` in a `fitsSystemWindows` container so it clears the translucent status bar; never set `fitsSystemWindows` on the `SearchView` (it adds its own spacer).
- Sibling icons animate off during expansion via `app:startSiblingViewId` / `app:endSiblingViewId` (one ID per side; wrap multiple icons in one ViewGroup). Inside a Toolbar, nav + menu are treated as siblings automatically.
- Contained style (bar visually persists inside the view): SearchBar inside `AppBarLayout` + `materialSearchViewStyle = Widget.Material3Expressive.SearchView.AppBarWithSearch`. The bouncy variant requires an Expressive theme; on the base `Theme.Material3.*` the same style morphs with linear motion — that is correct, do not change the theme for it.
- Behavior in code: `searchView.getEditText()` for `setText`/`addTextChangedListener`/`setOnEditorActionListener`; `inflateMenu` + `setOnMenuItemClickListener` for its menu; `addTransitionListener` for SHOWING/SHOWN/HIDING/HIDDEN callbacks; `searchView.hide()` to collapse; back press collapses automatically (predictive-back supported when connected to a SearchBar — never add a competing back handler); prefix pattern via `app:searchPrefixText` + `hideNavigationIcon=true`; drawer-arrow morph via `app:useDrawerArrowDrawable=true`.
- Content inside SearchView: history when first expanded, suggestions while typing, results after submit — as a scrolling list nested in the SearchView.
- Accessibility: set `android:contentDescription` on both; SearchView auto-marks siblings non-important-for-accessibility while shown and restores on hide — if you ever remove an open SearchView from the hierarchy, call `setModalForAccessibility(false)` first or the restoration never runs.
