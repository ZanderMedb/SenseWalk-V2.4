"""Valida o modelo TFLite do SenseWalk em um video local por execucao."""

from __future__ import annotations

import argparse
import csv
import json
import time
from pathlib import Path

import cv2
import numpy as np

try:
    from ai_edge_litert.interpreter import Interpreter
except ImportError:
    from tensorflow.lite.python.interpreter import Interpreter


ROOT = Path(__file__).resolve().parent
DEFAULT_MODEL = ROOT.parents[1] / "Projeto_Android" / "app" / "src" / "main" / "assets" / "model.tflite"
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
        description="Executa o modelo TFLite em um video e salva anotacoes e metricas."
    )
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--video", type=Path, required=True, help="Caminho de um unico video a validar.")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--confidence", type=float, default=0.25)
    parser.add_argument("--iou", type=float, default=0.45)
    parser.add_argument("--stride", type=int, default=1, help="Processa um a cada N frames.")
    parser.add_argument("--coordinates", choices=("normalized", "pixels"), default="normalized",
                        help="Formato das boxes. O asset atual retorna coordenadas normalizadas.")
    return parser.parse_args()


def letterbox(frame: np.ndarray, size: int, android: bool = False) -> tuple[np.ndarray, float, float, float]:
    height, width = frame.shape[:2]
    if height <= 0 or width <= 0:
        raise ValueError("Frame recebido sem dimensoes validas.")
    ratio = float(min(np.float32(size) / width, np.float32(size) / height)) if android else min(size / width, size / height)
    resized_w, resized_h = ((max(1, int(np.float32(width) * np.float32(ratio))),
                             max(1, int(np.float32(height) * np.float32(ratio)))) if android
                            else (round(width * ratio), round(height * ratio)))
    resized = cv2.resize(frame, (resized_w, resized_h), interpolation=cv2.INTER_LINEAR)
    pad_w, pad_h = (size - resized_w) / 2, (size - resized_h) / 2
    # Canvas.drawBitmap(..., null Paint) snaps half-pixel translation upwards.
    # Confirmed against production YoloDetector with Robolectric native Skia.
    left, top = ((int(np.floor(pad_w + .5)), int(np.floor(pad_h + .5))) if android
                 else (round(pad_w - 0.1), round(pad_h - 0.1)))
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
    return float(1 / (1 + np.exp(-np.clip(value, -500, 500))))


def decode_detections(
    output: np.ndarray,
    frame_width: int,
    frame_height: int,
    ratio: float,
    pad_w: float,
    pad_h: float,
    confidence: float,
    iou: float,
    input_size: int = 640,
    coordinates: str = "normalized",
    android: bool = False,
) -> list[tuple[int, float, int, int, int, int]]:
    predictions = output[0]
    if predictions.ndim != 2:
        raise ValueError(f"Saida do modelo invalida: esperado tensor 2D, recebido {predictions.shape}.")
    if predictions.shape[0] > predictions.shape[1]:
        predictions = predictions.T
    expected_features = 4 + len(CLASS_NAMES)
    if predictions.shape[0] != expected_features:
        raise ValueError(
            f"Saida do modelo incompativel: esperado {expected_features} atributos por deteccao, "
            f"recebido {predictions.shape[0]}."
        )
    if not np.isfinite(predictions).all():
        raise ValueError("Saida do modelo contem valores nao finitos.")

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
        if coordinates == "normalized":
            center_x, center_y, width, height = prediction[:4] * input_size
        if android:
            box = np.array([(center_x - width / 2 - pad_w) / ratio,
                            (center_y - height / 2 - pad_h) / ratio,
                            (center_x + width / 2 - pad_w) / ratio,
                            (center_y + height / 2 - pad_h) / ratio], dtype=np.float32)
            box[[0, 2]] = np.clip(box[[0, 2]], 0, frame_width)
            box[[1, 3]] = np.clip(box[[1, 3]], 0, frame_height)
            x1, y1, x2, y2 = map(float, box)
            if x2 - x1 < 1 or y2 - y1 < 1:
                continue
            boxes_by_class.setdefault(class_id, []).append([x1, y1, x2 - x1, y2 - y1])
            scores_by_class.setdefault(class_id, []).append(score)
            continue
        x1 = int((center_x - width / 2 - pad_w) / ratio)
        y1 = int((center_y - height / 2 - pad_h) / ratio)
        x2 = int((center_x + width / 2 - pad_w) / ratio)
        y2 = int((center_y + height / 2 - pad_h) / ratio)
        x1, x2 = (max(0, min(frame_width - 1, x)) for x in (x1, x2))
        y1, y2 = (max(0, min(frame_height - 1, y)) for y in (y1, y2))
        if x2 <= x1 or y2 <= y1:
            continue
        boxes_by_class.setdefault(class_id, []).append([x1, y1, x2 - x1, y2 - y1])
        scores_by_class.setdefault(class_id, []).append(score)

    detections = []
    for class_id, boxes in boxes_by_class.items():
        if android:
            # Android retains confidence == threshold and suppresses only IoU > threshold.
            pending = sorted(range(len(boxes)), key=lambda i: -scores_by_class[class_id][i])
            indices = []
            while pending:
                best = pending.pop(0)
                indices.append(best)
                a = boxes[best]
                def overlap(index):
                    b = boxes[index]
                    intersection = max(0, min(a[0]+a[2], b[0]+b[2])-max(a[0], b[0])) * max(0, min(a[1]+a[3], b[1]+b[3])-max(a[1], b[1]))
                    union = a[2]*a[3]+b[2]*b[3]-intersection
                    return intersection / union if union > 0 else 0
                pending = [i for i in pending if overlap(i) <= iou]
        else:
            indices = cv2.dnn.NMSBoxes(boxes, scores_by_class[class_id], confidence, iou)
        for index in np.asarray(indices).reshape(-1):
            index = int(index)
            x, y, width, height = boxes[index]
            detections.append((class_id, scores_by_class[class_id][index], x, y, x + width, y + height))
    return detections


def draw_detections(frame: np.ndarray, detections: list[tuple[int, float, int, int, int, int]]) -> None:
    for class_id, score, x1, y1, x2, y2 in detections:
        x1, y1, x2, y2 = map(int, (x1, y1, x2, y2))
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
    coordinates: str = "normalized",
) -> dict:
    capture = cv2.VideoCapture(str(video_path))
    if not capture.isOpened():
        raise RuntimeError(f"Nao foi possivel abrir: {video_path}")

    fps = capture.get(cv2.CAP_PROP_FPS)
    width, height = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH)), int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT))
    reported_frames = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
    if not np.isfinite(fps) or fps <= 0:
        capture.release()
        raise RuntimeError(f"FPS invalido no video: {fps}")
    if width <= 0 or height <= 0:
        capture.release()
        raise RuntimeError(f"Resolucao invalida no video: {width}x{height}")

    annotated_path = output_dir / f"{video_path.stem}_anotado.mp4"
    writer = cv2.VideoWriter(
        str(annotated_path),
        cv2.VideoWriter_fourcc(*"mp4v"),
        fps,
        (width, height),
    )
    if not writer.isOpened():
        capture.release()
        raise RuntimeError(f"Nao foi possivel criar o video anotado: {annotated_path}")
    csv_path = output_dir / f"{video_path.stem}_deteccoes.csv"
    frame_index = 0
    processed_frames = 0
    total_detections = 0
    inference_seconds = 0.0
    detections_by_class = {class_name: 0 for class_name in CLASS_NAMES}
    input_size = int(input_detail["shape"][1])

    try:
        with csv_path.open("w", newline="", encoding="utf-8") as file:
            csv_writer = csv.writer(file)
            csv_writer.writerow(["frame", "timestamp_s", "class_id", "class_name", "confidence", "x1", "y1", "x2", "y2"])
            while True:
                ok, frame = capture.read()
                if not ok:
                    break
                if frame is None or frame.shape[:2] != (height, width):
                    raise RuntimeError(f"Frame {frame_index} invalido ou com resolucao inconsistente.")
                if frame_index % stride == 0:
                    letterboxed, ratio, pad_w, pad_h = letterbox(frame, input_size)
                    image = cv2.cvtColor(letterboxed, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
                    interpreter.set_tensor(input_detail["index"], image[np.newaxis, ...])
                    start = time.perf_counter()
                    interpreter.invoke()
                    inference_seconds += time.perf_counter() - start
                    detections = decode_detections(
                        interpreter.get_tensor(output_detail["index"]), width, height, ratio, pad_w, pad_h,
                        confidence, iou, input_size, coordinates
                    )
                    processed_frames += 1
                    total_detections += len(detections)
                    for class_id, score, x1, y1, x2, y2 in detections:
                        class_name = CLASS_NAMES[class_id]
                        detections_by_class[class_name] += 1
                        csv_writer.writerow([frame_index, frame_index / fps, class_id, class_name, score, x1, y1, x2, y2])
                    draw_detections(frame, detections)
                writer.write(frame)
                frame_index += 1
    finally:
        capture.release()
        writer.release()

    if frame_index == 0:
        raise RuntimeError("O video nao contem frames que puderam ser decodificados.")
    return {
        "video": str(video_path),
        "video_metadata": {"fps": fps, "width": width, "height": height, "reported_frames": reported_frames},
        "frames_decoded": frame_index,
        "processed_frames": processed_frames,
        "detections": total_detections,
        "detections_by_class": {name: count for name, count in detections_by_class.items() if count},
        "average_inference_ms": round(1000 * inference_seconds / processed_frames, 2) if processed_frames else None,
        "annotated_video": str(annotated_path),
        "detections_csv": str(csv_path),
    }


def main() -> None:
    args = parse_args()
    model_path = args.model.expanduser().resolve()
    video_path = args.video.expanduser().resolve()
    output_dir = args.output.expanduser().resolve()
    if not model_path.is_file():
        raise FileNotFoundError(f"Modelo nao encontrado: {model_path}")
    if not video_path.is_file():
        raise FileNotFoundError(f"Video nao encontrado: {video_path}")
    if video_path.suffix.lower() not in VIDEO_EXTENSIONS:
        raise ValueError(f"Formato de video nao suportado: {video_path.suffix}")
    if video_path.stat().st_size == 0:
        raise ValueError(f"O video esta vazio: {video_path}")
    if not 0 < args.confidence <= 1:
        raise ValueError("--confidence deve estar entre 0 (exclusivo) e 1.")
    if not 0 < args.iou <= 1:
        raise ValueError("--iou deve estar entre 0 (exclusivo) e 1.")
    if args.stride < 1:
        raise ValueError("--stride deve ser no minimo 1")

    output_dir.mkdir(parents=True, exist_ok=True)
    interpreter = Interpreter(model_path=str(model_path), num_threads=4)
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    if len(input_details) != 1 or len(output_details) != 1:
        raise RuntimeError("O validador requer um modelo com exatamente uma entrada e uma saida.")
    input_detail = input_details[0]
    output_detail = output_details[0]
    if input_detail["dtype"] != np.float32 or output_detail["dtype"] != np.float32:
        raise RuntimeError("O validador requer um modelo com entrada e saida float32.")
    input_shape = tuple(int(value) for value in input_detail["shape"])
    if len(input_shape) != 4 or input_shape[0] != 1 or input_shape[1] != input_shape[2] or input_shape[3] != 3:
        raise RuntimeError(f"Entrada do modelo incompativel: esperado [1, tamanho, tamanho, 3], recebido {input_shape}.")

    result = process_video(video_path, interpreter, input_detail, output_detail, output_dir, args.confidence, args.iou, args.stride, args.coordinates)
    result["validation_config"] = {
        "model": str(model_path), "confidence": args.confidence, "iou": args.iou, "stride": args.stride,
        "input_shape": input_shape, "coordinates": args.coordinates
    }
    summary_path = output_dir / f"{video_path.stem}_resumo.json"
    summary_path.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print("Videos processados: 1")
    print(f"Resultados: {output_dir}")
    print(f"Resumo: {summary_path}")


if __name__ == "__main__":
    main()
