import base64, io, sys
from PIL import Image
img = Image.open(sys.argv[1]).convert("RGB"); img.thumbnail((320, 320))
buf = io.BytesIO(); img.save(buf, "JPEG", quality=80)
print("B64 " + base64.b64encode(buf.getvalue()).decode())
