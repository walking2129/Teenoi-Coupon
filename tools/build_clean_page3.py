import base64
import io
import re
from pathlib import Path

import cv2
import numpy as np
from PIL import Image
import qrcode


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "MK/A999999999991/BuffetSubscriptionDirect/page3.html"
OUTPUT = ROOT / "MK/A999999999991/BuffetSubscriptionDirect/page3_clean.html"

text = SOURCE.read_text(encoding="utf-8")
m = re.search(r'data:image/png;base64,([A-Za-z0-9+/=]+)', text)
if not m:
    raise RuntimeError("Source Page 3 PNG not found")

raw = base64.b64decode(m.group(1))
img = Image.open(io.BytesIO(raw)).convert("RGB")
arr = np.asarray(img)
height, width = arr.shape[:2]

detector = cv2.QRCodeDetector()
found = None

# Try several scales because the source image is very tall.
for scale in (1.0, 0.85, 0.65, 0.50, 0.35):
    if scale != 1.0:
        small = cv2.resize(arr, (round(width * scale), round(height * scale)), interpolation=cv2.INTER_AREA)
    else:
        small = arr
    value, points, _ = detector.detectAndDecode(small)
    if points is not None and len(points) > 0:
        p = points[0] / scale
        found = (p, value)
        break

if found is None:
    raise RuntimeError("Could not locate the original QR code")

points, value = found
xs = points[:, 0]
ys = points[:, 1]
qx = float(xs.min())
qy = float(ys.min())
qw = float(xs.max() - xs.min())
qh = float(ys.max() - ys.min())

if qw < 40 or qh < 40:
    raise RuntimeError(f"QR detection too small: {qw}x{qh}")

out = arr.copy()

# Erase only a tight rectangle around the original QR.
# The horizontal padding is deliberately tiny so nearby red artwork is untouched.
pad_x = max(4.0, qw * 0.035)
pad_top = max(4.0, qh * 0.035)
pad_bottom = max(30.0, qh * 0.30)

x0 = max(0, int(round(qx - pad_x)))
x1 = min(width, int(round(qx + qw + pad_x)))
y0 = max(0, int(round(qy - pad_top)))
y1 = min(height, int(round(qy + qh + pad_bottom)))

out[y0:y1, x0:x1] = 255

# Generate a real QR for the requested code.
qr = qrcode.QRCode(
    version=None,
    error_correction=qrcode.constants.ERROR_CORRECT_H,
    box_size=20,
    border=4,
)
qr.add_data("A999999999991")
qr.make(fit=True)
qr_img = qr.make_image(fill_color="black", back_color="white").convert("RGB")

# Keep the replacement QR inside the original QR footprint.
target = int(round(min(qw, qh) * 0.985))
qr_img = qr_img.resize((target, target), Image.Resampling.NEAREST)
qr_arr = np.asarray(qr_img)

dx = int(round(qx + (qw - target) / 2))
dy = int(round(qy + (qh - target) / 2))
dx = max(0, min(width - target, dx))
dy = max(0, min(height - target, dy))

out[dy:dy + target, dx:dx + target] = qr_arr

clean = Image.fromarray(out, "RGB")
buf = io.BytesIO()
clean.save(buf, format="PNG", optimize=False)
clean_b64 = base64.b64encode(buf.getvalue()).decode("ascii")

OUTPUT.write_text(
    '<!doctype html><html lang="th"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">'
    '<title>MK Buffet Subscription - Page 3</title></head><body style="margin:0;padding:0;background:#fff">'
    f'<img src="data:image/png;base64,{clean_b64}" alt="MK Buffet Subscription Page 3" style="display:block;width:100vw;max-width:none;height:auto;margin:0;padding:0">'
    '</body></html>',
    encoding="utf-8",
)

print(f"Page 3 cleaned: QR box {qw:.1f}x{qh:.1f} at ({qx:.1f},{qy:.1f})")
