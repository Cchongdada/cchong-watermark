"""Offline four-way natural-photo orientation; no source-image writes."""
from functools import lru_cache
from pathlib import Path
from threading import Lock
import numpy as np
from PIL import Image

_lock = Lock()

@lru_cache(maxsize=1)
def session():
    import onnxruntime as ort
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    return ort.InferenceSession(str(Path(__file__).parent / "models" / "orientation.onnx"),
                               sess_options=options, providers=["CPUExecutionProvider"])

def detect(image):
    # Match upstream validation preprocessing: square resize, center crop, ImageNet RGB.
    with _lock:
        model = session()
        side = int(model.get_inputs()[0].shape[-1])
        resized = image.convert("RGB").resize((side + 32, side + 32), Image.Resampling.BILINEAR)
        crop = resized.crop((16,16,side+16,side+16))
        values = np.asarray(crop,dtype=np.float32) / 255
        values = (values - np.array([.485,.456,.406],dtype=np.float32)) / np.array([.229,.224,.225],dtype=np.float32)
        tensor = np.ascontiguousarray(values.transpose(2,0,1)[None])
        logits = model.run(None,{model.get_inputs()[0].name:tensor})[0][0]
        probabilities = np.exp(logits-logits.max()); probabilities /= probabilities.sum()
        index = int(probabilities.argmax())
        confidence = float(probabilities[index])
        return {"rotation": index*90, "confidence": round(confidence,4), "confident": confidence >= .90}
