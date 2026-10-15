from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime
from pathlib import Path

import torch
import yaml
from ultralytics import YOLO


ROOT = Path(__file__).resolve().parent
DEFAULT_DATA = ROOT / "sensewalk_dataset_v3" / "data.yaml"
DEFAULT_MODEL = ROOT / "yolov8s.pt"
DEFAULT_PROJECT = ROOT / "runs_sensewalk"


def parse_args():
    parser = argparse.ArgumentParser(
        description=(
            "Treinamento profissional do SenseWalk com YOLOv8 + CUDA. "
            "Faz treino, salva checkpoints/gráficos e avalia o best.pt no split test."
        )
    )

    parser.add_argument(
        "--data",
        type=Path,
        default=DEFAULT_DATA,
        help="Caminho para data.yaml do dataset.",
    )
    parser.add_argument(
        "--model",
        type=Path,
        default=DEFAULT_MODEL,
        help="Peso inicial, por exemplo yolov8s.pt ou yolov8m.pt.",
    )
    parser.add_argument(
        "--mode",
        choices=["baseline", "camera"],
        default="baseline",
        help=(
            "baseline = experimento V3 atual; "
            "camera = augmentations moderadas para dataset com imagens da câmera real."
        ),
    )
    parser.add_argument("--epochs", type=int, default=150)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument(
        "--batch",
        type=float,
        default=-1,
        help="-1 = batch automático para ~60%% da VRAM. Pode usar inteiro, ex.: 16.",
    )
    parser.add_argument(
        "--workers",
        type=int,
        default=4,
        help="Workers do DataLoader. 4 é um ponto inicial conservador no Windows.",
    )
    parser.add_argument(
        "--device",
        default="0",
        help="GPU CUDA, normalmente 0.",
    )
    parser.add_argument("--patience", type=int, default=30)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--project",
        type=Path,
        default=DEFAULT_PROJECT,
        help="Pasta para os experimentos.",
    )
    parser.add_argument(
        "--name",
        default=None,
        help="Nome da execução. Se omitido, será gerado automaticamente.",
    )
    parser.add_argument(
        "--save-period",
        type=int,
        default=10,
        help="Salvar checkpoint a cada N épocas.",
    )
    parser.add_argument(
        "--cache",
        choices=["false", "disk", "ram"],
        default="false",
        help="Cache do dataset. Para ~100k imagens, comece com false.",
    )
    parser.add_argument(
        "--resume",
        type=Path,
        default=None,
        help="Caminho de last.pt para retomar um treino interrompido.",
    )
    parser.add_argument(
        "--skip-test",
        action="store_true",
        help="Não executar avaliação final no split test.",
    )
    return parser.parse_args()


def check_cuda(device: str):
    print("=" * 80)
    print("SENSEWALK - CUDA / AMBIENTE")
    print("=" * 80)
    print(f"Python:  {sys.version.split()[0]}")
    print(f"PyTorch: {torch.__version__}")
    print(f"CUDA disponível: {torch.cuda.is_available()}")
    print(f"CUDA PyTorch:    {torch.version.cuda}")

    if not torch.cuda.is_available():
        raise RuntimeError(
            "CUDA não está disponível no PyTorch. "
            "Não vou iniciar este treinamento grande pela CPU."
        )

    if device == "cpu":
        raise RuntimeError(
            "Este script foi preparado para CUDA; device=cpu não é recomendado."
        )

    # O caso usual deste projeto é uma GPU: device=0.
    first_device = int(str(device).split(",")[0])
    props = torch.cuda.get_device_properties(first_device)

    total_gb = props.total_memory / (1024 ** 3)

    print(f"GPU:     {props.name}")
    print(f"VRAM:    {total_gb:.2f} GB")
    print(f"Device:  {device}")
    print()


def validate_dataset(data_path: Path):
    if not data_path.exists():
        raise FileNotFoundError(f"data.yaml não encontrado: {data_path}")

    data = yaml.safe_load(data_path.read_text(encoding="utf-8"))

    required = ["train", "val", "test", "names"]
    missing = [key for key in required if key not in data]

    if missing:
        raise ValueError(
            f"data.yaml não possui os campos obrigatórios: {missing}"
        )

    names = data["names"]
    if isinstance(names, dict):
        ordered_names = [names[k] for k in sorted(names, key=lambda x: int(x))]
    else:
        ordered_names = list(names)

    print("=" * 80)
    print("DATASET")
    print("=" * 80)
    print(f"data.yaml: {data_path.resolve()}")
    print(f"Classes:   {len(ordered_names)}")
    for idx, name in enumerate(ordered_names):
        print(f"  {idx:2d}: {name}")
    print()

    return ordered_names


def make_run_name(args):
    if args.name:
        return args.name

    model_name = args.model.stem
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    return f"{model_name}_{args.mode}_{args.imgsz}_{stamp}"


def parse_batch(batch_value: float):
    # argparse retorna float para permitir -1 e frações no futuro.
    if batch_value == -1:
        return -1
    if batch_value >= 1 and float(batch_value).is_integer():
        return int(batch_value)
    return float(batch_value)


def make_train_args(args, run_name: str):
    cache_value = False if args.cache == "false" else args.cache

    train_args = dict(
        data=str(args.data.resolve()),
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=parse_batch(args.batch),
        patience=args.patience,
        device=args.device,
        workers=args.workers,

        optimizer="auto",
        amp=True,

        seed=args.seed,
        deterministic=True,

        cos_lr=True,
        close_mosaic=10,

        save=True,
        save_period=args.save_period,
        plots=True,
        verbose=True,

        cache=cache_value,

        project=str(args.project.resolve()),
        name=run_name,
        exist_ok=False,
    )

    # O baseline usa essencialmente a política padrão do Ultralytics.
    # Não mudamos dezenas de hiperparâmetros antes de medir uma referência.
    if args.mode == "baseline":
        return train_args

    # Modo para usar DEPOIS que o Dataset V4 contiver imagens reais
    # da mini câmera dos óculos.
    #
    # São augmentations geométricas/fotométricas moderadas.
    # Blur/compressão JPEG específicos devem ser feitos como dataset augmentation
    # controlada, não inventados aqui como parâmetros inexistentes do YOLO.
    train_args.update(
        dict(
            degrees=6.0,
            translate=0.10,
            scale=0.35,
            shear=2.0,
            perspective=0.0005,

            hsv_h=0.015,
            hsv_s=0.45,
            hsv_v=0.35,

            fliplr=0.50,
            flipud=0.0,

            mosaic=0.50,
            mixup=0.0,
            close_mosaic=15,
        )
    )

    return train_args


def serializable_results_dict(metrics):
    raw = getattr(metrics, "results_dict", {}) or {}
    result = {}

    for key, value in raw.items():
        try:
            result[str(key)] = float(value)
        except Exception:
            result[str(key)] = str(value)

    box = getattr(metrics, "box", None)

    if box is not None:
        for attr in ("map", "map50", "map75", "mp", "mr"):
            if hasattr(box, attr):
                try:
                    result[f"box_{attr}"] = float(getattr(box, attr))
                except Exception:
                    pass

        maps = getattr(box, "maps", None)
        if maps is not None:
            try:
                result["box_maps_per_class"] = [float(x) for x in maps]
            except Exception:
                pass

    return result


def save_test_summary(
    run_dir: Path,
    test_dir: Path,
    metrics,
    class_names,
):
    payload = {
        "training_run": str(run_dir),
        "test_run": str(test_dir),
        "metrics": serializable_results_dict(metrics),
        "classes": class_names,
    }

    maps = payload["metrics"].get("box_maps_per_class")

    if isinstance(maps, list) and len(maps) == len(class_names):
        payload["map50_95_per_class"] = {
            class_names[i]: maps[i]
            for i in range(len(class_names))
        }

    path = run_dir / "sensewalk_test_summary.json"
    path.write_text(
        json.dumps(payload, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )

    return path


def main():
    args = parse_args()

    args.data = args.data.expanduser()
    args.model = args.model.expanduser()
    args.project = args.project.expanduser()

    check_cuda(args.device)
    class_names = validate_dataset(args.data)

    if args.resume is not None:
        resume_path = args.resume.expanduser().resolve()

        if not resume_path.exists():
            raise FileNotFoundError(f"Checkpoint não encontrado: {resume_path}")

        print("=" * 80)
        print("RETOMANDO TREINAMENTO")
        print("=" * 80)
        print(resume_path)

        model = YOLO(str(resume_path))
        model.train(resume=True)

        print("\nTreinamento retomado/concluído.")
        return

    if not args.model.exists():
        raise FileNotFoundError(
            f"Peso inicial não encontrado: {args.model}\n"
            "Exemplo esperado: C:\\Users\\Admin\\Downloads\\sensewalk\\yolov8s.pt"
        )

    run_name = make_run_name(args)
    args.project.mkdir(parents=True, exist_ok=True)

    print("=" * 80)
    print("CONFIGURAÇÃO DO TREINAMENTO")
    print("=" * 80)
    print(f"Modelo:     {args.model.resolve()}")
    print(f"Modo:       {args.mode}")
    print(f"Épocas:     {args.epochs}")
    print(f"Imagem:     {args.imgsz}")
    print(f"Batch:      {parse_batch(args.batch)}")
    print(f"Workers:    {args.workers}")
    print(f"Patience:   {args.patience}")
    print(f"Seed:       {args.seed}")
    print(f"Run:        {run_name}")
    print(f"Saída:      {args.project.resolve() / run_name}")
    print()

    model = YOLO(str(args.model.resolve()))
    train_args = make_train_args(args, run_name)

    print("Iniciando treinamento...")
    model.train(**train_args)

    trainer = getattr(model, "trainer", None)

    if trainer is None:
        raise RuntimeError(
            "Treinamento terminou, mas não foi possível localizar o trainer."
        )

    run_dir = Path(trainer.save_dir).resolve()
    best_path = Path(trainer.best).resolve()
    last_path = Path(trainer.last).resolve()

    print("\n" + "=" * 80)
    print("TREINAMENTO FINALIZADO")
    print("=" * 80)
    print(f"Run:     {run_dir}")
    print(f"best.pt: {best_path}")
    print(f"last.pt: {last_path}")

    if not best_path.exists():
        raise FileNotFoundError(
            f"O best.pt não foi encontrado em: {best_path}"
        )

    if args.skip_test:
        print("\nTeste final ignorado (--skip-test).")
        return

    print("\n" + "=" * 80)
    print("AVALIAÇÃO FINAL NO SPLIT TEST")
    print("=" * 80)

    best_model = YOLO(str(best_path))

    test_name = f"{run_dir.name}_TEST"

    test_metrics = best_model.val(
        data=str(args.data.resolve()),
        split="test",
        imgsz=args.imgsz,
        batch=parse_batch(args.batch),
        device=args.device,
        workers=args.workers,
        plots=True,
        project=str(args.project.resolve()),
        name=test_name,
        exist_ok=False,
        verbose=True,
    )

    test_dir = Path(test_metrics.save_dir).resolve()

    summary_path = save_test_summary(
        run_dir=run_dir,
        test_dir=test_dir,
        metrics=test_metrics,
        class_names=class_names,
    )

    print("\n" + "=" * 80)
    print("SENSEWALK - EXPERIMENTO CONCLUÍDO")
    print("=" * 80)
    print(f"Treino:           {run_dir}")
    print(f"Teste:            {test_dir}")
    print(f"Melhor modelo:    {best_path}")
    print(f"Resumo do teste:  {summary_path}")
    print()
    print("Arquivos importantes para revisar:")
    print("  results.png")
    print("  results.csv")
    print("  confusion_matrix.png")
    print("  confusion_matrix_normalized.png")
    print("  BoxPR_curve.png")
    print("  BoxF1_curve.png")
    print("  BoxP_curve.png")
    print("  BoxR_curve.png")
    print("  weights/best.pt")
    print()
    print("Não exporte para TFLite ainda.")
    print("Primeiro compare as métricas e gráficos deste baseline.")


if __name__ == "__main__":
    # Necessário no Windows para DataLoader multiprocessing.
    import multiprocessing
    multiprocessing.freeze_support()
    main()
