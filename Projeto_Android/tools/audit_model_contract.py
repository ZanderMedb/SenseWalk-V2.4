#!/usr/bin/env python3
"""Smoke test dos assets reais em CPU host. Não valida acurácia nem o pipeline Android.

Requer ai-edge-litert, tflite, numpy e pillow em ambiente Python isolado.
As classes abaixo são as configuradas no app, NÃO metadados comprovados do modelo.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import tflite
from ai_edge_litert.interpreter import Interpreter
from PIL import Image

CLASSES = ["car", "motorcycle", "bicycle", "truck", "bus", "traffic-light-green",
           "traffic-light-red", "traffic-light-yellow", "crosswalk",
           "pedestrian-light-green", "pedestrian-light-red"]


def inspect(path):
    data = path.read_bytes()
    model = tflite.Model.GetRootAsModel(data, 0)
    metadata = []
    for i in range(model.MetadataLength()):
        entry = model.Metadata(i)
        metadata.append(entry.Name().decode())
    interpreter = Interpreter(model_path=str(path), num_threads=2)
    interpreter.allocate_tensors()
    def tensor(detail):
        return {"name": detail["name"], "shape": detail["shape"].tolist(),
                "dtype": str(detail["dtype"]), "quantization": detail["quantization"]}
    return interpreter, {"file": str(path), "sha256": hashlib.sha256(data).hexdigest(),
        "description": (model.Description() or b"").decode(), "metadata_names": metadata,
        "inputs": [tensor(x) for x in interpreter.get_input_details()],
        "outputs": [tensor(x) for x in interpreter.get_output_details()]}


def run_image(interpreter, image, yolo):
    inp = interpreter.get_input_details()[0]
    _, h, w, _ = inp["shape"]
    factor = min(w / image.width, h / image.height)
    size = (round(image.width * factor), round(image.height * factor))
    padded = Image.new("RGB", (int(w), int(h)), (114,114,114) if yolo else (0,0,0))
    padded.paste(image.resize(size, Image.Resampling.BILINEAR), ((w-size[0])//2, (h-size[1])//2))
    pixels = np.asarray(padded, dtype=np.float32) / 255
    if inp["dtype"] == np.uint8:
        scale, zero = inp["quantization"]
        pixels = np.clip(np.rint(pixels / scale + zero), 0, 255).astype(np.uint8)
    interpreter.set_tensor(inp["index"], pixels[None])
    interpreter.invoke()
    output = interpreter.get_tensor(interpreter.get_output_details()[0]["index"])
    assert np.isfinite(output).all(), "Nonfinite output"
    if yolo:
        assert output.shape == (1,15,8400), output.shape
        scores = output[0,4:]
        best = scores.argmax(axis=0)
        return {"finite": True, "score_range": [float(scores.min()),float(scores.max())],
            "bbox_range": [float(output[0,:4].min()),float(output[0,:4].max())],
            "raw_predictions_ge_020_before_nms_by_configured_class": {
                name: int(((best == i) & (scores[i] >= .20)).sum()) for i,name in enumerate(CLASSES)}}
    return {"finite": True, "raw_mask_argmax_counts_including_padding":
        np.bincount(output.argmax(axis=-1).ravel(), minlength=19).tolist()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, default=Path(__file__).resolve().parents[1]/"app/src/main/assets")
    parser.add_argument("--image", type=Path, action="append", default=[])
    args = parser.parse_args()
    results = []
    for name, yolo in [("model.tflite",True), ("ffnet_122ns_lowres.tflite",False)]:
        interpreter, contract = inspect(args.assets/name)
        contract["host_only"] = True
        contract["runs"] = {"gray_synthetic": run_image(interpreter,Image.new("RGB",(640,360),(114,114,114)),yolo)}
        for path in args.image:
            with Image.open(path) as image:
                contract["runs"][str(path)] = run_image(interpreter,image.convert("RGB"),yolo)
        results.append(contract)
    print(json.dumps(results,indent=2))


if __name__ == "__main__":
    main()
