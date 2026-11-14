# One line per screen: block counts on both sides and the worst difference (dp), sampling
# every pixel. usage: python3 summary.py [web-dir ...]   (default: every web*/ directory)
import os, sys, glob
from PIL import Image, ImageChops
ref = os.path.dirname(os.path.abspath(__file__))
nat = os.path.join(ref, '..', '..', 'app', 'build', 'screens')

def blocks(path):
    im = Image.open(path).convert('RGB'); w, h = im.size
    bg = im.getpixel((4, h // 2))
    diff = ImageChops.difference(im, Image.new('RGB', im.size, bg)).crop((8, 0, w - 8, h))
    r, g, b = diff.split()
    ink = ImageChops.lighter(ImageChops.lighter(r, g), b).point(lambda v: 255 if v > 40 else 0)
    # mean of each row = share of ink pixels in it
    rows = list(ink.resize((1, h), Image.BOX).getdata())
    on = [v * (w - 16) / 255 > 3 for v in rows]
    out, start = [], None
    for y, flag in enumerate(on):
        if flag and start is None: start = y
        if not flag and start is not None: out.append((start / 2, y / 2)); start = None
    if start is not None: out.append((start / 2, h / 2))
    merged = []
    for b_ in out:
        if merged and b_[0] - merged[-1][1] < 3: merged[-1] = (merged[-1][0], b_[1])
        else: merged.append(b_)
    return merged

dirs = sys.argv[1:] or sorted(d for d in os.listdir(ref) if d.startswith('web') and os.path.isdir(os.path.join(ref, d)))
bad = 0
for d in dirs:
    for path in sorted(glob.glob(os.path.join(ref, d, '*.png'))):
        name = os.path.basename(path)[:-4]
        other = os.path.join(nat, name + '.png')
        if not os.path.exists(other): print(f'{d:15} {name:28} (no native shot)'); continue
        a, b = blocks(path), blocks(other)
        if len(a) != len(b): worst = None
        else: worst = max([0] + [max(abs(y[0] - x[0]), abs((y[1] - y[0]) - (x[1] - x[0]))) for x, y in zip(a, b)])
        flag = '' if worst is not None and worst <= 1.5 else '  <<<'
        bad += bool(flag)
        print(f'{d:15} {name:28} blocks {len(a):3}/{len(b):<3} worst {"-" if worst is None else worst}{flag}')
print('screens outside ±1.5dp or with different block counts:', bad)
