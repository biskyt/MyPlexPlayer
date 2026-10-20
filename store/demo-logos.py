"""Original logos for the invented demo channels in the store screenshots (drawn here, so no third-party rights).

Run from the repository root: python3 store/demo-logos.py
"""
import math, sys
from PIL import Image, ImageDraw, ImageFont
FONT = "app/src/main/res/font/nunito.ttf"
OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/test/resources/demo-art"
W, H = 360, 200
S = 4  # supersample

def font(size, weight="Black"):
    f = ImageFont.truetype(FONT, size * S)
    try: f.set_variation_by_name(weight)
    except Exception: pass
    return f

def canvas():
    im = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
    return im, ImageDraw.Draw(im)

def done(im, name):
    im.resize((W, H), Image.LANCZOS).save(f"{OUT}/{name}.png")

def text(d, xy, s, size, fill, weight="Black", anchor="lm"):
    d.text((xy[0] * S, xy[1] * S), s, font=font(size, weight), fill=fill, anchor=anchor)

def rr(d, box, r, fill):
    d.rounded_rectangle([v * S for v in box], r * S, fill=fill)

def ell(d, box, fill=None, outline=None, width=1):
    d.ellipse([v * S for v in box], fill=fill, outline=outline, width=width * S)

WHITE = (255, 255, 255, 255)

# Riverside One HD: blue tile with a river, a big 1, and the name.
im, d = canvas()
rr(d, (20, 30, 160, 170), 28, (37, 99, 235, 255))
for y in (118, 138):
    pts = [((40 + t) * S, (y + 7 * math.sin(t / 12)) * S) for t in range(0, 101, 2)]
    d.line(pts, fill=(147, 197, 253, 255), width=7 * S, joint="curve")
text(d, (90, 80), "1", 84, WHITE, anchor="mm")
text(d, (176, 82), "RIVER", 40, WHITE)
text(d, (176, 124), "SIDE", 40, (147, 197, 253, 255))
done(im, "one")

# Northbridge News: red bar with a bridge arch.
im, d = canvas()
rr(d, (14, 48, 346, 152), 18, (220, 38, 38, 255))
d.arc([40 * S, 66 * S, 120 * S, 150 * S], 180, 360, fill=WHITE, width=8 * S)
for x in (52, 66, 80, 94, 108):
    d.line([(x * S, 108 * S), (x * S, 132 * S)], fill=WHITE, width=4 * S)
d.line([(34 * S, 132 * S), (126 * S, 132 * S)], fill=WHITE, width=6 * S)
text(d, (140, 100), "NEWS", 64, WHITE)
done(im, "news")

# Harbour Sport 1 and 2: orange roundel with a buoy stripe and the number.
for n in (1, 2):
    im, d = canvas()
    ell(d, (14, 24, 166, 176), fill=(249, 115, 22, 255))
    ell(d, (34, 44, 146, 156), fill=(17, 24, 39, 255))
    text(d, (90, 100), str(n), 76, (249, 115, 22, 255), anchor="mm")
    text(d, (180, 78), "HARBOUR", 34, WHITE)
    text(d, (180, 122), "SPORT", 46, (249, 115, 22, 255))
    done(im, f"sport{n}")

# Little Sky Kids: a sun and a cloud, rounded lower-case name.
im, d = canvas()
ell(d, (70, 20, 150, 100), fill=(250, 204, 21, 255))
for cx, cy, r in ((60, 110, 40), (110, 96, 46), (160, 114, 36)):
    ell(d, (cx - r, cy - r, cx + r, cy + r), fill=(125, 211, 252, 255))
rr(d, (20, 110, 196, 150), 20, (125, 211, 252, 255))
text(d, (204, 86), "little", 40, (250, 204, 21, 255), weight="ExtraBold")
text(d, (204, 132), "sky", 52, (125, 211, 252, 255))
done(im, "kids")

# Lantern Drama: a glowing lantern and a serif-like name.
im, d = canvas()
ell(d, (14, 24, 142, 176), fill=(168, 85, 247, 70))
rr(d, (52, 50, 104, 150), 14, (250, 204, 21, 255))
rr(d, (46, 40, 110, 56), 6, (88, 28, 135, 255))
rr(d, (46, 146, 110, 162), 6, (88, 28, 135, 255))
d.arc([60 * S, 14 * S, 96 * S, 52 * S], 180, 360, fill=(88, 28, 135, 255), width=5 * S)
text(d, (156, 84), "LANTERN", 42, WHITE)
text(d, (158, 128), "drama", 36, (216, 180, 254, 255), weight="Bold")
done(im, "drama")

# Atlas Documentary: a green globe with meridians.
im, d = canvas()
ell(d, (20, 30, 160, 170), fill=(22, 163, 74, 255))
for w in (70, 34):
    ell(d, (90 - w, 30, 90 + w, 170), outline=(187, 247, 208, 255), width=4)
for y in (64, 100, 136):
    half = math.sqrt(max(0, 70 ** 2 - (y - 100) ** 2))
    d.line([((90 - half) * S, y * S), ((90 + half) * S, y * S)], fill=(187, 247, 208, 255), width=4 * S)
text(d, (176, 84), "ATLAS", 54, WHITE)
text(d, (178, 130), "DOCUMENTARY", 22, (134, 239, 172, 255), weight="ExtraBold")
done(im, "docs")

# Meadow Comedy: a daisy and a playful name.
im, d = canvas()
for k in range(8):
    a = k * math.pi / 4
    cx, cy = 90 + 44 * math.cos(a), 100 + 44 * math.sin(a)
    ell(d, (cx - 24, cy - 24, cx + 24, cy + 24), fill=WHITE)
ell(d, (64, 74, 116, 126), fill=(234, 179, 8, 255))
text(d, (172, 84), "meadow", 46, (163, 230, 53, 255))
text(d, (174, 130), "COMEDY", 30, WHITE)
done(im, "comedy")
