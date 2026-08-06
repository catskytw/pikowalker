from PIL import Image, ImageDraw

CHAR = r"C:\Users\catsk\auto_walking\app\src\main\res\drawable\ic_avatar_character.png"
OUT = r"C:\Users\catsk\auto_walking\design\avatar_preview.png"

SIZE = 260
BORDER = SIZE // 26
BG_COLOR = (27, 124, 85, 255)  # #1B7C55

canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
d = ImageDraw.Draw(canvas)
d.ellipse([0, 0, SIZE, SIZE], fill=BG_COLOR)
d.ellipse([BORDER, BORDER, SIZE - BORDER, SIZE - BORDER], outline=(255, 255, 255, 255), width=BORDER)

char = Image.open(CHAR).convert("RGBA")
dest = int(SIZE * 0.9)
char = char.resize((dest, dest), Image.LANCZOS)
offset = (SIZE - dest) // 2
canvas.alpha_composite(char, (offset, offset))

bg = Image.new("RGB", (SIZE, SIZE), (235, 235, 235))
bg.paste(canvas, (0, 0), canvas)
bg.save(OUT)
print("saved", OUT)
