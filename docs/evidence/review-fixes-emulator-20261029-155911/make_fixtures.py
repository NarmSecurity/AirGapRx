"""Synthetic regression inputs; existing evidence is never replaced."""
from io import BytesIO
from pathlib import Path
import random
import zipfile
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).parent
font = "C:/Windows/Fonts/arial.ttf"
fixtures = {
    "split-contains-label.png": [
        (70, 50, "SYNTHETIC TEST LABEL", 28),
        (70, 210, "Each tablet contains:", 50),
        (70, 330, "Paracetamol 500 mg", 56),
        (70, 740, "Warnings: Do not take with a product that", 45),
        (70, 1100, "contains warfarin.", 52),
    ],
    "declared-actives-label.png": [
        (70, 50, "SYNTHETIC TEST LABEL", 28),
        (70, 200, "Active ingredients:", 54),
        (70, 350, "Calcium hydrogen phosphate 500 mg", 50),
        (70, 470, "Paracetamol 500 mg", 54),
        (70, 1000, "Other ingredients: magnesium stearate", 44),
    ],
}
for name, lines in fixtures.items():
    path = root / name
    if path.exists():
        raise FileExistsError(path)
    image = Image.new("RGB", (1400, 1500), "white")
    draw = ImageDraw.Draw(image)
    for x, y, text, size in lines:
        draw.text((x, y), text, font=ImageFont.truetype(font, size), fill="black")
    image.save(path)

archive = BytesIO()
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("kb.sqlite", random.Random(1).randbytes(4096))
for name, content in {
    "corrupt-update.zip": archive.getvalue()[: len(archive.getvalue()) // 2],
    "empty-model.task": b"",
}.items():
    path = root / name
    if path.exists():
        raise FileExistsError(path)
    path.write_bytes(content)
print(root)
