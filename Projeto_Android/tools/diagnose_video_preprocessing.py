"""Compare input geometry on existing lossless video samples; does not tune thresholds."""
import argparse
import csv
import json
from pathlib import Path

import cv2
import numpy as np
from offline_video_validation import ROOT, model, letterbox, decode_detections


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path)
    args = parser.parse_args()
    interpreter, inp, out, contract = model(ROOT/"Projeto_Android/app/src/main/assets/model.tflite",4)
    with (args.results/"diagnostico_preprocessamento.csv").open("w") as stream:
        writer = csv.writer(stream)
        writer.writerow(["video","frame","timestamp_ms","preprocessing","max_crosswalk_score","crosswalk_best_class_candidates_020","crosswalk_boxes_020","max_car_score"])
        for replay in sorted(args.results.glob("*/replay.json")):
            data = json.loads(replay.read_text())
            for row in data["frames"][::20]:
                frame = cv2.imread(str(args.results/row["image"]))
                variants = ["android_geometry", "legacy_rounded", "stretch_diagnostic_only"]
                native = replay.parent/"yolo_input_android.rgb"
                if row["frame"] == 0 and native.exists(): variants.append("production_skia_native")
                for variant in variants:
                    if variant == "production_skia_native":
                        rgb = np.fromfile(native,np.uint8).reshape(640,640,3)
                        image = cv2.cvtColor(rgb,cv2.COLOR_RGB2BGR)
                        python_image,ratio,px,py = letterbox(frame,640,android=True)
                        diff = np.abs(image.astype(float)-python_image.astype(float))
                        (replay.parent/"preprocess_pixel_difference.json").write_text(json.dumps(dict(
                            mae=float(diff.mean()),max=float(diff.max()),fraction_changed=float(np.mean(np.any(diff>0,axis=2))),
                            note="Production YoloDetector letterbox via Robolectric native Skia vs OpenCV; first frame only."),indent=2))
                    elif variant == "stretch_diagnostic_only":
                        image = cv2.resize(frame,(640,640)); ratio,px,py = 1,0,0
                    else:
                        image,ratio,px,py = letterbox(frame,640,android=variant == "android_geometry")
                    interpreter.set_tensor(inp["index"],cv2.cvtColor(image,cv2.COLOR_BGR2RGB)[None].astype(np.float32)/255)
                    interpreter.invoke()
                    raw = interpreter.get_tensor(out["index"])
                    boxes = decode_detections(raw,data["width"],data["height"],ratio,px,py,.20,.45,android=True)
                    scores = raw[0,4:]
                    count = int(((scores.argmax(axis=0)==8) & (scores[8]>=.20)).sum())
                    writer.writerow([replay.parent.name,row["frame"],row["timestamp_ms"],variant,float(scores[8].max()),count,
                                     sum(d[0]==8 for d in boxes),float(scores[0].max())])
    (args.results/"diagnostico_modelo.json").write_text(json.dumps(contract,indent=2))


if __name__ == "__main__": main()
