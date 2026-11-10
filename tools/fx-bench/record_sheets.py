"""GIFs and contact sheets of an effects recording (batch 2026-11-10-custom-shader-system; tools/fx-bench/record.sh).

Usage: python record_sheets.py <recording dir>
For each shot folder (f000.png ...): <shot>.gif at the recorded rate (times.txt), and <shot>-sheet.png, eight evenly spaced
frames in a 4 x 2 grid at a quarter of the frame size each, labelled with their time. Needs Pillow.
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw


def times(folder):
    out = {}
    path = folder / 'times.txt'
    if path.exists():
        for line in path.read_text().split('\n'):
            parts = line.split()
            if len(parts) == 2:
                out[int(parts[0])] = float(parts[1])
    return out


def main(root):
    for folder in sorted(p for p in root.iterdir() if p.is_dir()):
        frames = sorted(folder.glob('f[0-9][0-9][0-9].png'))
        if not frames:
            continue
        at = times(folder)
        images = [Image.open(f).convert('RGB') for f in frames]
        span = (at.get(len(frames) - 1, 0) - at.get(0, 0)) if len(frames) > 1 else 0
        step = int(round(1000 * span / (len(frames) - 1))) if len(frames) > 1 and span > 0 else 100
        gif = [im.resize((im.width * 3 // 4, im.height * 3 // 4)).quantize(colors=255, method=Image.Quantize.MEDIANCUT) for im in images]
        gif[0].save(root / f'{folder.name}.gif', save_all=True, append_images=gif[1:], duration=step, loop=0, optimize=True)
        picks = [images[round(i * (len(images) - 1) / 7)] for i in range(8)] if len(images) >= 8 else images
        cw, ch = images[0].width // 2, images[0].height // 2
        sheet = Image.new('RGB', (cw * 4, ch * ((len(picks) + 3) // 4)), 'black')
        draw = ImageDraw.Draw(sheet)
        for i, im in enumerate(picks):
            x, y = (i % 4) * cw, (i // 4) * ch
            sheet.paste(im.resize((cw, ch)), (x, y))
            n = images.index(im)
            draw.text((x + 6, y + 6), f'{folder.name} t={at.get(n, n / 10):.1f}s', fill='yellow')
        sheet.save(root / f'{folder.name}-sheet.png')
        print(folder.name, len(frames), 'frames', f'{step} ms/frame')


if __name__ == '__main__':
    main(Path(sys.argv[1]))
