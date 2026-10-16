"""Valida o modelo TFLite do SenseWalk em videos locais."""

from __future__ import annotations

import argparse
import csv
import json
import time
from pathlib import Path

import cv2
import numpy as np
from ai_edge_litert.interpreter import Interpreter


ROOT = Path(__file__).resolve().parent
DEFAULT_MODEL = ROOT / "modelos_exportados" / "best_dynamic.tflite"
DEFAULT_VIDEOS = ROOT / "videos"
DEFAULT_OUTPUT = ROOT / "validacoes_videos"
CLASS_NAMES = [
    "car",
    "motorcycle",
    "bicycle",
    "truck",
    "bus",
    "traffic-light-green",
    "traffic-light-red",
    "traffic-light-yellow",
    "crosswalk",
    "pedestrian-light-green",
    "pedestrian-light-red",
]
VIDEO_EXTENSIONS = {".mp4", ".avi", ".mov", ".mkv", ".webm"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Executa o modelo TFLite nos videos e salva anotacoes e metricas."
    )
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--videos", type=Path, default=DEFAULT_VIDEOS)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--confidence", type=float, default=0.25)
    parser.add_argument("--iou", type=float, default=0.45)
    parser.add_argument("--stride", type=int, default=1, help="Processa um a cada N frames.")
    return parser.parse_args()


def letterbox(frame: np.ndarray, size: int) -> tuple[np.ndarray, float, float, float]:
    height, width = frame.shape[:2]
    ratio = min(size / width, size / height)
    resized_w, resized_h = round(width * ratio), round(height * ratio)
    resized = cv2.resize(frame, (resized_w, resized_h), interpolation=cv2.INTER_LINEAR)
    pad_w, pad_h = (size - resized_w) / 2, (size - resized_h) / 2
    left, top = round(pad_w - 0.1), round(pad_h - 0.1)
    padded = cv2.copyMakeBorder(
        resized,
        top,
        size - resized_h - top,
        left,
        size - resized_w - left,
        cv2.BORDER_CONSTANT,
        value=(114, 114, 114),
    )
    return padded, ratio, pad_w, pad_h


def sigmoid(value: float) -> float:
    return 1 / (1 + np.exp(-value))


def decode_detections(
    output: np.ndarray,
    frame_width: int,
    frame_height: int,
    ratio: float,
    pad_w: float,
    pad_h: float,
    confidence: float,
    iou: float,
) -> list[tuple[int, float, int, int, int, int]]:
    predictions = output[0]
    if predictions.shape[0] > predictions.shape[1]:
        predictions = predictions.T

    boxes_by_class: dict[int, list[list[int]]] = {}
    scores_by_class: dict[int, list[float]] = {}
    for prediction in predictions.T:
        class_id = int(np.argmax(prediction[4:]))
        score = float(prediction[4 + class_id])
        if score < 0 or score > 1:
            score = sigmoid(score)
        if score < confidence:
            continue

        center_x, center_y, width, height = prediction[:4]
        x1 = int((center_x - width / 2 - pad_w) / ratio)
        y1 = int((center_y - height / 2 - pad_h) / ratio)
        x2 = int((center_x + width / 2 - pad_w) / ratio)
        y2 = int((center_y + height / 2 - pad_h) / ratio)
        x1, x2 = sorted((max(0, x1), min(frame_width - 1, x2)))
        y1, y2 = sorted((max(0, y1), min(frame_height - 1, y2)))
        if x2 <= x1 or y2 <= y1:
            continue
        boxes_by_class.setdefault(class_id, []).append([x1, y1, x2 - x1, y2 - y1])
        scores_by_class.setdefault(class_id, []).append(score)

    detections = []
    for class_id, boxes in boxes_by_class.items():
        indices = cv2.dnn.NMSBoxes(boxes, scores_by_class[class_id], confidence, iou)
        for index in indices:
            index = int(index)
            x, y, width, height = boxes[index]
            detections.append((class_id, scores_by_class[class_id][index], x, y, x + width, y + height))
    return detections


def draw_detections(frame: np.ndarray, detections: list[tuple[int, float, int, int, int, int]]) -> None:
    for class_id, score, x1, y1, x2, y2 in detections:
        color = (50, 220, 50) if "green" in CLASS_NAMES[class_id] else (40, 80, 240)
        label = f"{CLASS_NAMES[class_id]} {score:.0%}"
        cv2.rectangle(frame, (x1, y1), (x2, y2), color, 2)
        cv2.putText(frame, label, (x1, max(20, y1 - 6)), cv2.FONT_HERSHEY_SIMPLEX, 0.55, color, 2)


def process_video(
    video_path: Path,
    interpreter: Interpreter,
    input_detail: dict,
    output_detail: dict,
    output_dir: Path,
    confidence: float,
    iou: float,
    stride: int,
) -> dict:
    capture = cv2.VideoCapture(str(video_path))
    if not capture.isOpened():
        raise RuntimeError(f"Nao foi possivel abrir: {video_path}")

    fps = capture.get(cv2.CAP_PROP_FPS) or 30.0
    width, height = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH)), int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT))
    writer = cv2.VideoWriter(
        str(output_dir / f"{video_path.stem}_anotado.mp4"),
        cv2.VideoWriter_fourcc(*"mp4v"),
        fps,
        (width, height),
    )
    csv_path = output_dir / f"{video_path.stem}_deteccoes.csv"
    frame_index = 0
    processed_frames = 0
    total_detections = 0
    inference_seconds = 0.0
    input_size = int(input_detail["shape"][1])

    with csv_path.open("w", newline="", encoding="utf-8") as file:
        csv_writer = csv.writer(file)
        csv_writer.writerow(["frame", "timestamp_s", "class_id", "class_name", "confidence", "x1", "y1", "x2", "y2"])
        while True:
            ok, frame = capture.read()
            if not ok:
                break
            if frame_index % stride == 0:
                letterboxed, ratio, pad_w, pad_h = letterbox(frame, input_size)
                image = cv2.cvtColor(letterboxed, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
                interpreter.set_tensor(input_detail["index"], image[np.newaxis, ...])
                start = time.perf_counter()
                interpreter.invoke()
                inference_seconds += time.perf_counter() - start
                detections = decode_detections(
                    interpreter.get_tensor(output_detail["index"]),
                    width,
                    height,
                    ratio,
                    pad_w,
                    pad_h,
                    confidence,
                    iou,
                )
                processed_frames += 1
                total_detections += len(detections)
                for detection in detections:
                    class_id, score, x1, y1, x2, y2 = detection
                    csv_writer.writerow([frame_index, frame_index / fps, class_id, CLASS_NAMES[class_id], score, x1, y1, x2, y2])
                draw_detections(frame, detections)
            writer.write(frame)
            frame_index += 1

    capture.release()
    writer.release()
    return {
        "video": str(video_path),
        "frames": frame_index,
        "processed_frames": processed_frames,
        "detections": total_detections,
        "average_inference_ms": round(1000 * inference_seconds / processed_frames, 2) if processed_frames else None,
        "annotated_video": str(output_dir / f"{video_path.stem}_anotado.mp4"),
        "detections_csv": str(csv_path),
    }


def main() -> None:
    args = parse_args()
    model_path = args.model.expanduser().resolve()
    videos_dir = args.videos.expanduser().resolve()
    output_dir = args.output.expanduser().resolve()
    if not model_path.is_file():
        raise FileNotFoundError(f"Modelo nao encontrado: {model_path}")
    if not videos_dir.is_dir():
        raise NotADirectoryError(f"Pasta de videos nao encontrada: {videos_dir}")
    if args.stride < 1:
        raise ValueError("--stride deve ser no minimo 1")

    videos = sorted(path for path in videos_dir.iterdir() if path.suffix.lower() in VIDEO_EXTENSIONS)
    if not videos:
        raise FileNotFoundError(f"Nenhum video encontrado em: {videos_dir}")

    output_dir.mkdir(parents=True, exist_ok=True)
    interpreter = Interpreter(model_path=str(model_path), num_threads=4)
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]
    if input_detail["dtype"] != np.float32 or output_detail["dtype"] != np.float32:
        raise RuntimeError("O validador requer um modelo com entrada e saida float32.")

    results = [
        process_video(video, interpreter, input_detail, output_detail, output_dir, args.confidence, args.iou, args.stride)
        for video in videos
    ]
    summary_path = output_dir / "resumo.json"
    summary_path.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(f"Videos processados: {len(results)}")
    print(f"Resultados: {output_dir}")
    print(f"Resumo: {summary_path}")


if __name__ == "__main__":
    main()
