# Optimizing core-lite bundle size

## The loop

1. **Measure** — `bb report` prints brotli sizes for every active test, core-lite vs cljs-core.

2. **Diagnose** — build with pseudo-names to see what survived DCE:
   ```
   bb test :expr <name> :variant core-lite :build pseudo
   grep -oP '\$cljs\$core\$\w+\$\$' .out/<name>-core-lite-pseudo.js | sort -u
   ```
   Anything surprising in the list is worth tracing.

3. **Fix** — apply the relevant pattern from below, then go back to step 1.

## Known leak patterns

### Variadic arities → IndexedSeq + full seq chain
Clojure's variadic arity (`[a f x y & xs]`) emits a prototype assignment that wraps
`arguments` in `new IndexedSeq(args.slice(N), 0)`. Prototype assignments survive DCE
even when the variadic path is never called. This drags in `IndexedSeq`, `EmptyList`,
`seq`, `first`, `next`, `count`, `ES6Iterator`, `mix_collection_hash`, `imul` — roughly
2k brotli.

**Fix:** provide a fixed-arity-only drop-in in `core.lite` (same pattern as `update`,
`update-in`, `swap!`). Add to `:refer-clojure :exclude` and use `🪶/fn` in tests.

### Map literal with variable key → HashMapLite
`{variable-key value}` emits `HashMapLite.fromArrays(...)` at compile time (the compiler
can't infer the key type). This pulls in all HashMapLite infrastructure even if the
code path is rarely executed — prototype method bodies survive DCE.

**Fix:** use a JS-native structure where possible. For watch storage, `js/Map` is ideal:
`.set`, `.delete`, `.forEach` — no CLJS collection machinery, Closure has built-in
externs for `Map`.

### `reduce-kv` in a deftype method body
Protocol method implementations are prototype assignments (side effects) — they always
survive DCE. A `reduce-kv` call inside a method body keeps `reduce-kv` and its
`IKVReduce` infrastructure alive even when the method is never invoked at runtime.

**Fix:** use the native equivalent. For iterating a `js/Map`: `.forEach`.

## What's in `test.edn`

Each entry is `{:test-expr {<sym> {core-lite <expr> cljs-core <expr>}}}`.
Commented-out entries use `#_#_`. The `bb report` task runs all active entries.

`bb test :expr <sym> :variant <core-lite|cljs-core> :build <lite|pseudo|heavy>` compiles
one variant. Output goes to `.out/<sym>-<variant>-<build>.js`.

## Closure library baseline (~1.5k brotli)

Every build pays for `goog.typeOf`, `SafeUrl`, `SafeStyle`, and other goog.base
boilerplate. This is unavoidable. The floor for any build using goog infrastructure is
roughly 1.5k brotli.
