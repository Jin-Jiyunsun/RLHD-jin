"""Stack centre crops of WaterBench renders. Usage: stack.py <out png> <crop w> <crop h> <png>..."""
import sys
from PIL import Image

out_path, cw, ch = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
ims = [Image.open(p) for p in sys.argv[4:]]
w, h = ims[0].size
box = (w // 2 - cw // 2, h // 2 - ch // 2, w // 2 + cw // 2, h // 2 + ch // 2)
out = Image.new('RGB', (cw, len(ims) * (ch + 10) - 10), (40, 40, 40))
for k, im in enumerate(ims):
    out.paste(im.crop(box), (0, k * (ch + 10)))
out.save(out_path)
