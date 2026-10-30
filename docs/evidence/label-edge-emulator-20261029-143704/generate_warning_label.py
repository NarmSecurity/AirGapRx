"""Render a synthetic OCR fixture; never overwrite an existing evidence image."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

output = Path(__file__).with_name("warning-continuation-label.png")
if output.exists():
    raise FileExistsError(output)

image = Image.new("RGB", (1200, 1500), "white")
draw = ImageDraw.Draw(image)
font_path = "C:/Windows/Fonts/arial.ttf"
lines = [
    (90, 80, "SYNTHETIC TEST LABEL", 28),
    (90, 180, "MOOD SUPPORT", 64),
    (90, 390, "Each tablet contains:", 50),
    (90, 490, "St John's wort 300 mg", 55),
    (90, 790, "Warnings:", 55),
    (90, 885, "Do not take with warfarin.", 50),
    (90, 1160, "Or aspirin.", 50),
]
for x, y, text, size in lines:
    draw.text((x, y), text, fill="black", font=ImageFont.truetype(font_path, size))
image.save(output)
print(output)
