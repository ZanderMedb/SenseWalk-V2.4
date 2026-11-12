"""Summarize real inference + Kotlin replay, optionally evaluate reviewed presence samples."""
import argparse
import csv
import json
from collections import Counter
from pathlib import Path

import cv2
import numpy as np
from offline_video_validation import annotation_metrics, intervals


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path)
    parser.add_argument("--annotations", type=Path)
    parser.add_argument("--tracking-video", action="store_true")
    args = parser.parse_args()
    root = args.results
    metadata = json.loads((root/"metricas.json").read_text())
    tracking = list(csv.DictReader((root/"tracking.csv").open())) if (root/"tracking.csv").exists() else []
    scenes = list(csv.DictReader((root/"cena_kotlin.csv").open())) if (root/"cena_kotlin.csv").exists() else []
    segments = list(csv.DictReader((root/"segmentacao.csv").open()))
    detections = list(csv.DictReader((root/"deteccoes.csv").open()))
    annotations = json.loads(args.annotations.read_text()) if args.annotations else {"intervals":[]}
    report = dict(annotation_source=annotations.get("source"), videos=[])
    for video in metadata["videos"]:
        name = Path(video["video"]).stem
        ts = [r for r in tracking if r["video"] == name]
        ss = [r for r in scenes if r["video"] == name]
        seg = [r for r in segments if r["video"] == name]
        samples = [dict(frame=int(r["frame"]),timestamp_s=float(r["timestamp_s"]),crosswalk_confidence=float(r["crosswalk_confidence"])) for r in seg]
        by_frame = {}
        for row in detections:
            if row["video"] == name: by_frame.setdefault(int(row["frame"]),[]).append(row)
        signal_classes = ["traffic-light-green","traffic-light-red","traffic-light-yellow","pedestrian-light-green","pedestrian-light-red"]
        signals = {}
        for cls in signal_classes:
            presence = [dict(timestamp_s=r["timestamp_s"],present=any(d["class_name"] == cls for d in by_frame.get(r["frame"],[]))) for r in samples]
            signals[cls] = dict(detected_frames=sum(r["present"] for r in presence),
                                presence_intervals=intervals(presence,lambda r:r["present"]),
                                missing_intervals=intervals(presence,lambda r:not r["present"]))
        spans = []
        for tid in sorted({int(r["id"]) for r in ts}):
            seen = [r for r in ts if int(r["id"]) == tid and r["ghost"] == "false"]
            times = [int(r["timestamp_ms"])/1000 for r in seen]
            if times:
                spans.append(dict(id=tid,first_s=min(times),last_s=max(times),observations=len(times),
                                  max_observation_gap_s=max(np.diff(times),default=0)))
        item = dict(video=name,track_ids_created=len(spans),track_spans=spans,
                    ghost_rows=sum(r["ghost"] == "true" for r in ts),
                    classifications=dict(Counter(r["classification"] for r in ts if r["ghost"] == "false")),
                    risk_frame_counts=dict(Counter(reason for r in ss for reason in r["reasons"].split("|") if reason)),
                    danger_frames=sum(r["danger"] == "true" for r in ss),
                    camera_reliable_frames=sum(float(r["camera_confidence"]) >= .35 for r in ss),
                    crosswalk_confirmed_frames=sum(r["crosswalk"] == "CONFIRMED" for r in ss),
                    signal_heuristic_counts=dict(Counter(r["signal_heuristic"] for r in ss)),
                    signal_class_continuity=signals,
                    coverage_verified=False, signal_applicability="UNCERTAIN",
                    road_ratio_range=[min(float(r["road_ratio"]) for r in seg),max(float(r["road_ratio"]) for r in seg)],
                    sidewalk_ratio_range=[min(float(r["sidewalk_ratio"]) for r in seg),max(float(r["sidewalk_ratio"]) for r in seg)],
                    low_road_temporal_iou_samples=[int(r["frame"]) for r in seg if r["road_temporal_iou"] and float(r["road_temporal_iou"]) < .8],
                    reviewed_presence=annotation_metrics(samples,annotations["intervals"],name),
                    limits="Track creation/gaps are observable; ID switches, FP/FN alerts and physical approach require independent object/risk ground truth.")
        report["videos"].append(item)
        if args.tracking_video and ss:
            cap = cv2.VideoCapture(str(root/f"{name}_anotado.mp4"))
            writer = cv2.VideoWriter(str(root/f"{name}_tracking.mp4"),cv2.VideoWriter_fourcc(*"mp4v"),
                                     video["metadata"]["fps"]/metadata["stride"],(video["metadata"]["width"],video["metadata"]["height"]))
            if not cap.isOpened() or not writer.isOpened(): raise RuntimeError("Tracking video unavailable")
            by_frame = {}
            for r in ts: by_frame.setdefault(int(r["frame"]),[]).append(r)
            try:
                for s in ss:
                    ok, frame = cap.read()
                    if not ok: raise RuntimeError("Annotated MP4 shorter than replay")
                    for r in by_frame.get(int(s["frame"]),[]):
                        box = [int(float(r[k])) for k in ["x1","y1","x2","y2"]]
                        color = (150,150,150) if r["ghost"] == "true" else (0,255,255)
                        cv2.rectangle(frame,box[:2],box[2:],color,1)
                        cv2.putText(frame,f"#{r['id']} {r['classification']}",(max(0,box[0]),max(45,box[1])),0,.45,color,1)
                    cv2.putText(frame,f"Kotlin: {s['stage']} | {s['reasons']}",(8,frame.shape[0]-12),0,.42,(255,255,255),1)
                    writer.write(frame)
            finally:
                cap.release(); writer.release()
    (root/"avaliacao.json").write_text(json.dumps(report,indent=2,ensure_ascii=False))
    path = root/"relatorio_offline.md"
    text = path.read_text().split("\n## Replay Kotlin e revisão")[0]
    text += "\n## Replay Kotlin e revisão\n\nFonte das anotações: " + str(report["annotation_source"]) + ".\n"
    for v in report["videos"]:
        text += f"\n### {v['video']}\n\nIDs criados: {v['track_ids_created']}; ghosts: {v['ghost_rows']}; frames com alerta de perigo: {v['danger_frames']}.\n"
        text += f"Classificações: `{v['classifications']}`.\n\nPresença revisada: `{v['reviewed_presence']}`.\n"
    text += "\nDados descritivos em avaliacao.json. IDs criados não equivalem a ID switches. Não há ground truth humano independente de risco.\n"
    path.write_text(text)
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__ == "__main__": main()
