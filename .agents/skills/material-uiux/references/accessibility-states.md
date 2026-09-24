# Accessibility and Screen States

Sources: M3 accessibility guidance, WCAG via M3, Material Components Android a11y behavior.

## 1. Touch targets and spacing

- Minimum touch target 48x48dp (a 24dp icon carries padding to reach it). Pointer (mouse/stylus) minimum 44x44dp. Minimum 8dp gap between adjacent tappables.
- Bottom-sheet drag handle reserves 48dp of interactive height at top; resizing must also work via a button, never drag-only.
- Never shrink targets for density settings — adapt vertical padding of containers, not the target size.

## 2. Contrast

- Text: 4.5:1 normal, 3:1 large (≥14pt bold or ≥18pt regular). Non-text UI and graphics (icons vs container, active indicator, focus rings): 3:1.
- Use default scheme pairings — they comply by construction. Filled-vs-outlined selection cues must survive monochromacy: indicator pill + filled icon weight, never color alone. Never ship multiple low-contrast navigation colors.

## 3. Content descriptions

- Label the purpose, not the appearance ("Voice search", never "Microphone"; never "magnifying glass").
- Never include the type in the label (screen reader appends the role: Button, Tab, Title). Label usually equals the visible text ("Photos" → "Photos"); add context only when ambiguous ("Recents" → "Recent photos").
- Icon buttons are labeled by their action ("View on map"). Decorative images get `contentDescription=null` / hidden. Badge counts are announced. Drag handles carry the Button role. Drawer and SearchView siblings are auto-hidden from services while open.
- Scaled text to 2x must remain fully visible (rails grow vertically, wrapping allowed); beyond 2x, truncation of non-essential text is acceptable but essential labels never truncate.

## 4. Focus and keyboard

- Initial focus = first interactive element serving the screen's goal (app-bar leading button; dialog's first input; drawer's first destination; a home screen's search field).
- Focus returns to the invoking element on dismiss (dialog, sheet, search, drawer).
- `Tab / Shift+Tab` moves between groups; arrow keys move within groups (nav items, menus, tabs); `Space / Enter` activates; `Esc` dismisses. Focus rings are always visible. Collections are a single Tab stop with arrow-key traversal inside; DOM order is left-to-right, top-to-bottom unless deliberately tailored.
- TalkBack: explore-by-touch plus linear navigation; headings define the hierarchy.

## 5. Loading, empty, error states

Design all three before shipping any async screen.

- Loading: skeleton placeholders that transition into content; indicator placement signals scope (top of screen = whole page; attached to a card = that card; centered circular = that container). Long loads allow navigating away. Instant loads (<200ms) show no indicator.
- Empty: illustration or icon + concise headline + supporting text + primary action ("No results", "Create"). A search with no matches is an empty state, never an error.
- Error: inline field error for validation (error state + supporting text, input preserved, focus moved to the error, announced); fullscreen error with Retry for load failure; Snackbar with Retry for transient low-stakes failures; Dialog only for blocking high-stakes interruptions (sign-in required, destructive confirm).
- Never: a spinner per list item, an indefinite skeleton without timeout, a critical error in an auto-dismissing Snackbar.
