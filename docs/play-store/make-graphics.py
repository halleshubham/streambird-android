from PIL import Image, ImageDraw, ImageFont, ImageFilter
import sys
R = sys.argv[1]
OUT = sys.argv[2]
bird = Image.open(f"{R}/drawable-nodpi/logo_bird.png").convert("RGBA")

# --- 512x512 hi-res icon: bird on the launcher background colour (#F5F3FF), full square (Play applies its own mask)
icon = Image.new("RGBA", (512, 512), (0xF5, 0xF3, 0xFF, 255))
b = bird.copy(); b.thumbnail((400, 400), Image.LANCZOS)
icon.alpha_composite(b, ((512 - b.width) // 2, (512 - b.height) // 2))
icon.convert("RGB").save(f"{OUT}/streambird-icon-512.png", optimize=True)

# --- 1024x500 feature graphic
W, H = 1024, 500
base = Image.new("RGB", (W, H))
px = base.load()
c1, c2 = (0x4c, 0x1d, 0x95), (0x7c, 0x3a, 0xed)   # deep violet -> brand #7c3aed
for y in range(H):
    for x in range(W):
        t = (x / W * 0.7 + y / H * 0.3)
        px[x, y] = tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))
img = base.convert("RGBA")
# soft circle behind the bird
glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
ImageDraw.Draw(glow).ellipse((630, 30, 1010, 410), fill=(255, 255, 255, 40))
glow = glow.filter(ImageFilter.GaussianBlur(8))
img.alpha_composite(glow)
bb = bird.copy(); bb.thumbnail((300, 300), Image.LANCZOS)
tile = Image.new("RGBA", (320, 320), (0xF5, 0xF3, 0xFF, 255))
m = Image.new("L", (320, 320), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, 319, 319), 66, fill=255)
tile.alpha_composite(bb, ((320 - bb.width) // 2, (320 - bb.height) // 2))
img.paste(tile, (655, 95), m)

d = ImageDraw.Draw(img)
bold = "/usr/share/fonts/opentype/inter/Inter-Bold.otf"
reg = "/usr/share/fonts/opentype/inter/Inter-Regular.otf"
try:
    f1 = ImageFont.truetype(bold, 62); f2 = ImageFont.truetype(reg, 30); f3 = ImageFont.truetype(bold, 26)
except Exception:
    f1 = f2 = f3 = ImageFont.load_default()
d.text((60, 125), "StreamBird", font=f1, fill="white")
d.text((60, 205), "Go live everywhere", font=f1, fill=(0xE9, 0xD5, 0xFF))
d.text((60, 320), "Stream from your phone to YouTube,", font=f2, fill="white")
d.text((60, 362), "Facebook and Twitch at once.", font=f2, fill="white")
# red LIVE pill
d.rounded_rectangle((60, 55, 160, 95), 20, fill=(0xDC, 0x26, 0x26))
d.ellipse((76, 69, 88, 81), fill="white")
d.text((96, 62), "LIVE", font=f3, fill="white")
img.convert("RGB").save(f"{OUT}/streambird-feature-graphic-1024x500.png", optimize=True)
print("ok")
