# Tutorial photos: review

## Sources

All retrieved from Wikimedia Commons on 2026-11-18 as 1280 px copies; source page `https://commons.wikimedia.org/wiki/File:<title with _ for spaces>`. CC0: https://creativecommons.org/publicdomain/zero/1.0/. CC BY 2.0: https://creativecommons.org/licenses/by/2.0/. Every photo was looked at whole at 1280 px: no person seen.

| Id | Chapter | Title | Author, date | Licence | Edits beyond scale and colours |
|---|---|---|---|---|---|
| welcome | welcome | Industrial-720706 640.jpg | Carol M. Highsmith, 2014 (Library of Congress, highsm.29635) | Public domain | none |
| crude | crude | Petroleum sample.jpg | Nefronus, 2020 | CC0 | none |
| crude_gas | crude, gas | Gas flame.jpg | George Shuklin, 2007 | Public domain (author's release) | none |
| crude_naphtha | crude, naphtha | Gasoline in mason jar.jpg | Burger, 2008 | Public domain (author's release) | none |
| crude_kerosene | crude, kerosene | Kerosene in mason jar.JPG | Burger, 2008 | Public domain (author's release) | none |
| crude_diesel | crude, diesel | Diesel in mason jar.JPG | Burger, 2008 | Public domain (author's release) | none |
| crude_gas_oil | crude, gas oil | Petro motor oil.jpg | Burger, 2008 | Public domain (author's release) | none; a stand-in, motor oil made from gas oil (the caption says so) |
| crude_residue | crude, residue | Bitumen.jpg | Daniel Tzvi, 2007 | Public domain (author's release) | none; a stand-in, natural bitumen (the caption says so) |
| still | still | Woodford Reserve Distillery-27527-3.jpg | Ken Thomas, 2013 | Public domain (author's release) | cropped to x 712 to 1280, full height: the wall's sign ends near x 710 and the dark shape at the bottom left is out; the leftmost still's bulb loses a few pixels at its left |
| tower | tower | Een gedeelte van de raffinaderij, Bestanddeelnr 921-7111.jpg | Jack de Nijs / Anefo, 1968 (Nationaal Archief) | CC0 | none |
| simulator | simulator | Chiller plant SCADA system.jpg | Rsparks3, 2024 | CC0 | none |
| unit | unit | Crude-oil pipe stills, rundown tanks, and "Cat Crackers" at the Baton Rouge Esso Refinery, ca. 1945 - NARA - 535733.jpg | unknown, about 1945 (US National Archives 535733) | Public domain (US government) | none |
| building | building | Pueblo Chemical Agent - Jacketed vessel.jpg | PEO ACWA (US Army), 2011, https://www.flickr.com/photos/acwa/6240399099/ | CC BY 2.0 | none: not cropped, so its caption ends "Photo: PEO ACWA, CC BY 2.0." |
| start | start | Gas flare on top of a flare stack at Preemraff Lysekil 2.jpg | W.carter, 2018 | CC0 | none |

## Sizes

`NativeImage.read` takes PNG only, so the photos are indexed PNGs (256 colours, median cut, Floyd-Steinberg), drawn through `Canvas.sprite` (`GuiCanvas.blit`, linear filter). Totals of the 14 at each longest side, against a budget of 150 KB each and 1.5 MB in all:

| Longest side | Total | Largest | Over 150 KB |
|---|---|---|---|
| 512 px | 2.38 MB | tower 218 KB | 10 files |
| 448 px | 1.84 MB | tower 167 KB | 3 files (tower, crude_gas, crude) |
| 384 px (shipped) | 1.35 MB | tower 125 KB | none |

Why not 448: the photos' own grain (film grain, sensor noise, JPEG blocks) is what deflate cannot pack; damping the dither's error to .75 or .5 saved under 2 %. The owner chose 384 px (option A of three: 384 px; 448 px with a raised budget; 512 px with 128 colours). The viewer shows a photo at most about 180 GUI px tall at 464 by 254, so 384 px is still about twice the screen size at GUI scale 1.

Shipped (px, bytes): welcome 384x256 90050; crude 384x383 116210; crude_gas 384x344 119144; crude_naphtha 288x384 104893; crude_kerosene 288x384 107033; crude_diesel 293x384 92781; crude_gas_oil 288x384 92105; crude_residue 384x198 76469; still 255x384 83246; tower 384x384 124533; simulator 384x288 94588; unit 295x384 96523; building 288x384 78616; start 256x384 73551.

## Layout (option B)

Numbers from the prototype that drew the owner's sheets: thumbnail 64 by 48 at the glass's (3, 3), cut round the photo's focus; the caption beside it in the muted colour where its room is at least 100 px (so not at 312 by 232); View at least 48 wide at the glass's right; the steps 8 px under the row. Viewer: option keys flowing across the top (word plus 8 px), the photo whole and centred with a muted outline, the caption at the foot left, Back at the foot right; no scroll bar.

## Deviations from the brief

- 384 px instead of 512 or 448 (budget, owner decision above).
- A caption may count three sentences in `TutorialTextTest`: the initial in "Carol M. Highsmith" reads as a sentence end.
- No third-party data notice ships in `src/main/resources` (only EJML's licence files), so no pointer line was added; `FORMATS.md` lists no tutorial asset, so it is unchanged.
- The test run took the lane's selection (201 classes of the `test` task, `LanguageFilesTest` and `UnitTextTest` left out) as `--tests` arguments plus the lane's filter file for its excluded slow methods, through Git Bash (cmd's line limit).

## Not verified

- Nothing is checked in a dev client: the game's texture filter on a 384 px photo drawn at about 64 to 260 GUI px, and the real font's wrap, are judged only on the software renders.
- The truck cabs in `welcome` are too small at 1280 px to see whether anyone sits in them.
- Licences were taken from the Commons file pages as retrieved on 2026-11-18; no upstream source (Library of Congress, NARA, Flickr) was re-checked.
