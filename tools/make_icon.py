r"""Makes the launcher icon: the kuma bear on an XP sky-and-hill background (adaptive icon layers),
plus assets/xp/icon.png for the page. Run: .venv\Scripts\python.exe apps\anki-xp\tools\make_icon.py"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
BEAR = ROOT.parents[1] / "tools/ankidroid-rwkv/icons/kuma_black_512.png"
S = 432  # 108 dp at xxxhdpi; the launcher crops to the middle 72 dp


def background():
    img = Image.new("RGB", (S, S))
    d = ImageDraw.Draw(img)
    top, low = (0x1f, 0x5f, 0xd0), (0x9c, 0xcd, 0xf7)
    for y in range(S):
        t = min(1, y / (S * .72))
        d.line([(0, y), (S, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(top, low)))
    clouds = Image.new("L", (S, S))
    c = ImageDraw.Draw(clouds)
    for box in [(40, 70, 230, 130), (150, 95, 330, 150), (250, 40, 420, 95)]:
        c.ellipse(box, fill=150)
    img.paste((255, 255, 255), mask=clouds.filter(ImageFilter.GaussianBlur(18)))
    hill = Image.new("L", (S, S))
    ImageDraw.Draw(hill).ellipse((-180, 285, 620, 760), fill=255)
    green = Image.new("RGB", (S, S))
    g = ImageDraw.Draw(green)
    for y in range(S):
        t = max(0, (y - 285) / (S - 285))
        g.line([(0, y), (S, y)], fill=(round(0x6c - 0x30 * t), round(0xc0 - 0x40 * t), round(0x3c - 0x18 * t)))
    img.paste(green, mask=hill.filter(ImageFilter.GaussianBlur(1.5)))
    return img


def foreground():
    bear = Image.open(BEAR).convert("RGBA").resize((215, 215), Image.LANCZOS)
    fg = Image.new("RGBA", (S, S))
    x, y = (S - 215) // 2, (S - 215) // 2 + 8
    shadow = Image.new("RGBA", (S, S))
    shadow.paste((0, 0, 0, 120), (x + 8, y + 10), bear.getchannel("A"))  # XP icons carry a soft drop shadow
    fg = Image.alpha_composite(fg, shadow.filter(ImageFilter.GaussianBlur(7)))
    fg.alpha_composite(bear, (x, y))
    return fg


bg, fg = background(), foreground()
out = ROOT / "src/main/res/drawable-nodpi"
out.mkdir(parents=True, exist_ok=True)
bg.save(out / "ic_launcher_background.png")
fg.save(out / "ic_launcher_foreground.png")
# The page's own icon: the 72 dp middle, rounded like an XP window corner.
full = Image.alpha_composite(bg.convert("RGBA"), fg).crop((72, 72, S - 72, S - 72)).resize((128, 128), Image.LANCZOS)
mask = Image.new("L", full.size)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, 127, 127), 24, fill=255)
full.putalpha(mask)
full.save(ROOT / "src/main/assets/xp/icon.png")
