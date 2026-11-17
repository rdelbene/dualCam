import numpy as np, sys
from PIL import Image
from ai_edge_litert.interpreter import Interpreter
m = sys.argv[1]
it = Interpreter(model_path=m); it.allocate_tensors()
inp = it.get_input_details()[0]; out = it.get_output_details()
print("INPUT", inp['shape'], inp['dtype'], inp.get('quantization'))
for o in out: print("OUTPUT", o['shape'], o['dtype'])
shape = inp['shape']; nchw = shape[1] == 3
H, W = (shape[2], shape[3]) if nchw else (shape[1], shape[2])
im = Image.open('.github/probe/dog.png').convert('RGB').resize((W, H), Image.BICUBIC)
x = np.asarray(im).astype(np.float32) / 255.
for name, v in [('0a1', x), ('imagenet', (x - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225])]:
    v = v.astype(np.float32)
    if nchw: v = v.transpose(2, 0, 1)
    it.set_tensor(inp['index'], v[None]); it.invoke()
    d = it.get_tensor(out[0]['index']).squeeze()
    print(name, d.shape, float(d.min()), float(d.max()), 'top', float(d[:H//6].mean()), 'dog', float(d[int(H*.7):, int(W*.3):int(W*.8)].mean()))
