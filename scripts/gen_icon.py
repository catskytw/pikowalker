import numpy as np
from PIL import Image

SRC = r"C:\Users\catsk\auto_walking\design\app_icon_source.png"
CANVAS = 432

img = Image.open(SRC).convert("RGB")
arr = np.array(img).astype(np.int16)
r, g, b = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
ptp = np.maximum(np.maximum(r, g), b) - np.minimum(np.minimum(r, g), b)

# The source has a baked-in gray transparency-checkerboard (two near-equal-channel tones
# in the ~185-240 range) instead of real alpha. Key it out; keep near-white (eye highlights)
# and the blue-tinted motion-lines, which fall outside this band.
is_checker = (ptp <= 6) & (r >= 180) & (r <= 240)
alpha = np.where(is_checker, 0, 255).astype(np.uint8)
rgba = np.dstack([np.array(img), alpha])
full_img = Image.fromarray(rgba, mode="RGBA")

bbox_full = full_img.split()[3].getbbox()
trimmed = full_img.crop(bbox_full)

# The faint motion-lines trailing behind the character skew a plain bbox-center off to one
# side. Center on the saturated "core" body (orange/green) instead, ignoring the pale
# near-white lines and eye highlights when computing the anchor point.
is_core = (alpha > 0) & (ptp > 15)
core_ys, core_xs = np.nonzero(is_core)
core_bbox = (core_xs.min(), core_ys.min(), core_xs.max() + 1, core_ys.max() + 1)
core_cx = (core_bbox[0] + core_bbox[2]) / 2 - bbox_full[0]
core_cy = (core_bbox[1] + core_bbox[3]) / 2 - bbox_full[1]
tw, th = trimmed.size


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


# Adaptive-icon foreground: needs the ~62% safe-zone padding so OS-applied masks never clip it.
render(r"C:\Users\catsk\auto_walking\app\src\main\res\drawable\ic_launcher_foreground.png", 0.62)

# Map-marker avatar: composited onto our own circle with full control, so it can fill almost
# the entire badge with minimal padding.
render(r"C:\Users\catsk\auto_walking\app\src\main\res\drawable\ic_avatar_character.png", 0.95)
