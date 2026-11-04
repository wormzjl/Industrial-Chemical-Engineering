# Industrial Chemical Engineering (IChemE)

## Intent

Industrial Chemical Engineering (IChemE, formerly CreateChemE) is a Minecraft 1.21.1 NeoForge mod that aims to turn chemical and petroleum processing into playable factory systems. Its scientific model is intended to be lightweight enough for real-time game simulation while retaining consistent component, mass, element, phase, and energy balances.

The planned material system combines real chemical species with petroleum pseudocomponents, phase equilibrium, heat transfer, fluid transport, reaction kinetics, catalyst state, and conservative coupling between reaction and separation processes. Public thermodynamic and process data will provide the scientific basis, with clearly identified gameplay approximations where industrial data are unavailable.

Planned equipment includes storage drums, pumps, compressors, heat exchangers, boilers, furnaces, generic reactors, gas-liquid separators, three-phase separators, air coolers, distillation columns, pressure-swing adsorption units, and stirred-tank reactors. The mod needs no other mod. Its machines will run on their own drivers (electric motors on FE, steam turbines) joined by shafts; Create, when installed, will be an optional power source that turns those shafts through a coupling. JEI and KubeJS, also optional, will support discovery and configurable content.

## Current status

Version 0.14.0, early development (see [CHANGELOG.md](CHANGELOG.md)). From 0.13.0 a world keeps its fluid network and placed equipment across updates, and from 0.13.1 its installed gauges and pipe fittings. A world saved by 0.12.4 or older resets its fluid network once: inventories, controls and pending fluid events are discarded, and placed devices rebuild on chunk load. Column calculator blocks lose their input when their format changes.

- **Distillation column calculator:** solves a crude column with side draws, steam and pumparounds.
- **Fluid network:** pipes, tanks, pumps, compressors and valves with multiphase flow and equilibrium.
- **Pipe laying tool and pipe racks:** lays a whole pipe line along a searched route between two clicks, on layered pipe racks where they stand, in a primary and a secondary paint.
- **Equipment constructor:** builds drums, columns and process units in the world; drums join the fluid network.

Not implemented yet: reactions, and process models for the other units.

## Building and verification

Build the mod with Java 21 using the Gradle wrapper:

```text
./gradlew build
```

The build runs every test, including the slow gates. The wrapper can provision the Java 21 toolchain. Mod jars are written to `build/libs/`.

Run the quick test suite (about a minute) with:

```text
./gradlew test
```

Run the slow gate tests as well, as before a merge, with:

```text
./gradlew test slowGates
```

Runtime sources, resources, the Gradle wrapper, regression tests and their fixtures are tracked. The bundled Transformer weights and their model card under `src/main/resources/data/icheme/neural/` are required runtime/provenance assets and stay in the repository. The server defaults to learned-first initialization with classical fallback; experimental models are not required to build or run the mod.

Research datasets, retired models, training/evaluation tools, branch handoffs and standalone timing/memory diagnostics are local, ignored files. Existing local research tasks can be loaded from `tools/development.gradle` when that file is present. A fresh clone builds and tests without those files. Scientific regression fixtures under `src/test/resources/` remain tracked.
