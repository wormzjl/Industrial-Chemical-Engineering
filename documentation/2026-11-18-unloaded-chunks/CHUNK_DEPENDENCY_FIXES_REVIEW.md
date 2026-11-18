# Five plant equipment chunk dependencies

The corrections apply to recorded plants on the batch branch. Each regression checks the chunk state as well as the equipment outcome. [Original findings](EQUIPMENT_CHUNK_DEPENDENCIES_REVIEW.md)

| Finding | Correction | Regression evidence |
|---|---|---|
| Exchanger and condenser external controls | Startup reconstructs real valve assignments from the saved assembly, registered side IDs and valve signal. A piping edit that invalidates the report clears the assignment even with the exchanger absent. | A process restart restores a metered bypass before the first dispatch. A remote pipe edit releases its real valve while the shell stays unloaded. |
| Water strainer | The engine counts face-connected water in loaded and saved terrain and rechecks registered intakes every 1,200 online ticks. The existing 100-block threshold applies across chunk borders. | One pool stays valid when its far bank unloads. Removing and replacing its hundredth water block closes and reopens the unloaded intake. A separate restart corrects a stale closed switch. |
| Remote column operation | The DCS lists wired saved column hosts and resolves their master, design and utility members from the same assembly used for local operation. Apply, Start and Stop follow the existing command and acknowledgement path. | A restored console lists a never-started unloaded column, applies setpoints, starts it with the recorded condenser, cooler and pumps, then stops it and receives the published stopped state. |
| Pollution blasts on single-block equipment | Mixed footprints run the vanilla block rays over generated terrain. Both loaded and sleeping devices enter the same registered removal and inventory-release path. The checkpoint records the destroyed identities until the saved blocks catch up. | Paired loaded and mixed footprints destroy exposed pipes and a filled tank, preserve protected and shielded equipment, and account for removed contents once. Reload and restart exercise cleanup and preservation of a replacement device. |
| Secondary equipment sockets | Binding a loaded secondary socket resolves both IDs from the saved structure and registry. The primary socket refreshes its local metadata when its chunk loads. | Loading only the secondary exchanger socket establishes its engine binding and leaves the primary chunk absent. |

## Qualification

- The affected selection passed 1,191 JUnit cases, 84 hold-gate cases and 23 equipment-gate cases (1,298 total). The fluid compatibility run included the complete saved-image corpus and both new rupture cases.
- All 140 required tests passed on the standard headless fluid GameTest server. Six exercise the five findings directly, including separate fully loaded and mixed-footprint blasts.
- A fresh two-process prepare/verify run passed. Before the first engine tick, the second process found the saved external bypass assigned with the exchanger and valve chunks absent. The intake recovered its 100-block pool from storage. On the tank chunk’s return, the retired identity disappeared and a replacement survived another unload/reload.

Fluid schema 33 appends the rupture records after earlier facts sections. Older saves start with an empty set of pending ruptures. The compatibility cases cover old corpus images, individual removal intent and multiple destroyed identities at one coordinate. The format row and corpus are on this worktree. [Saved formats](../reference/FORMATS.md)

Fresh assembly validation still needs access to the physical path when a checked report is missing. Foreign FE endpoints and Create rotation retain their existing live-world behavior. On a mixed blast footprint, IChemE reconciles registered equipment and its contents. Vanilla item drops for ordinary blocks and entity effects depend on a live world.
