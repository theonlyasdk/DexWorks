DexWorks is an on-device Android package analysis and reverse-engineering workbench. It lets users browse installed apps and APK files, inspect their structure, decompile DEX files, and extract files. No desktop computer needed.

Stack: Kotlin, Views with XML and ViewBinding, no Compose. Navigation Component. Material 3 base theme Theme.Material3.DayNight.NoActionBar, keep it. Material library 1.14.0. minSdk 23, targetSdk 37.

Key files: MainActivity.kt holds the bottom navigation with Browse and Projects. BrowseFragment.kt holds the SearchBar and SearchView. AppDetailActivity and AppDetailFragment.kt show app details. PreferenceActivity and PreferenceFragment.kt with res xml preferences.xml hold settings. The Gradle version catalog is in gradle libs.versions.toml.

Skills: .agents/skills, 23 total. Lazy-load one max per task, never preload or stack.

Conventions: bottom navigation has exactly 3 destinations with labels always visible. Settings use simple words. Multi-choice preferences show the selected item as summary through useSimpleSummaryProvider. User-visible text goes in strings.xml, never hardcoded. No new dependencies without asking. Do not run builds or tests unless asked. Manage git commits when asked.

Working rules, in order:

1. Scope: only named files. No extra browsing/globbing/grepping. Ask before opening else.
2. Search cheap: grep first, then read offset/limit only. Never full files. Never re-read unchanged.
3. Output cheap: concise plaintext, what changed + paths. No diffs, files, narration. Short replies only.
4. Plan only if non-trivial (multi-file/feature/fix/refactor/multi-step): 3-5 lines, wait approval. Trivial: edit + done. No trial-error.
5. One acceptance check up front: single observable pass/fail.
6. Batch independent calls in one block. One skill max. No new deps/builds/tests unless asked.
7. Stop rules: verify edit by reading region once; stop after 2 fails, report blocker. One hypothesis, evidence first, no overthinking loops.
