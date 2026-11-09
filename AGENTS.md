# AGENTS.md: Shared contributor rules for the owner's mods

The kit's `AGENTS.md` (the `mc-dev-kit` root) takes precedence over this file: its rules on design priorities,
performance, verification, tests, branches and approvals apply wherever this file differs. This file adds code
standards for the owner's mods. A project may add its own rules; they must not contradict either file.
Project specs (`0N-*.md`, `README.md`, the kit's `plan/projects/<project>/`) say *what* to build.

## 1. Guiding principles

1. **No speculative structure.** Within the kit's long-term and performance priorities, prefer the simpler of
   two designs. No framework-building or generic "manager" classes; add an abstraction when it provides a
   concrete seam or lifecycle responsibility.
2. **Tunables in config files.** Values the owner adjusts by eye go in a file under the instance's `config/`
   folder, with an op command to set, save and re-apply them live; not in Java constants or world datapacks.
   Worldgen defaults may stay as data in the jar.
3. **Small surface area.** Every mixin, access widener/transformer, reflection call and loader hook is a
   maintenance cost on every Minecraft update. Justify each one in a comment and keep it minimal.
4. **Portability is a design constraint, not a later task.** See section 3.

## 2. Code standards

**Language and style**
- Java 21 for 1.21.x (check the pinned version in `docs/PINS.md`; do not guess). Use modern features where they
  simplify: `record`, `sealed` interfaces, pattern matching, `switch` expressions, text blocks, `var` for obvious
  types, `Optional` for return values (never fields or parameters).
- Prefer immutability: `final` fields, records for data, unmodifiable collections. On hot paths the kit's
  performance rules come first, for example no per-call allocation in per-block or per-tick loops.
- No wildcard imports, no dead code, no commented-out code, no unused imports. Fix compiler warnings.
- Names say what a thing is. Methods are short and do one thing. Prefer early returns over nesting.
- Comments explain *why* (a Mojang quirk, a version workaround), never *what*. Match the density of surrounding code.
- Use Mojang official mappings everywhere. Do not mix mapping sets in shared code.
- Logging via SLF4J (`LogUtils.getLogger()`), never `System.out`.

**Object-oriented design**
- Single responsibility per class; depend on interfaces at boundaries (platform, config), concrete types inside.
- Composition over inheritance. Inherit only from Minecraft types you are required to extend.
- Encapsulate: private fields, narrow public API, package-private by default.
- Make illegal states unrepresentable (records with validating compact constructors, enums, sealed types).
- Dependency injection by constructor or parameter. No global mutable state; no singletons except the
  platform-service lookup in section 3.
- Define worldgen data types as records with a `MapCodec`/`Codec`. The codec *is* the schema: keep it in
  one place next to the record.

**Worldgen specifics**
- **Thread safety:** chunk generation is multithreaded. Anything reachable from worldgen must be immutable or
  safely concurrent. No unsynchronised caches, no `static` mutable fields, no world/level access that is not
  a worldgen-safe accessor.
- **Determinism:** all randomness derives from the world seed and coordinates (plus the project's salt).
  Never `new Random()`, `Math.random()` or time.
- Do not override vanilla terrain files (`noise_settings`, density functions, `dimension_type`); wrap or patch
  at runtime. Vanilla blocks only unless the spec says otherwise.
- Fresh worlds only. Servers need no client-side install unless the spec says so.

**Testing and verification**
- Follow the kit: build the affected targets and start the runtimes the change needs; the owner tests in game.
- Add unit tests only when requested. Existing tests cover a handful of invariants that tuning never changes and
  need no Minecraft bootstrap where possible; do not add JUnit tests for tuned behaviour.
- Say plainly when something is untested.

## 3. Cross-version and cross-loader portability

**Target matrix:** NeoForge and Fabric, on every Minecraft version the project supports (see the kit's
`AGENTS.md` for which targets to build and start). Treat every Minecraft bump as expected.

**Module layout** (same as `Architects_Toolbox`):
```
common/    all game logic, codecs, worldgen types, shared mixins, resources (datapack JSON)
fabric/    entrypoint + loader glue only
neoforge/  entrypoint + loader glue only
```
- Platform modules are thin: target ~100 lines or fewer each. If logic is creeping in, move it to `common`.
- `common` must **never** import `net.fabricmc.*` or `net.neoforged.*`. Enforce this in the build
  (compile `common` without loader dependencies, so a violation fails to compile).
- Reach loader features through a small interface in `common` (for example `Platform`, `RegistryHelper`,
  `BiomeInjector`) with one implementation per loader, found via `ServiceLoader`. Keep these interfaces
  tiny and purpose-named, not a mirror of a loader API.
- Shared mixins live in `common` (Mixin is the same on both loaders). Loader-specific mixins are a last resort.
- Data differences between loaders belong in the loader module's resources, not in conditionals:
  - NeoForge biome modifiers: `data/<ns>/neoforge/biome_modifier/`. Fabric has no datapack equivalent, so use
    `BiomeModifications` in code, driven by the same placed-feature IDs.
  - Use tags that exist on both loaders (`c:` convention tags, or your own namespaced tags). Do not rely on
    loader-only tags.
  - Conditional loading goes through each loader's mechanism in its own module.
- Registries: register through the platform layer; never hold direct references to registry objects before
  registration completes.

**Version-sensitive code** (anything Mojang renames or reformats between releases):
- Isolate it. Put code that touches unstable Minecraft internals (`NoiseChunk`, `Aquifer`/fluid picker, `Structure`
  placement internals, rendering) in one clearly named package such as `…compat` or `…mc`, wrapped by a small
  project-owned interface. Business logic must call the interface, not the internals.
- Datapack JSON formats change between versions (folder names, field names, registry formats). Keep the version
  in `docs/PINS.md` and note which files are version-specific.
- When a Minecraft update breaks something, fix it in the compat layer. Do not scatter version `if`s through logic.
- Support several Minecraft versions with a multi-version tool (Stonecutter, see the kit's skill) rather than
  copied branches or source trees.
- Pin exact versions of Minecraft, loaders, mappings, Java and every library in `gradle.properties` plus
  `docs/PINS.md`. No `+` or `latest` ranges.

**Dependencies**
- **Allowed:** widely used common libraries and APIs (for example Architectury API, Fabric API, Cloth Config,
  worldgen helper libraries such as Lithostitched) when they remove real loader glue or boilerplate. Prefer one
  well-maintained library over hand-rolled platform code, and prefer fewer libraries over more.
- **Forbidden as hard dependencies:** terrain, biome, or content mods (for example Larion, TerraBlender-based
  biome packs, Biomes O' Plenty) and sibling content mods. These may be *test targets* and may be supported
  through optional, soft integration, but the mod must load and work without them.
- Record each library (purpose, version, why it was chosen over writing the code) in `docs/PINS.md`.
  `common` may use a library only if it is available on both loaders for the pinned version.
- Build-time-only tools (Gradle plugins, JUnit) are fine.
- Code shared between projects belongs in the owner's library that owns it (kit library-first skill), consumed
  from a Maven repository. Whether a mod requires a library or integrates with it optionally is the project's
  decision.

## 4. Working agreements

- Read the project spec and `docs/PINS.md` before changing code. If the spec conflicts with these rules or the
  kit's, stop and ask; do not silently pick.
- Items marked `[recall; verify]` in specs are unverified memory. Verify against the pinned version's real
  files (use the game's data generator and decompiled sources) before building on them.
- Keep changes small and reviewable. One concern per commit. Do not commit generated output, run directories
  or build products.
- Do not invent APIs. If a class or method is not found in the pinned version, look it up; do not assume.
- Report honestly: say what was run, what passed, what was not tested.
