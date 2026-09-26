DexWorks is an on-device Android package analysis and reverse-engineering workbench. It lets users browse installed apps and APK files, inspect their structure, decompile DEX files, and extract files. No desktop computer needed.

Stack: Kotlin, Views with XML and ViewBinding, no Compose. Navigation Component. Material 3 base theme Theme.Material3.DayNight.NoActionBar, keep it. Material library 1.14.0. minSdk 23, targetSdk 37.

Key files: MainActivity.kt holds the bottom navigation with Browse and Projects. BrowseFragment.kt holds the SearchBar and SearchView. AppDetailActivity and AppDetailFragment.kt show app details. PreferenceActivity and PreferenceFragment.kt with res xml preferences.xml hold settings. The Gradle version catalog is in gradle libs.versions.toml.

Skills: agent skills live in the .agents skills folder, one folder per skill, 23 in total including android-dev, compose, kotlin-docs, material-uiux, kotlin-coroutines, kotlin-flows, android-testing, android-debugging. Load only the single skill matching the task, never several at once.

Conventions: bottom navigation has exactly 3 destinations with labels always visible. Settings use simple words. Multi-choice preferences show the selected item as summary through useSimpleSummaryProvider. User-visible text goes in strings.xml, never hardcoded. No new dependencies without asking. Do not run builds or tests unless asked. Manage git commits when asked.

Working rules for every session, in order:

1. Only touch files named in the task or directly referenced by it. No speculative browsing, globbing, or grepping. Ask before opening anything else.
2. Find code with grep first, then read only the matching section with offset and limit. Never read whole large files. Never re-read a file that has not changed.
3. Reply concisely in plaintext: what changed plus file paths. No diffs, no full files, no narration.
4. For anything beyond a trivial edit, write a plan of 3 to 5 lines and wait for approval before acting. Never fix by trial and error. For a trivial change, just make the edit and say done.
5. State the acceptance check up front: one observable pass or fail condition per task, for example a specific screen behavior or a specific string value.
6. Batch independent reads and searches in one block instead of running them one by one.
7. After an edit that must be exact, read back the edited region to confirm it before finishing.
8. Stop after 2 failed attempts on one step. Report the blocker instead of retrying variations.
