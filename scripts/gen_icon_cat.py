import numpy as np
from scipy import ndimage
from PIL import Image

SRC = r"C:\Users\catsk\Downloads\Gemini_Generated_Image_sw8rb2sw8rb2sw8r.png"
CANVAS = 432

img = Image.open(SRC).convert("RGB")
arr = np.array(img).astype(float) / 255.0
r, g, b = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
maxc = np.max(arr, axis=2)
minc = np.min(arr, axis=2)
delta = maxc - minc
sat = np.where(maxc > 0, delta / np.where(maxc == 0, 1, maxc), 0)

hue = np.zeros_like(maxc)
mask_r = (maxc == r) & (delta > 0)
mask_g = (maxc == g) & (delta > 0)
mask_b = (maxc == b) & (delta > 0)
hue[mask_r] = (60 * (((g - b) / np.where(delta == 0, 1, delta)) % 6))[mask_r]
hue[mask_g] = (60 * (((b - r) / np.where(delta == 0, 1, delta)) + 2))[mask_g]
hue[mask_b] = (60 * (((r - g) / np.where(delta == 0, 1, delta)) + 4))[mask_b]

# The source is a full logo (circular ring + location-pin paw badge + cat on an exam
# table). Ring/pin/table are all grayscale line art; only the cat itself has real hue,
# so a warm-hue-or-green-eyes mask cleanly isolates it as the single largest blob.
warm = ((hue <= 55) | (hue >= 320)) & (sat > 0.08) & (maxc < 0.99)
green_eyes = (hue > 60) & (hue < 170) & (sat > 0.15)
catcolor = warm | green_eyes

labeled, num = ndimage.label(catcolor, structure=np.ones((3, 3)))
sizes = ndimage.sum(catcolor, labeled, range(1, num + 1))
largest = np.argmax(sizes) + 1
cat = labeled == largest

# Close small gaps, fill internal holes (eye pupils, paw-mark dark spots), then dilate
# slightly to recover the thin dark outline stroke that sits just outside the raw hue mask.
cat = ndimage.binary_closing(cat, structure=np.ones((9, 9)))
cat = ndimage.binary_fill_holes(cat)
cat = ndimage.binary_dilation(cat, structure=np.ones((5, 5)))

rgba = np.dstack([np.array(img), (cat * 255).astype(np.uint8)])
full_img = Image.fromarray(rgba, mode="RGBA")

pad = 25
ys, xs = np.where(cat)
bbox = (xs.min() - pad, ys.min() - pad, xs.max() + pad, ys.max() + pad)
trimmed = full_img.crop(bbox)
tw, th = trimmed.size
core_cx, core_cy = tw / 2, th / 2


def render(out_path, safe_fraction):
    target_max = int(CANVAS * safe_fraction)
    scale = target_max / max(tw, th)
    new_w, new_h = max(1, round(tw * scale)), max(1, round(th * scale))
    resized = trimmed.resize((new_w, new_h), Image.LANCZOS)

    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    x = round(CANVAS / 2 - core_cx * scale)
    y = round(CANVAS / 2 - core_cy * scale)
    canvas.paste(resized, (x, y), resized)
    canvas.save(out_path)
    print(f"{out_path}: safe_fraction={safe_fraction} paste_at=({x},{y}) size={resized.size}")


render(r"C:\Users\catsk\auto_walking\app\src\main\res\drawable\ic_launcher_foreground.png", 0.62)
render(r"C:\Users\catsk\auto_walking\app\src\main\res\drawable\ic_avatar_character.png", 0.95)
