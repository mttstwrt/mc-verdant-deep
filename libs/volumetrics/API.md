# Volumetrics, for this mod

The gas library (`mc-volumetric-effects-lib`, mod id `volumetrics`) as this mod builds and runs against it. This folder is
a Maven repository holding one build of it:

```
dev.syrval.volumetrics:volumetrics-api:0.1.0+mc1.21.1        jar, sources jar, POM: compiled against
dev.syrval.volumetrics:volumetrics-neoforge:0.1.0+mc1.21.1   jar, POM: the NeoForge mod, for the dev runs
dev.syrval.volumetrics:volumetrics-fabric:0.1.0+mc1.21.1     jar, POM: the Fabric mod, for the dev runs
```

Built from Volumetrics `main` at `51115e8` (VOL-096), from its 1.21.1 nodes. Where this file and the sources jar
disagree, the sources jar wins. The library's spec is the kit's `plan/research/vol-api-v2.md`.

## How this mod takes it

- The root `build.gradle` declares this folder as a repository for the group `dev.syrval.volumetrics` only.
- `common` compiles against the API jar with plain `compileOnly` (`volumetrics_api_version`). The jar is in Mojang's
  names, as this mod is written, so it is not given to a `mod*` configuration, which would remap it. Its classes ship in
  the library's own jar, never in this mod's.
- Each loader takes the library's loader jar with `modLocalRuntime` (`volumetrics_version`), so `runServer` and
  `runClient` run with it and nothing has to be copied into `run/mods`.
- The library is required on the server and the client: `depends` `~0.1.0` in `fabric.mod.json`, `required`
  `[0.1.0,0.2)` in `neoforge.mods.toml`. The API is 0.x, so a minor version may break it.

## What this mod uses

All of it from the server, in `compat/HoleGasRule` and `compat/HoleGasInputs`:

- `VolumetricsApi.owner("verdant_deep").registerPlacementRule(id, new HoleGasRule())` (`@Experimental`): a server rule.
  The library calls its placer (`start(level)`, null for a level with no ravine carve) for each chunk of a server level
  that loads, adds what it gives through `ChunkPlacements` scoped to that chunk, sends each input to the players tracking
  the chunk (placed fields to everyone in the level), ends it when the chunk unloads and calls the rule again when it
  loads again; this mod sends nothing, listens to no chunk event and holds no handle.
- `out.add(Source, Attachment.at(pos))` for each source: a medium, a shape relative to the anchor (`Cylinder` standing on
  it, `Sphere` centred on it), `Source.Rate`, and a `velocity` up for a `rise`. Equal sources are one instance, so a
  payload writes each kind once.
- `out.add(Force, ...)` with a `Toward` kind for the pull over each disc: anchored on the hole's axis (the line it pulls
  to), its region a `Cylinder` moved out over the disc, `media` the disc's gases, added for the chunk of the disc's
  middle.
- `out.add(PlacedField.drawn(haze, field), Attachment.at(Vec3.ZERO))` for the haze, added for the chunk of the hole's
  middle and written in world coordinates: `Mul(Inside(Cylinder(base, height, bottomRadius, topRadius), edge),
  Exponential over Altitude)` (or `Linear`, or `Constant`), the form the library draws in one step.
- `out.later(hole, then)` for a chunk whose hole is still being worked out off the server thread: the library calls
  `then` on the server thread once it is done, if the chunk is still loaded.
- Media are this mod's data, `data/verdant_deep/volumetrics/medium/*.json`, in the v2 format (`motion`, `optics`,
  `detail`). `/volumetrics tune medium verdant_deep:<id> <path> <value>` changes one live.

## Publishing a new build here

After the library's API changes. With the library's branch checked out in the kit (`mods/mc-volumetric-effects-lib`), from
the kit's root, into an empty folder first so no file of an earlier build stays:

```sh
W=<absolute path of this mod's tree>
rm -rf "$W/libs/volumetrics/dev"
bin/mcg mods/mc-volumetric-effects-lib :api:1.21.1:publishToMavenLocal :neoforge:1.21.1:publishToMavenLocal \
    :fabric:1.21.1:publishToMavenLocal "-Dmaven.repo.local=$W/libs/volumetrics"
```

`bin/mc-lib publish` publishes the library's old `volumetrics-common` jar only, which this mod no longer uses. Then change
the branch and date at the top of this file, and this file if what the mod uses changed.
