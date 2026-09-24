---
name: kotlin-docs
description: Use when writing or reviewing Kotlin code. A condensed summary of the official Kotlin language documentation (kotlinlang.org/docs) covering syntax, types, null safety, control flow, functions, classes, generics, the standard library, coroutines, idioms, and Java interop. Trigger on any Kotlin task, Kotlin questions, or code review.
---

# Kotlin Documentation Summary

A condensed reference distilled from the official Kotlin documentation (https://kotlinlang.org/docs). The authoritative source for exact/edge-case behavior is kotlinlang.org/docs; treat this as a fast, reliable overview.

Source pages: basic-syntax, types-overview, numbers, strings, arrays, booleans, characters, control-flow, ranges, functions, lambdas, classes, inheritance, data-classes, generics, object-declarations, properties, null-safety, typecasts, extensions, collections-overview, sequences, coroutines (overview/basics/context/dispatchers/cancellation/flow), idioms, coding-conventions, java-interop, unsigned-integer-types.

## 1. Program structure

- Packages: `package my.demo` at the top of the file. Package path need not match directories.
- Imports: `import kotlin.text.*`, `import foo.bar.Baz`.
- Entry point: `fun main()` or `fun main(args: Array<String>)`.
- Output: `print()`, `println()`. Input: `readln()` (returns a line as String), `readlnOrNull()` (returns null at EOF). Safe number parsing: `readln().toIntOrNull()`.
- Comments: `//` and `/* */`; block comments nest.
- Trailing commas are allowed in parameter lists and most comma-separated declarations.

## 2. Types overview

- Everything is an object (member functions and properties callable on any value). Number/char/boolean are optimized to primitives at runtime but behave like classes.
- Basic types (non-nullable by default):
  - Integers: `Byte`, `Short`, `Int`, `Long`. Unsigned: `UByte`, `UShort`, `UInt`, `ULong`.
  - Floating point: `Float`, `Double`. Boolean: `Boolean`. Char: `Char`. String: `String`. Arrays: `Array<T>` and primitive arrays (`IntArray`, etc.).
- Special types: `Any` (root of class hierarchy, like Object), `Nothing` (type with no values; return type of `TODO()`, `throw`, `error()`, `exitProcess()`), `Unit` (like Java void; only value is `Unit`).
- Nullable types end with `?` (`String?`). Nullability is enforced at compile time.
- Non-denotable types (cannot be written in source, appear in diagnostics): platform types (from Java/JS/Native, e.g. `String!`), intersection types, integer literal types, captured types.
- Type checks/casts: `is` / `!is` (smart-cast immutable locals/properties in branch), `as` / `as?` (unsafe / safe cast).
- Literals: `0x1F` (hex), `0b0001` (binary), `1_000_000` (underscores). `Long` literal `1L`, `Float` `1f`, char `'a'`, string `"..."`, raw string `"""..."""`.
- Explicit number conversion required between types (`toInt()`, `toLong()`, `toDouble()`, ...); no implicit widening.
- `toChar()`/`digitToInt()` etc. for char conversions.

## 3. Null safety

- Non-null and nullable types are distinct: `String` vs `String?`.
- Safe call `?.` executes only if receiver non-null; chains: `a?.b?.c`.
- Elvis `?:` supplies default when left side is null: `files?.size ?: "empty"`. RHS can be a block via `run {}`.
- Not-null assertion `!!` throws NPE if null.
- Smart casts: after null check (`if (x != null)`) or safe call, compiler narrows the type. Smart-cast works on `val`s, and on `var`s not captured by lambdas and without custom getters.
- Idioms: `value?.let { ... }`, `value ?: return/throw`, `firstOrNull() ?: ""` vs `first()`.
- Platform types from Java: `String!` — treated as nullable or not at your discretion; check defensively.
- `require()`/`check()` for parameter/state validation (throw IllegalArgumentException / IllegalStateException).

## 4. Control flow

- `if` is an expression: `val max = if (a > b) a else b`. No ternary operator.
- `when` replaces switch and is exhaustive-aware: branches `1`, `"Hello"`, `is Long`, `!is String`, `in 1..10`, `else`. Can be an expression with a value. `when (x) { is Foo -> ... }` for type checks.
- `for (item in items)`, `for (i in items.indices)`, `for ((k, v) in map)`. Ranges: `1..10` (inclusive), `1..<10` (exclusive), `2..10 step 2`, `10 downTo 1`, `(1..5).forEach {}`.
- `while`/`do-while` standard. `break`, `continue` standard. Labels (`loop@`) supported.
- Ranges/Progressions: `in` / `!in` checks; `IntRange`, `CharRange`; `rangeTo`, `downTo`, `until`, `step`.
- `return`, `return@label`, `break@label`, `continue@label` for jumps.
- Try/catch is an expression: `val result = try { count() } catch (e: ArithmeticException) { ... }`. `try/catch/finally`, `throw` (type `Nothing`).

## 5. Functions

- Declaration: `fun name(params: Type): ReturnType { }`. Block bodies require explicit return type (except `Unit`).
- Single-expression body: `fun double(x: Int): Int = x * 2` (return type optional, unless recursive/mutually recursive/typeless).
- Parameters are read-only (`val`-like); objects passed by reference-copy, mutable state modifiable.
- Default parameter values (can be non-constant expressions, may reference earlier params). Overrides cannot re-declare defaults.
- Named arguments: callable in any order; after the first skipped default, all subsequent must be named. Not available for Java functions.
- `vararg` for variadic params (usually last); spread with `*arr`. Primitive arrays need `.toTypedArray()` to spread.
- `Unit` return: no `return` needed; type is `Unit`.
- Return multiple values: prefer a data class with descriptive names over `Pair`/`Triple` (whose `first`/`second` are unclear); use `Pair`/`Triple` for throwaway tuples.
- Infix functions: `infix fun Int.shl(x: Int)` — must be member or extension, single param, no vararg, no default. Precedence: lower than arithmetic/typecast/rangeTo; higher than `&&` `||` `is` `in`.
- Local functions (inside other functions, close over outer locals), member functions, top-level functions.
- Generic functions: `fun <T> singletonList(item: T): List<T>`.
- `tailrec` for stack-safe recursive calls: only when the recursive call is the last operation.
- Inline functions (`inline`) for lambdas; `reified` type params in inline functions enable `T::class` at runtime.
- Extension functions/properties: `fun String.spaceToCamelCase() { ... }`; `this` is the receiver; can't access private members.
- Higher-order functions and lambdas: `val upper = items.map { it.uppercase() }`; trailing lambda syntax `foo { ... }`; `it` for single param; `_` to skip params; lambda returns last expression.
- Scope functions (stdlib): `let` (return last expr; `it`), `run` (return last expr; `this`), `with` (return last expr; non-extension; `this`), `apply` (return receiver; `this`) for configuring objects, `also` (return receiver; `it`) for side effects. Choose by "what do you want back and how do you reference the receiver".
- Operator overloading via operator functions (`operator fun plus`, etc.); `get`/`set`, `contains`, `invoke`, `componentN`.

## 6. Classes and objects

- `class Rectangle(val height: Double, val length: Double)` — primary constructor params become properties with `val`/`var`. Classes are `final` by default; `open` to allow inheritance; `abstract` for abstract; `sealed` for restricted hierarchies.
- Inheritance: `class Rectangle : Shape()` (colon + parent constructor call). `override` keyword required; base must be `open`. Every class inherits `Any`.
- Secondary constructors: `constructor(...)` with `: this(...)` delegation. `init {}` blocks run at construction.
- Data classes: `data class Customer(val name: String, val email: String)` auto-generates `equals`, `hashCode`, `toString`, `copy()`, `component1()..componentN()` (destructuring). Requirements: primary constructor only, ≥1 param, params `val`/`var`, no `open`.
- `object` = singleton (thread-safe lazy). `companion object` = per-class static-ish members (JVM: `@JvmStatic` for static). Anonymous objects: `object : MyAbstractClass() { override ... }`.
- Sealed classes/interfaces: `sealed class Result { data class Success(...) : Result() ... }` — exhaustive `when` without `else`; all subclasses same package/module (JVM: same compilation unit unless sealed interface).
- Interfaces: define abstract + default implementations; can hold properties; multiple interface inheritance.
- Visibility: `public` (default), `private` (file/class), `internal` (module), `protected` (class + subclasses).
- Properties: `var` with custom getters/setters, backing field `field`; `val` read-only. Delegated properties: `by lazy`, `by Delegates.observable()`, `by map`, `by vetoable`.
- Value classes: `@JvmInline value class` wraps one value for type safety without allocation; `@JvmInline` needed only on JVM.
- Type aliases: `typealias Name = String`.
- Nested vs inner classes: `inner` classes capture the outer instance.
- Generics: `<T>`, variance `out` (covariant/producer) / `in` (contravariant/consumer), type projections (`List<out Number>`), star projection `List<*>`, upper bounds `T : Comparable<T>`, multiple bounds `T : A, B` where `where`.
- Destructuring declarations: `val (name, age) = person`.
- Enum classes, sealed, annotations (`annotation class`), `when` exhaustiveness.
- `fun interface` = SAM interface (single abstract method).

## 7. Standard library highlights

- Collections: read-only interfaces first — `List`, `Set`, `Map` via `listOf`, `setOf`, `mapOf`; mutable via `mutableListOf`, etc. Iteration, `in` for membership, `filter`/`map`/`sortedBy`/`forEach`, `firstOrNull`, `maxByOrNull`, `groupBy`, `associate`, `zip`, `partition`, `any`/`all`/`none`, `distinct`, `chunked`, `windowed`, `fold`/`reduce`, `sum`, `joinToString`.
- Sequences (`Sequence`) for lazy chains: `asSequence().map{}.filter{}.toList()` — avoids intermediate collections for large data.
- `Array<T>`, and specialized primitive arrays: `IntArray(size)`, `intArrayOf(...)`; `.contentToString()`, `.contentEquals()`, deep comparisons for nested arrays.
- Strings: templates `"$a"` / `"${expr}"`, raw strings, `uppercase()`/`lowercase()` (locale-independent), `trim()`, `split`, `replace`, `contains`, `startsWith`, `substring`, `removePrefix`, `length`.
- `Result<T>`: success/failure handling, `.getOrNull()`, `.getOrElse`, `runCatching {}`.
- `runCatching { }` as try/catch expression idiom.
- Time: `kotlin.time` — `Duration`, `measureTime`, `measureTimedValue` (exact APIs vary by version; check reference).
- I/O: `readln`, `Files.newInputStream(...).buffered().reader().use { }` (use = try-with-resources for Closeable).
- `TODO()` returns `Nothing`, throws `NotImplementedError`, optional reason.
- `bisect`/math: `kotlin.math` (abs, cos, sqrt, etc.).
- `String.format` (JVM), `"%.2f".format(x)` deprecated in newer versions — prefer `toString()` on doubles or locale-aware formatting.
- `lazy {}` for thread-safe deferred property initialization.
- Marked experimental APIs need opt-in (`@OptIn`).

## 8. Coroutines (kotlinx.coroutines)

- Concept: lightweight threads; `suspend` functions pause without blocking. Async code in sequential style.
- Builders: `launch {}` (fire-and-forget result), `async {}` (returns `Deferred<T>`, `.await()`), `runBlocking {}` (blocks current thread; for non-suspend context like main/tests). All are extensions on `CoroutineScope`.
- Context elements: `Job` (lifecycle), `CoroutineDispatcher` (thread control), `CoroutineExceptionHandler`. Constructed via `CoroutineContext`; inherited by default from parent — structured concurrency.
- Dispatchers: `Dispatchers.Main` (UI), `Dispatchers.IO`, `Dispatchers.Default` (CPU), `Dispatchers.Unconfined`. Switch with `withContext`.
- Cancellation: cooperative; check `isActive`/`ensureActive()`; `CoroutineScope.cancel()`; structured concurrency — parent cancel cancels children. `cancelAndJoin`. `withTimeout`/`withTimeoutOrNull`.
- Exception handling: `SupervisorJob` (children failures don't propagate), `CoroutineExceptionHandler`, `try/catch` inside coroutine. In `supervisorScope` and `CoroutineScope(SupervisorJob())` failures are isolated.
- Flows: cold stream `flow { emit() }`; hot variants — `StateFlow` (latest state, `value`), `SharedFlow` (shared emissions), `Channel` (each value consumed by exactly one receiver). Operators: `map`, `filter`, `flatMapLatest`/`flatMapConcat`, `debounce`, `distinctUntilChanged`, `.collect`, `.collectLatest`, `.catch`, `onEach`, `shareIn`/`stateIn`.
- Converting: `callbackFlow` + `awaitClose` to wrap callbacks; `channelFlow`. `StateFlow` for UI state; one-shot events: `Channel(BUFFERED)` (preferred for precisely-once buffered events) or `SharedFlow(replay = 0)`.
- UI scopes: `lifecycleScope`, `viewModelScope` (AndroidX) for lifecycle-safe coroutines.
- Best practices: prefer `suspend fun` over exposing raw scopes; let the caller own the scope; always handle `CoroutineScope` lifecycle (cancel in `onDestroy`/`onPause` as appropriate; never leak).
- Debug: IntelliJ coroutine debugger, `kotlinx-coroutines-debug`, structured-concurrency viz.

## 9. Java interop

- Platform types (`String!`) — nullable ambiguity; annotate with `@Nullable`/`@NotNull` on Java side for better Kotlin types.
- SAM conversion: `Runnable { ... }` automatically for single-method interfaces (`fun interface` or Java SAM).
- `@JvmStatic` for companion method access from Java; `@JvmField` exposes property as field; `@JvmOverloads` generates overloads for default params.
- Named arguments unavailable for Java methods; use positional.
- `@Throws(Exception::class)` to declare checked exceptions. `@Synchronized`, `@JvmName` to rename functions, `@JvmMultifileClass`/`@JvmPackageName`.

## 10. Idioms (quick reference)

- DTO/data holder: `data class Customer(val name: String, val email: String)`.
- Default values: `fun foo(a: Int = 0, b: String = "") {}`.
- Filter: `list.filter { it > 0 }`.
- Membership: `if ("x" in emails) {}` / `!in`.
- Read-only list/map: `listOf`, `mapOf("a" to 1)`; access `map["key"]`; iterate `for ((k, v) in map)`.
- `val p: String by lazy {}`; singleton `object Resource {}`.
- Type-safe wrappers: `@JvmInline value class EmployeeId(val id: String)`.
- `if-not-null-else`: `files?.size ?: "empty"`; `value ?: throw IllegalStateException("...")`.
- Execute if not null: `value?.let { }`; map nullable: `value?.let { transform(it) } ?: defaultValue`.
- Swap: `a = b.also { b = a }`.
- try/catch expression; `if` expression; single-expression functions combined with `when`.
- Builder setup: `IntArray(size).apply { fill(-1) }`; `Rectangle().apply { length = 4; breadth = 5 }`.
- `with(obj) { ... }`; `use {}` for closeables; reified generics: `inline fun <reified T: Any> ... `.
- `TODO("reason")` for unfinished code.

## 11. Coding conventions summary

- 4-space indent, no tabs. PascalCase for classes, camelCase for functions/variables, SCREAMING_CASE for top-level constants (val).
- Trailing commas in multiline declarations. Explicit return types on public API and block-body functions.
- Prefer `val` over `var`; immutable interfaces first; expression bodies when they aid clarity; descriptive names; avoid `!!` when smart-cast/safe-call suffices; prefer `when` expression over long if/else; use named args for booleans/content params.
- Avoid unused `it` warning; eliminate redundancy with `_`; keep comments meaningful, code self-explanatory.
- Use explicit imports, no wildcard except `kotlin.text.*` etc. in examples.

## 12. Language & tooling notes

- Multiplatform (KMP): same language; platform-specific code via `expect`/`actual` declarations; common code in `commonMain`; JVM, JS, Native, WASM targets.
- Version notes:
  - `1..<10` exclusive range literal requires recent versions (2.0+ style).
  - `String.uppercase()` replaces deprecated `toUpperCase()` (and locale-sensitive variants removed).
  - `"x".format()`/`String.format` printf variants deprecated on some platforms — check current deprecation.
  - `popcount`, `countOneBits` style Int/Long props available.
  - Coroutine DSL: `runCatching`, `checkNotNull`, `requireNotNull`.
- See kotlinlang.org/docs for: Builders/DSL (type-safe `@DslMarker` builders), Type-safe builders, Multiplatform specifics, and API reference (kotlinlang.org/api).

## Usage guidance

- For detail beyond this summary, consult the linked official pages; never guess exact experimental API shapes — check the current docs/API reference.
- When writing Kotlin, prefer idiomatic constructs above (data classes, sealed hierarchies, expression bodies, scope functions, structured concurrency).
- For Android context, pair with the android skills (android-dev, compose, etc.) in this repo for framework-level conventions.