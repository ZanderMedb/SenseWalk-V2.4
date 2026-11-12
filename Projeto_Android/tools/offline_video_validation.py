"""Real host TFLite inference + artifacts for a separate Kotlin/Robolectric replay.

No Android integration claim: OpenCV resize differs from Skia; host LiteRT differs
from Android. Timestamp is media PTS, not capture-to-audio latency.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import importlib.metadata
import json
import platform
import os
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path

import cv2
import numpy as np
from ai_edge_litert.interpreter import Interpreter

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "Dados_IA/dados_treinamento_ia"))
from validar_videos_tflite import CLASS_NAMES, decode_detections, draw_detections, letterbox


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def model(path, threads):
    interpreter = Interpreter(model_path=str(path), num_threads=threads)
    interpreter.allocate_tensors()
    i, = interpreter.get_input_details()
    o, = interpreter.get_output_details()
    contract = {"sha256": sha256(path), "input": i["shape"].tolist(), "output": o["shape"].tolist(),
                "input_dtype": str(i["dtype"]), "output_dtype": str(o["dtype"]),
                "input_quantization": i["quantization"], "output_quantization": o["quantization"]}
    return interpreter, i, o, contract


def ffnet_input(frame, scale, zero):
    h, w = frame.shape[:2]
    factor = min(np.float32(1024)/w, np.float32(512)/h)
    rw, rh = int(np.floor(w*factor+.5)), int(np.floor(h*factor+.5))
    left, top = (1024-rw)//2, (512-rh)//2
    image = np.zeros((512, 1024, 3), np.uint8)
    image[top:top+rh, left:left+rw] = cv2.resize(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB), (rw, rh))
    image = np.clip(np.floor(image.astype(np.float32)/255/np.float32(scale)+.5)+zero, 0, 255).astype(np.uint8)
    return image[None], (left, top, rw, rh)


def ffnet_labels(scores, geometry):
    left, top, w, h = geometry
    x = np.clip((left+(np.arange(256, dtype=np.float32)+.5)/256*w)/4-.5, 0, 255)
    y = np.clip((top+(np.arange(128, dtype=np.float32)+.5)/128*h)/4-.5, 0, 127)
    x0, y0 = x.astype(int), y.astype(int)
    x1, y1 = np.minimum(x0+1, 255), np.minimum(y0+1, 127)
    dx, dy = (x-x0)[None, :, None], (y-y0)[:, None, None]
    s = scores[0].astype(np.float32)
    # Exact bilinear coordinates, not OpenCV's quantized remap interpolation table.
    values = (s[y0[:, None], x0]*(1-dx)+s[y0[:, None], x1]*dx)*(1-dy) + (s[y1[:, None], x0]*(1-dx)+s[y1[:, None], x1]*dx)*dy
    return np.argmax(values, axis=2).astype(np.uint8)


def road_metrics(labels):
    h, w = labels.shape
    a = labels[h//2:h-1, w//6:w*5//6-1]
    right = labels[h//2:h-1, w//6+1:w*5//6]
    below = labels[h//2+1:h, w//6:w*5//6-1]
    road, sidewalk = float(np.mean(a == 0)), float(np.mean(a == 1))
    boundary = float(np.mean(((a == 0) & ((right == 1) | (below == 1))) | ((a == 1) & ((right == 0) | (below == 0)))))
    return road, sidewalk, boundary, road >= .12 and sidewalk >= .05 and boundary >= .002


def intervals(samples, predicate):
    result, start, last = [], None, None
    for row in samples:
        if predicate(row):
            if start is None: start = row["timestamp_s"]
            last = row["timestamp_s"]
        elif start is not None:
            result.append([start, last]); start = None
    if start is not None: result.append([start, last])
    return result


def annotation_metrics(samples, annotations, video):
    # Only explicitly reviewed PRESENT/ABSENT samples count; unknown is not negative.
    tp = fn = fp = tn = 0
    for row in samples:
        matches = [a for a in annotations if a["video"] == video and a["class_name"] == "crosswalk"
                   and (row.get("frame") in a["frames"] if "frames" in a else a["start_s"] <= row["timestamp_s"] <= a["end_s"])
                   and a["presence"] in ("present", "absent")]
        states = {a["presence"] for a in matches}
        if len(states) > 1: raise ValueError("Conflicting annotations")
        if not states: continue
        present, detected = "present" in states, row["crosswalk_confidence"] > 0
        tp += int(present and detected); fn += int(present and not detected)
        fp += int(not present and detected); tn += int(not present and not detected)
    return {"annotated_samples": tp+fn+fp+tn, "tp_presence": tp, "fn_presence": fn,
            "fp_presence": fp, "tn_presence": tn, "presence_recall": tp/(tp+fn) if tp+fn else None,
            "absent_frame_false_positive_rate": fp/(fp+tn) if fp+tn else None,
            "note": "Frame presence, not box IoU/object recall or safety accuracy."}


def graph(path, rows):
    points = " ".join(f"{40+r['timestamp_s']/max(rows[-1]['timestamp_s'], 1)*900:.1f},{220-r['crosswalk_confidence']*180:.1f}" for r in rows)
    path.write_text(f'<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="260"><rect width="100%" height="100%" fill="white"/><text x="40" y="20">Crosswalk confidence (0..1), media time (seconds)</text><path d="M40 40V220H940" fill="none" stroke="black"/><polyline points="{points}" fill="none" stroke="blue"/><text x="40" y="245">0 s</text><text x="900" y="245">{rows[-1]["timestamp_s"]:.2f} s</text></svg>')


def process(path, output, stride, yolo, ffnet, writers, annotations):
    yi, yin, yout, _ = yolo
    fi, fin, fout, _ = ffnet
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened(): raise ValueError(f"Cannot open {path}")
    fps, total = cap.get(cv2.CAP_PROP_FPS), int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    w, h = int(cap.get(3)), int(cap.get(4))
    if not np.isfinite(fps) or fps <= 0 or min(w, h) <= 0: raise ValueError("Invalid video metadata")
    metadata = dict(width=w, height=h, fps=fps, reported_frames=total, duration_s=total/fps,
                    orientation_degrees=cap.get(cv2.CAP_PROP_ORIENTATION_META), sha256=sha256(path))
    folder = output / path.stem
    folder.mkdir(parents=True)
    writer = cv2.VideoWriter(str(output/f"{path.stem}_anotado.mp4"), cv2.VideoWriter_fourcc(*"mp4v"), fps/stride, (w, h))
    if not writer.isOpened(): raise RuntimeError("Cannot create annotated MP4")
    rows, counts, timings, replay = [], Counter(), [], []
    n, previous_mask, previous_pts = 0, None, -1.0
    pts_fallbacks = 0
    try:
        while True:
            ok, frame = cap.read()
            if not ok: break
            if frame is None or frame.shape[:2] != (h, w): raise ValueError(f"Invalid frame {n}")
            pts = cap.get(cv2.CAP_PROP_POS_MSEC)/1000
            if not np.isfinite(pts) or pts <= previous_pts:
                pts = n/fps; pts_fallbacks += 1
            previous_pts = pts
            index = n; n += 1
            if index % stride: continue
            started = time.perf_counter()
            padded, ratio, px, py = letterbox(frame, 640, android=True)
            yi.set_tensor(yin["index"], cv2.cvtColor(padded, cv2.COLOR_BGR2RGB)[None].astype(np.float32)/255)
            t = time.perf_counter(); yi.invoke(); yms = (time.perf_counter()-t)*1000
            raw = yi.get_tensor(yout["index"])
            if not np.isfinite(raw).all(): raise ValueError("Nonfinite YOLO output")
            detections = decode_detections(raw, w, h, ratio, px, py, .20, .45, android=True)
            image, geometry = ffnet_input(frame, *fin["quantization"])
            fi.set_tensor(fin["index"], image)
            t = time.perf_counter(); fi.invoke(); fms = (time.perf_counter()-t)*1000
            scores = fi.get_tensor(fout["index"])
            labels = ffnet_labels(scores, geometry)
            r, s, b, boundary = road_metrics(labels)
            union = np.logical_or(labels == 0, previous_mask == 0).sum() if previous_mask is not None else 0
            road_iou = float(np.logical_and(labels == 0, previous_mask == 0).sum()/union) if union else None
            previous_mask = labels
            row = dict(video=path.stem, frame=index, timestamp_s=pts,
                       crosswalk_confidence=max((d[1] for d in detections if d[0] == 8), default=0),
                       road_ratio=r, sidewalk_ratio=s, boundary_ratio=b, possible_boundary=boundary,
                       road_temporal_iou=road_iou, yolo_ms=yms, ffnet_ms=fms,
                       processing_ms=(time.perf_counter()-started)*1000)
            rows.append(row); timings.append([yms, fms, row["processing_ms"]])
            writers["segmentacao"].writerow(row)
            for cid, confidence, x1, y1, x2, y2 in detections:
                counts[CLASS_NAMES[cid]] += 1
                writers["deteccoes"].writerow([path.stem, index, pts, cid, CLASS_NAMES[cid], confidence, x1, y1, x2, y2])
            # Lossless frames/masks feed the actual Kotlin implementation in the replay.
            name = f"{index:06d}"
            cv2.imwrite(str(folder/f"{name}.png"), frame)
            (folder/f"{name}.mask").write_bytes(labels.tobytes())
            replay.append(dict(frame=index, timestamp_ms=int(round(pts*1000)), image=f"{path.stem}/{name}.png",
                               mask=f"{path.stem}/{name}.mask", detections=detections, road=[r, s, b, boundary]))
            if index == 0:
                # Actual model output shared with Kotlin decoder/codec parity tests.
                cv2.cvtColor(padded,cv2.COLOR_BGR2RGB).tofile(folder/"yolo_input_python.rgb")
                raw.astype('<f4').tofile(folder/"yolo_output.f32")
                scores.tofile(folder/"ffnet_output.u8")
                (folder/"geometry.json").write_text(json.dumps(dict(yolo=[ratio, px, py], ffnet=geometry)))
            overlay = frame.copy()
            mask = cv2.resize(labels, (w, h), interpolation=cv2.INTER_NEAREST)
            overlay[mask == 0] = (.65*overlay[mask == 0]+.35*np.array([190, 40, 160])).astype(np.uint8)
            overlay[mask == 1] = (.65*overlay[mask == 1]+.35*np.array([220, 220, 0])).astype(np.uint8)
            draw_detections(overlay, detections)
            cv2.putText(overlay, f"{pts:.2f}s sampled | signal applicability UNKNOWN", (12, 24), cv2.FONT_HERSHEY_SIMPLEX, .55, (255,255,255), 2)
            writer.write(overlay)
            if index % (stride*10) == 0: cv2.imwrite(str(folder/f"{name}_overlay.jpg"), overlay)
    finally:
        cap.release(); writer.release()
    if not rows: raise ValueError("No decoded samples")
    (folder/"replay.json").write_text(json.dumps(dict(width=w, height=h, frames=replay)))
    graph(output/f"{path.stem}_confianca.svg", rows)
    return dict(video=str(path), metadata=metadata, frames_decoded=n, processed_frames=len(rows),
                decode_count_matches=n == total, timestamp_fallbacks=pts_fallbacks,
                detections_by_class=dict(counts), crosswalk_detected_frames=sum(r["crosswalk_confidence"] > 0 for r in rows),
                road_detected_frames=sum(r["road_ratio"] >= .12 for r in rows),
                sidewalk_detected_frames=sum(r["sidewalk_ratio"] >= .05 for r in rows),
                boundary_frames=sum(r["possible_boundary"] for r in rows),
                no_crosswalk_detection_intervals=intervals(rows, lambda r: not r["crosswalk_confidence"]),
                intervals_note="Candidates for review; NOT confirmed failures without visibility annotations.",
                latency_host_ms={name: dict(p50=float(np.median(np.array(timings)[:, i])), p95=float(np.percentile(np.array(timings)[:, i], 95))) for i, name in enumerate(["yolo", "ffnet", "pipeline"])},
                annotations=annotation_metrics(rows, annotations, path.stem))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--videos", type=Path, default=ROOT/"videos_teste")
    parser.add_argument("--output", type=Path, default=ROOT/"videos_teste/resultados")
    parser.add_argument("--stride", type=int, default=3)
    parser.add_argument("--annotations", type=Path)
    parser.add_argument("--replay-kotlin", action="store_true", help="Also run actual Kotlin classes via Gradle/Robolectric and generate tracking artifacts (JDK/SDK required)")
    args = parser.parse_args()
    if args.stride < 1: parser.error("stride must be positive")
    if args.output.exists() and any(args.output.iterdir()): parser.error("Use a new/empty output directory to preserve evidence")
    args.output.mkdir(parents=True, exist_ok=True)
    paths = sorted(args.videos.glob("*.mp4"))
    result = dict(videos=[], errors=[], stride=args.stride, confidence=.20, nms=.45,
                  python=platform.python_version(), opencv=cv2.__version__, numpy=np.__version__,
                  litert=importlib.metadata.version("ai-edge-litert"),
                  nominal_classes=CLASS_NAMES, class_provenance="AppConfig + dataset V3; no nominal metadata in YOLO asset",
                  limitations=["Host inference, not Android runtime", "OpenCV vs Skia pixel interpolation not certified", "No physical coverage or signal applicability", "No risk ground truth"])
    if paths:
        assets = ROOT/"Projeto_Android/app/src/main/assets"
        yolo, ffnet = model(assets/"model.tflite", 4), model(assets/"ffnet_122ns_lowres.tflite", 2)
        assert yolo[3]["input"] == [1,640,640,3] and yolo[3]["output"] == [1,15,8400]
        assert ffnet[3]["input"] == [1,512,1024,3] and ffnet[3]["output"] == [1,128,256,19]
        assert yolo[1]["dtype"] == np.float32 and yolo[2]["dtype"] == np.float32
        assert ffnet[1]["dtype"] == np.uint8 and ffnet[2]["dtype"] == np.uint8
        result["models"] = dict(yolo=yolo[3], ffnet=ffnet[3])
        annotations = json.loads(args.annotations.read_text())["intervals"] if args.annotations else []
        with (args.output/"deteccoes.csv").open("w") as df, (args.output/"segmentacao.csv").open("w") as sf:
            dw = csv.writer(df); dw.writerow(["video", "frame", "timestamp_s", "class_id", "class_name", "confidence", "x1", "y1", "x2", "y2"])
            sw = csv.DictWriter(sf, fieldnames=["video", "frame", "timestamp_s", "crosswalk_confidence", "road_ratio", "sidewalk_ratio", "boundary_ratio", "possible_boundary", "road_temporal_iou", "yolo_ms", "ffnet_ms", "processing_ms"]); sw.writeheader()
            for path in paths:
                try:
                    summary = process(path, args.output, args.stride, yolo, ffnet, dict(deteccoes=dw, segmentacao=sw), annotations)
                    result["videos"].append(summary)
                    print(json.dumps(summary, ensure_ascii=False), flush=True)
                except Exception as exc:
                    result["errors"].append(dict(video=str(path), error=str(exc)))
                    print(f"ERROR {path}: {exc}", file=sys.stderr, flush=True)
    else:
        result["errors"].append(dict(error="No MP4 videos found; pipeline ready for later input"))
    (args.output/"metricas.json").write_text(json.dumps(result, indent=2, ensure_ascii=False))
    lines = ["# Validação offline", "", "Inferência real Linux; replay Kotlin separado. Nenhuma certificação de travessia.", ""]
    for v in result["videos"]:
        lines += [f"## {Path(v['video']).name}", f"Decodificados: {v['frames_decoded']}; processados: {v['processed_frames']}.",
                  f"Detecções: `{v['detections_by_class']}`.", f"Faixa detectada em {v['crosswalk_detected_frames']} amostras; pista em {v['road_detected_frames']}; limite em {v['boundary_frames']}.",
                  "Intervalos sem detecção em metricas.json precisam de revisão visual. Métricas de acurácia exigem anotações.", ""]
    lines += [f"Erros: {result['errors']}", "", "Não valida orientação física, pés/meio-fio, captura→áudio, USB/X5, TTS Android, TalkBack ou segurança no trânsito."]
    (args.output/"relatorio_offline.md").write_text("\n".join(lines))
    if result["errors"]: sys.exit(1)
    if args.replay_kotlin:
        env = dict(os.environ, SENSEWALK_VIDEO_RESULTS=str(args.output.resolve()))
        with (args.output/"replay-gradle.log").open("w") as log:
            subprocess.run([str(ROOT/"Projeto_Android/gradlew"), "-p", str(ROOT/"Projeto_Android"),
                            "testDebugUnitTest", "--tests", "*OfflineVideoReplayTest", "--rerun-tasks", "--console=plain"],
                           env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        subprocess.run([sys.executable,str(Path(__file__).with_name("diagnose_video_preprocessing.py")),str(args.output)],check=True)
        command = [sys.executable,str(Path(__file__).with_name("summarize_video_validation.py")),str(args.output),"--tracking-video"]
        if args.annotations: command += ["--annotations",str(args.annotations)]
        subprocess.run(command,check=True)


if __name__ == "__main__":
    main()
