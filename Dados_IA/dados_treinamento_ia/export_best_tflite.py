"""Exporta o checkpoint SenseWalk para TFLite com pesos quantizados dinamicamente."""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import torch
from ultralytics import YOLO
from ultralytics.engine import exporter as ultralytics_exporter


DATA_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MODEL = DATA_ROOT / "best.pt"
DEFAULT_OUTPUT_DIR = Path(__file__).resolve().parent / "modelos_exportados"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Exporta best.pt para TFLite FP16 e gera um relatorio de compatibilidade."
    )
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--imgsz", type=int, default=640)
    return parser.parse_args()


def ordered_names(names: dict[int, str] | list[str]) -> list[str]:
    if isinstance(names, dict):
        return [str(names[index]) for index in sorted(names)]
    return [str(name) for name in names]


def tensor_details(model_path: Path) -> tuple[list[dict], list[dict]]:
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError as error:
        raise RuntimeError(
            "ai-edge-litert e necessario para validar o TFLite. "
            "Execute: python -m pip install ai-edge-litert"
        ) from error

    interpreter = Interpreter(model_path=str(model_path))
    interpreter.allocate_tensors()

    def simplify(details: list[dict]) -> list[dict]:
        return [
            {
                "name": detail["name"],
                "shape": detail["shape"].tolist(),
                "dtype": detail["dtype"].__name__,
                "quantization": list(detail["quantization"]),
            }
            for detail in details
        ]

    return simplify(interpreter.get_input_details()), simplify(interpreter.get_output_details())


def disable_tflite_metadata_export() -> None:
    """Evita o tflite-support, que nao oferece wheel para Python 3.12 no Windows.

    O Android nao utiliza os metadados opcionais adicionados por esse pacote; os
    tensores e o FlatBuffer gerados pelo onnx2tf permanecem os mesmos.
    """
    original_check_requirements = ultralytics_exporter.check_requirements

    def check_requirements_without_metadata(requirements, *args, **kwargs):
        if isinstance(requirements, (list, tuple)):
            requirements = tuple(
                requirement
                for requirement in requirements
                if not str(requirement).startswith(("tflite_support", "onnx2tf"))
            )
        return original_check_requirements(requirements, *args, **kwargs)

    ultralytics_exporter.check_requirements = check_requirements_without_metadata
    ultralytics_exporter.Exporter._add_tflite_metadata = lambda self, file: None


def use_legacy_onnx_exporter() -> None:
    """Evita a conversao automatica para ONNX opset 18 do PyTorch recente."""
    original_export = torch.onnx.export

    def export_with_legacy_path(*args, **kwargs):
        kwargs["dynamo"] = False
        return original_export(*args, **kwargs)

    torch.onnx.export = export_with_legacy_path


def enable_dynamic_range_quantization() -> None:
    """Quantiza pesos para INT8, preservando entrada e saida float32."""
    import onnx2tf

    original_convert = onnx2tf.convert

    def convert_with_dynamic_range_quantization(*args, **kwargs):
        kwargs["output_dynamic_range_quantized_tflite"] = True
        return original_convert(*args, **kwargs)

    onnx2tf.convert = convert_with_dynamic_range_quantization


def main() -> None:
    args = parse_args()
    model_path = args.model.expanduser().resolve()
    output_dir = args.output_dir.expanduser().resolve()

    if not model_path.is_file():
        raise FileNotFoundError(f"Checkpoint nao encontrado: {model_path}")

    output_dir.mkdir(parents=True, exist_ok=True)
    disable_tflite_metadata_export()
    use_legacy_onnx_exporter()
    enable_dynamic_range_quantization()
    model = YOLO(str(model_path))
    names = ordered_names(model.names)

    # A quantizacao dinamica reduz os pesos para INT8 sem precisar de imagens de calibracao.
    # A entrada e a saida continuam float32, como espera o detector Android atual.
    generated_tflite = model_path.with_name(f"{model_path.stem}_saved_model") / (
        f"{model_path.stem}_dynamic_range_quant.tflite"
    )
    try:
        exported_path = Path(
            model.export(format="tflite", imgsz=args.imgsz, half=False, nms=False, opset=13)
        ).resolve()
    except OSError:
        # onnx2tf 2.x escreve o TFLite diretamente, sem o SavedModel que o
        # exportador legado do Ultralytics ainda tenta carregar ao final.
        if not generated_tflite.is_file():
            raise
        exported_path = generated_tflite.resolve()
    output_path = output_dir / "best_dynamic.tflite"
    shutil.copy2(exported_path, output_path)

    inputs, outputs = tensor_details(output_path)
    float_io = all(tensor["dtype"] == "float32" for tensor in inputs + outputs)
    float32_tflite = output_path.parent.parent.parent / "best_saved_model" / "best_float32.tflite"
    float32_size = float32_tflite.stat().st_size if float32_tflite.is_file() else None
    report = {
        "source_model": str(model_path),
        "source_size_bytes": model_path.stat().st_size,
        "exported_model": str(output_path),
        "exported_size_bytes": output_path.stat().st_size,
        "size_reduction_percent": round(
            (1 - output_path.stat().st_size / model_path.stat().st_size) * 100, 2
        ),
        "float32_tflite_size_bytes": float32_size,
        "reduction_vs_float32_tflite_percent": (
            round((1 - output_path.stat().st_size / float32_size) * 100, 2)
            if float32_size is not None
            else None
        ),
        "imgsz": args.imgsz,
        "classes": names,
        "class_count": len(names),
        "inputs": inputs,
        "outputs": outputs,
        "android_float_io_compatible": float_io,
        "android_app_class_count": 9,
        "android_class_mapping_required": len(names) != 9,
    }
    report_path = output_dir / "best_dynamic_report.json"
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")

    print(f"TFLite dinamico: {output_path}")
    print(f"Relatorio:   {report_path}")
    print(f"Classes:     {len(names)}")
    print(f"Tamanho:     {report['source_size_bytes']} -> {report['exported_size_bytes']} bytes")
    print(f"Entrada/saida float32 compativel com Android: {float_io}")
    if report["android_class_mapping_required"]:
        print("AVISO: o modelo tem mais classes que as 9 configuradas no aplicativo.")


if __name__ == "__main__":
    main()
