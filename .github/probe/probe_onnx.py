import numpy as np, sys, onnxruntime as ort
from PIL import Image
s = ort.InferenceSession(sys.argv[1])
for i in s.get_inputs(): print("ONNX INPUT", i.name, i.shape, i.type)
for o in s.get_outputs(): print("ONNX OUTPUT", o.name, o.shape, o.type)
im = Image.open('.github/probe/dog.png').convert('RGB').resize((518, 518), Image.BICUBIC)
x = (np.asarray(im).astype(np.float32) / 255. - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]
x = x.astype(np.float32).transpose(2, 0, 1)[None]
inp = s.get_inputs()[0]
if 'float16' in inp.type: x = x.astype(np.float16)
d = s.run(None, {inp.name: x})[0].squeeze().astype(np.float32)
print("onnx imagenet", d.shape, d.min(), d.max(), 'top', d[:86].mean(), 'dog', d[360:, 155:415].mean())
