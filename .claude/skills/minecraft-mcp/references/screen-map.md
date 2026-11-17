# Screen map: widget indices for `click_button_index`

From the 2026-09-24 transcript mining (`documentation/2026-09-24-minecraft-mcp-skill/transcript-mining/`)
and the 2026-10 tool folders. Vanilla 1.21.1 screens; indices shift if another mod adds buttons, so
read `get_screen_buttons` once per screen before relying on them.

| screen | index | widget |
|---|---|---|
| AccessibilityOnboardingScreen (fresh `run/mcp-client/` only) | 4 | Continue |
| TitleScreen | 0 | Singleplayer (opens CreateWorldScreen when there are no saves, SelectWorldScreen otherwise) |
| TitleScreen | 6 | Quit Game |
| SelectWorldScreen | 1 | Play Selected World (select the entry first: `click {x:400,y:135}` at 854x480 is the newest world) |
| SelectWorldScreen | 2 | Create New World |
| CreateWorldScreen, Game tab | 4 | world name `EditBox` (leave "New World") |
| CreateWorldScreen, Game tab | 5 | Game Mode: Survival -> Hardcore -> Creative (two clicks) |
| CreateWorldScreen, Game tab | 6 | Difficulty |
| CreateWorldScreen, Game tab | 7 | Allow Commands (Creative turns it on by itself; do not click) |
| CreateWorldScreen, World tab (`switch_tab {"index":1}`) | 3 | World Type (one click = Superflat) |
| CreateWorldScreen, World tab | 8 | Generate Structures (one click = off) |
| CreateWorldScreen | 1 / 2 | Create New World / Cancel |
| PauseScreen | 0 | Back to Game |
| PauseScreen | 5 | Options |
| PauseScreen | 6 | Open to LAN (not needed) |
| PauseScreen | 8 | Save and Quit to Title |
| Options | 6 | Video Settings (its entries are not indexable; change GUI scale in `options.txt`) |

IChemE screens change with the code; read the indices from `get_screen_buttons` and from the tool
folder of the batch that last drove the screen (for example `tools/custom-equipment-rendering-poc/README.md`
for the equipment constructor and its Paint page).

Superflat: the grass is y = -61, the player stands at y = -60, scenario blocks go at y = -60 or higher
(earlier batches used y = -59 with the aiming placements in `SKILL.md`).
