from PIL import Image, ImageDraw

FG = r"C:\Users\catsk\auto_walking\app\src\main\res\drawable\ic_launcher_foreground.png"
OUT = r"C:\Users\catsk\auto_walking\design\icon_preview.png"

BG_COLOR = (18, 43, 29, 255)  # matches ic_launcher_background.xml's #122B1D

fg = Image.open(FG).convert("RGBA")
size = fg.size[0]

def composite(mask_shape):
    bg = Image.new("RGBA", (size, size), BG_COLOR)
    bg.alpha_composite(fg)
    if mask_shape == "square":
        return bg
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    if mask_shape == "circle":
        d.ellipse([0, 0, size, size], fill=255)
    elif mask_shape == "squircle":
        # approximate squircle with rounded rect
        d.rounded_rectangle([0, 0, size, size], radius=size * 0.22, fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(bg, (0, 0), mask)
    return out

tiles = [composite("square"), composite("circle"), composite("squircle")]
gap = 24
canvas = Image.new("RGBA", (size * 3 + gap * 2, size), (240, 240, 240, 255))
for i, t in enumerate(tiles):
    canvas.paste(t, (i * (size + gap), 0), t)
canvas.convert("RGB").save(OUT)
print("saved", OUT)
