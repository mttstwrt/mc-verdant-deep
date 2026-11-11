# Volumetrics, for this mod

The gas library (`mc-volumetric-effects-lib`, mod id `volumetrics`) as this mod builds and runs against it. This folder is
a Maven repository holding one build of it:

```
dev.syrval.volumetrics:volumetrics-api:0.1.0+mc1.21.1        jar, sources jar, POM: compiled against
dev.syrval.volumetrics:volumetrics-neoforge:0.1.0+mc1.21.1   jar, POM: the NeoForge mod, for the dev runs
dev.syrval.volumetrics:volumetrics-fabric:0.1.0+mc1.21.1     jar, POM: the Fabric mod, for the dev runs
```

Published 2026-11-11 from the library's `agent/VOL-082-api-v2` branch (VOL-082 1g1), from its 1.21.1 nodes. Where this
file and the sources jar disagree, the sources jar wins; the library's spec is the kit's `plan/research/vol-api-v2.md`.

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

All of it from the server, in `compat/HoleGasPlacer` and `compat/HoleGasInputs`:

- `VolumetricsApi.owner("verdant_deep").server(level)`: where the server adds what every player near it sees. The
  library sends each input to the players tracking its chunk (the scope's chunk, for one scoped to a chunk), sends placed
  fields to everyone in the level, and takes them away again; this mod sends nothing of its own.
- `add(Source, Attachment.at(pos), Scope.chunk(chunk))` for each source: a medium, a shape relative to the anchor
  (`Cylinder` standing on it, `Sphere` centred on it), `Source.Rate`, and a `velocity` up for a `rise`. Equal sources are
  one instance, so a payload writes each kind once.
- `add(Force, ...)` with a `Toward` kind for the pull over each disc: anchored on the hole's axis (the line it pulls to),
  its region a `Cylinder` moved out over the disc, `media` the disc's gases, scoped to the chunk of the disc's middle.
- `add(PlacedField.drawn(haze, field), Attachment.at(Vec3.ZERO), Scope.chunk(middle))` for the haze, written in world
  coordinates: `Mul(Inside(Cylinder(base, height, bottomRadius, topRadius), edge), Exponential over Altitude)` (or
  `Linear`, or `Constant`), the form the library draws in one step.
- Handles are not kept: every input ends when its chunk unloads, and is added again when it loads.
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
