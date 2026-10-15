from __future__ import annotations

import csv
import hashlib
import json
import os
import shutil
import sys
from collections import Counter, defaultdict
from pathlib import Path

try:
    import yaml
    from PIL import Image
except ImportError:
    print(
        "\nDependências ausentes.\n"
        "Ative seu .venv e rode:\n"
        "    pip install pillow pyyaml\n"
    )
    raise


# ============================================================
# CONFIGURAÇÃO
# ============================================================

ROOT = Path(__file__).resolve().parent
ARCHIVE = ROOT / "archive"
OUTPUT = ROOT / "sensewalk_dataset_v1"

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}

# Classes finais do SenseWalk Dataset v1
CLASSES = [
    "car",                      # 0
    "motorcycle",               # 1
    "bicycle",                  # 2
    "truck",                    # 3
    "bus",                      # 4
    "traffic-light-green",      # 5
    "traffic-light-red",        # 6
    "traffic-light-yellow",     # 7
    "crosswalk",                # 8
    "pedestrian-light-green",   # 9
    "pedestrian-light-red",     # 10
]

CLASS_TO_ID = {name: idx for idx, name in enumerate(CLASSES)}

# Estatísticas
stats_images = Counter()
stats_objects = Counter()
stats_objects_by_split = defaultdict(Counter)
stats_source = Counter()
warnings: list[str] = []


# ============================================================
# UTILIDADES
# ============================================================

def normalize_name(name: str) -> str:
    return (
        str(name)
        .strip()
        .lower()
        .replace("_", "-")
        .replace(" ", "-")
    )


def reset_output() -> None:
    if OUTPUT.exists():
        print(f"\n[INFO] Removendo dataset anterior: {OUTPUT}")
        shutil.rmtree(OUTPUT)

    for split in ("train", "val", "test"):
        (OUTPUT / "images" / split).mkdir(parents=True, exist_ok=True)
        (OUTPUT / "labels" / split).mkdir(parents=True, exist_ok=True)


def safe_link_or_copy(src: Path, dst: Path) -> None:
    """
    Tenta criar hardlink primeiro para economizar espaço em disco.
    Se não for possível, copia o arquivo normalmente.
    """
    dst.parent.mkdir(parents=True, exist_ok=True)

    if dst.exists():
        dst.unlink()

    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def find_image_by_name(directory: Path, filename: str) -> Path | None:
    direct = directory / filename
    if direct.exists():
        return direct

    stem = Path(filename).stem
    for ext in IMAGE_EXTENSIONS:
        candidate = directory / f"{stem}{ext}"
        if candidate.exists():
            return candidate

    return None


def yolo_box(
    x1: float,
    y1: float,
    x2: float,
    y2: float,
    img_w: int,
    img_h: int,
) -> tuple[float, float, float, float] | None:
    if img_w <= 0 or img_h <= 0:
        return None

    x1 = max(0.0, min(float(x1), float(img_w)))
    y1 = max(0.0, min(float(y1), float(img_h)))
    x2 = max(0.0, min(float(x2), float(img_w)))
    y2 = max(0.0, min(float(y2), float(img_h)))

    if x2 <= x1 or y2 <= y1:
        return None

    width = x2 - x1
    height = y2 - y1

    if width < 1.0 or height < 1.0:
        return None

    x_center = ((x1 + x2) / 2.0) / img_w
    y_center = ((y1 + y2) / 2.0) / img_h
    norm_w = width / img_w
    norm_h = height / img_h

    values = (x_center, y_center, norm_w, norm_h)

    if not all(0.0 <= value <= 1.0 for value in values):
        return None

    return values


def write_label(path: Path, lines: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        if lines:
            handle.write("\n".join(lines) + "\n")


def output_names(prefix: str, original_image_name: str) -> tuple[str, str]:
    image_name = f"{prefix}_{Path(original_image_name).name}"
    label_name = f"{Path(image_name).stem}.txt"
    return image_name, label_name


# ============================================================
# BDD100K
# ============================================================

def find_bdd_image_split(split: str) -> Path | None:
    """
    Procura algo equivalente a:
      archive/bdd100k/bdd100k/images/100k/train
      archive/bdd100k/bdd100k/images/100k/val
    """
    candidates = []

    for directory in ARCHIVE.rglob(split):
        if not directory.is_dir():
            continue

        normalized = str(directory).lower().replace("\\", "/")

        if "/images/100k/" in normalized:
            candidates.append(directory)

    if not candidates:
        return None

    # Prefere caminho que contenha bdd100k
    candidates.sort(
        key=lambda p: (
            "bdd100k" not in str(p).lower(),
            len(str(p)),
        )
    )
    return candidates[0]


def find_bdd_json(split: str) -> Path | None:
    """
    Compatível com nomes como:
      bdd100k_labels_images_train.json
      bdd100k_labels_images_val.json
      det_train.json
      det_val.json
    """
    json_files = list(ARCHIVE.rglob("*.json"))

    preferred_tokens = {
        "train": ("train",),
        "val": ("val", "valid"),
    }[split]

    matches = []

    for path in json_files:
        name = path.name.lower()

        if not any(token in name for token in preferred_tokens):
            continue

        # Queremos JSON de labels de imagens/detecção, não segmentação.
        normalized = str(path).lower().replace("\\", "/")

        if "seg" in normalized:
            continue

        score = 0
        if "bdd100k" in name:
            score += 5
        if "labels_images" in name:
            score += 5
        if "det_" in name or "det" in name:
            score += 3
        if "labels" in normalized:
            score += 2
        if "bdd100k" in normalized:
            score += 2

        matches.append((score, path))

    if not matches:
        return None

    matches.sort(key=lambda item: (-item[0], len(str(item[1]))))
    return matches[0][1]


def map_bdd_label(label: dict) -> str | None:
    category = normalize_name(label.get("category", ""))

    if category == "car":
        return "car"

    if category in {"motor", "motorcycle"}:
        return "motorcycle"

    if category in {"bike", "bicycle"}:
        return "bicycle"

    if category == "truck":
        return "truck"

    if category == "bus":
        return "bus"

    if category == "traffic-light":
        attributes = label.get("attributes") or {}
        color = normalize_name(attributes.get("trafficLightColor", ""))

        if color == "green":
            return "traffic-light-green"
        if color == "red":
            return "traffic-light-red"
        if color == "yellow":
            return "traffic-light-yellow"

    # traffic sign, lane, drivable area, person etc. são ignorados.
    return None


def bdd_val_to_final_split(filename: str) -> str:
    """
    O BDD100K não fornece labels públicos do test oficial.
    Então dividimos deterministicamente o VAL anotado:
      ~50% continua val
      ~50% vira nosso test
    """
    digest = hashlib.md5(filename.encode("utf-8")).digest()
    return "val" if digest[0] < 128 else "test"


def process_bdd_json(
    json_path: Path,
    image_dir: Path,
    source_split: str,
) -> None:
    print(f"\n[BDD100K] Lendo {source_split}:")
    print(f"  JSON:    {json_path}")
    print(f"  Imagens: {image_dir}")

    with json_path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)

    total = len(data)

    for index, item in enumerate(data, start=1):
        filename = item.get("name")

        if not filename:
            warnings.append("BDD: item sem campo 'name'.")
            continue

        src_image = find_image_by_name(image_dir, filename)

        if src_image is None:
            warnings.append(f"BDD: imagem ausente: {filename}")
            continue

        final_split = (
            "train"
            if source_split == "train"
            else bdd_val_to_final_split(filename)
        )

        try:
            with Image.open(src_image) as image:
                img_w, img_h = image.size
        except Exception as exc:
            warnings.append(f"BDD: imagem inválida {src_image}: {exc}")
            continue

        yolo_lines: list[str] = []

        for label in item.get("labels", []):
            target_class = map_bdd_label(label)

            if target_class is None:
                continue

            box = label.get("box2d")
            if not box:
                continue

            converted = yolo_box(
                box.get("x1", 0),
                box.get("y1", 0),
                box.get("x2", 0),
                box.get("y2", 0),
                img_w,
                img_h,
            )

            if converted is None:
                warnings.append(
                    f"BDD: bbox inválida em {filename}, classe {target_class}"
                )
                continue

            class_id = CLASS_TO_ID[target_class]
            xc, yc, width, height = converted

            yolo_lines.append(
                f"{class_id} {xc:.6f} {yc:.6f} {width:.6f} {height:.6f}"
            )

            stats_objects[target_class] += 1
            stats_objects_by_split[final_split][target_class] += 1

        # Só inclui imagens BDD que contenham ao menos uma classe-alvo.
        # Assim não criamos "negativos" acidentais nesta etapa.
        if not yolo_lines:
            continue

        out_image_name, out_label_name = output_names("bdd", filename)

        safe_link_or_copy(
            src_image,
            OUTPUT / "images" / final_split / out_image_name,
        )
        write_label(
            OUTPUT / "labels" / final_split / out_label_name,
            yolo_lines,
        )

        stats_images[final_split] += 1
        stats_source[f"bdd_{final_split}"] += 1

        if index % 5000 == 0 or index == total:
            print(f"  {index:,}/{total:,} registros lidos")


def process_bdd100k() -> None:
    train_images = find_bdd_image_split("train")
    val_images = find_bdd_image_split("val")
    train_json = find_bdd_json("train")
    val_json = find_bdd_json("val")

    print("\n" + "=" * 70)
    print("BDD100K")
    print("=" * 70)
    print("Train images:", train_images)
    print("Val images:  ", val_images)
    print("Train JSON:  ", train_json)
    print("Val JSON:    ", val_json)

    if train_images is None:
        raise FileNotFoundError(
            "Não encontrei a pasta BDD100K images/100k/train dentro de archive."
        )

    if val_images is None:
        raise FileNotFoundError(
            "Não encontrei a pasta BDD100K images/100k/val dentro de archive."
        )

    if train_json is None:
        raise FileNotFoundError(
            "Não encontrei o JSON TRAIN do BDD100K dentro de archive."
        )

    if val_json is None:
        raise FileNotFoundError(
            "Não encontrei o JSON VAL do BDD100K dentro de archive."
        )

    process_bdd_json(train_json, train_images, "train")
    process_bdd_json(val_json, val_images, "val")


# ============================================================
# ROBOFLOW / YOLO
# ============================================================

def load_yaml_names(dataset_dir: Path) -> list[str]:
    yaml_path = dataset_dir / "data.yaml"

    if not yaml_path.exists():
        raise FileNotFoundError(f"Sem data.yaml: {dataset_dir}")

    with yaml_path.open("r", encoding="utf-8") as handle:
        data = yaml.safe_load(handle)

    names = data.get("names")

    if names is None:
        raise ValueError(f"'names' ausente em {yaml_path}")

    if isinstance(names, dict):
        return [names[key] for key in sorted(names, key=lambda x: int(x))]

    return list(names)


def find_dataset_by_folder_token(token: str) -> Path | None:
    token = token.lower()

    candidates = []
    for yaml_path in ROOT.rglob("data.yaml"):
        normalized = str(yaml_path).lower().replace("\\", "/")

        # Não procurar dentro do dataset final, venv ou caches.
        if "/sensewalk_dataset_v1/" in normalized:
            continue
        if "/.venv/" in normalized:
            continue
        if "/site-packages/" in normalized:
            continue

        parent = yaml_path.parent
        if token in parent.name.lower():
            candidates.append(parent)

    if not candidates:
        return None

    candidates.sort(key=lambda p: len(str(p)))
    return candidates[0]


def find_pedestrian_dataset() -> Path | None:
    candidates = []

    for yaml_path in ROOT.rglob("data.yaml"):
        normalized = str(yaml_path).lower().replace("\\", "/")

        if "/sensewalk_dataset_v1/" in normalized:
            continue
        if "/.venv/" in normalized:
            continue
        if "/site-packages/" in normalized:
            continue

        parent = yaml_path.parent
        if "pedestrian" in parent.name.lower():
            candidates.append(parent)

    if not candidates:
        return None

    candidates.sort(key=lambda p: len(str(p)))
    return candidates[0]


def source_split_dir(dataset_dir: Path, final_split: str) -> Path | None:
    aliases = {
        "train": ["train"],
        "val": ["valid", "val"],
        "test": ["test"],
    }

    for alias in aliases[final_split]:
        candidate = dataset_dir / alias
        if candidate.exists():
            return candidate

    return None


def process_yolo_dataset(
    dataset_dir: Path,
    prefix: str,
    class_mapping: dict[str, str | None],
) -> None:
    print("\n" + "=" * 70)
    print(f"YOLO / Roboflow: {dataset_dir.name}")
    print("=" * 70)

    source_names = load_yaml_names(dataset_dir)

    print("Classes originais:")
    for idx, name in enumerate(source_names):
        print(f"  {idx}: {name}")

    for final_split in ("train", "val", "test"):
        split_dir = source_split_dir(dataset_dir, final_split)

        if split_dir is None:
            print(f"  [{final_split}] split ausente -> ignorado.")
            continue

        images_dir = split_dir / "images"
        labels_dir = split_dir / "labels"

        if not images_dir.exists() or not labels_dir.exists():
            print(
                f"  [{final_split}] images/labels não encontrados -> ignorado."
            )
            continue

        images = sorted(
            path
            for path in images_dir.iterdir()
            if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS
        )

        print(f"  [{final_split}] {len(images):,} imagens")

        for src_image in images:
            src_label = labels_dir / f"{src_image.stem}.txt"

            if not src_label.exists():
                warnings.append(
                    f"{dataset_dir.name}: imagem sem label: {src_image.name}"
                )
                continue

            with src_label.open("r", encoding="utf-8") as handle:
                raw_lines = [
                    line.strip()
                    for line in handle
                    if line.strip()
                ]

            converted_lines: list[str] = []

            for line in raw_lines:
                parts = line.split()

                if len(parts) != 5:
                    warnings.append(
                        f"{src_label}: linha não é bbox YOLO de 5 campos."
                    )
                    continue

                try:
                    source_id = int(float(parts[0]))
                    coords = [float(value) for value in parts[1:5]]
                except ValueError:
                    warnings.append(f"{src_label}: linha inválida: {line}")
                    continue

                if source_id < 0 or source_id >= len(source_names):
                    warnings.append(
                        f"{src_label}: class_id fora do data.yaml: {source_id}"
                    )
                    continue

                source_class = normalize_name(source_names[source_id])
                target_class = class_mapping.get(source_class)

                # Classe propositalmente ignorada.
                if target_class is None:
                    continue

                if target_class not in CLASS_TO_ID:
                    raise ValueError(
                        f"Mapeamento aponta para classe final inexistente: "
                        f"{target_class}"
                    )

                if not all(0.0 <= value <= 1.0 for value in coords):
                    warnings.append(
                        f"{src_label}: bbox fora de 0..1 -> {coords}"
                    )
                    continue

                target_id = CLASS_TO_ID[target_class]

                converted_lines.append(
                    f"{target_id} "
                    + " ".join(f"{value:.6f}" for value in coords)
                )

                stats_objects[target_class] += 1
                stats_objects_by_split[final_split][target_class] += 1

            # Se havia anotações, mas TODAS pertenciam a classes que
            # decidimos ignorar, não convertemos a imagem em negativo.
            if raw_lines and not converted_lines:
                continue

            # Label originalmente vazio = negativo legítimo daquele dataset.
            out_image_name, out_label_name = output_names(
                prefix,
                src_image.name,
            )

            safe_link_or_copy(
                src_image,
                OUTPUT / "images" / final_split / out_image_name,
            )
            write_label(
                OUTPUT / "labels" / final_split / out_label_name,
                converted_lines,
            )

            stats_images[final_split] += 1
            stats_source[f"{prefix}_{final_split}"] += 1


def process_crosswalk_traffic_lights() -> None:
    # Pelo seu print, este é o dataset correto:
    # RF_DETR.v2i.yolov8
    dataset = find_dataset_by_folder_token("rf_detr")

    if dataset is None:
        raise FileNotFoundError(
            "Não encontrei RF_DETR.v2i.yolov8 com data.yaml na pasta do projeto."
        )

    mapping = {
        "crosswalk": "crosswalk",
        "green-traffic-light": "traffic-light-green",
        "red-traffic-light": "traffic-light-red",
    }

    process_yolo_dataset(
        dataset_dir=dataset,
        prefix="crosswalk",
        class_mapping=mapping,
    )


def process_pedestrian_traffic_lights() -> None:
    dataset = find_pedestrian_dataset()

    if dataset is None:
        raise FileNotFoundError(
            "Não encontrei o dataset de pedestrian traffic light com data.yaml."
        )

    # data.yaml mostrado por você:
    # ['go', 'green', 'green_pedestrian_light', 'not-go',
    #  'pedestrian Traffic Light', 'red',
    #  'red_pedestrian_light', 'signal-light']
    mapping = {
        "go": "pedestrian-light-green",
        "green": "pedestrian-light-green",
        "green-pedestrian-light": "pedestrian-light-green",

        "not-go": "pedestrian-light-red",
        "red": "pedestrian-light-red",
        "red-pedestrian-light": "pedestrian-light-red",

        # Sem cor explícita -> descartamos nesta versão.
        "pedestrian-traffic-light": None,
        "signal-light": None,
    }

    process_yolo_dataset(
        dataset_dir=dataset,
        prefix="pedlight",
        class_mapping=mapping,
    )


# ============================================================
# DATA.YAML FINAL
# ============================================================

def write_final_yaml() -> None:
    data = {
        "path": str(OUTPUT.resolve()).replace("\\", "/"),
        "train": "images/train",
        "val": "images/val",
        "test": "images/test",
        "names": {
            idx: name
            for idx, name in enumerate(CLASSES)
        },
    }

    with (OUTPUT / "data.yaml").open("w", encoding="utf-8") as handle:
        yaml.safe_dump(
            data,
            handle,
            sort_keys=False,
            allow_unicode=True,
        )


# ============================================================
# VALIDAÇÃO
# ============================================================

def verify_dataset() -> list[str]:
    problems: list[str] = []

    for split in ("train", "val", "test"):
        images_dir = OUTPUT / "images" / split
        labels_dir = OUTPUT / "labels" / split

        image_stems = {
            path.stem
            for path in images_dir.iterdir()
            if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS
        }

        label_stems = {
            path.stem
            for path in labels_dir.glob("*.txt")
        }

        missing_labels = image_stems - label_stems
        missing_images = label_stems - image_stems

        if missing_labels:
            problems.append(
                f"{split}: {len(missing_labels)} imagens sem label."
            )

        if missing_images:
            problems.append(
                f"{split}: {len(missing_images)} labels sem imagem."
            )

        for label_file in labels_dir.glob("*.txt"):
            with label_file.open("r", encoding="utf-8") as handle:
                for line_number, line in enumerate(handle, start=1):
                    line = line.strip()

                    if not line:
                        continue

                    parts = line.split()

                    if len(parts) != 5:
                        problems.append(
                            f"{label_file}:{line_number} -> formato inválido."
                        )
                        continue

                    try:
                        class_id = int(parts[0])
                        coords = [float(value) for value in parts[1:]]
                    except ValueError:
                        problems.append(
                            f"{label_file}:{line_number} -> valor inválido."
                        )
                        continue

                    if not (0 <= class_id < len(CLASSES)):
                        problems.append(
                            f"{label_file}:{line_number} -> "
                            f"classe inválida {class_id}."
                        )

                    if not all(0.0 <= value <= 1.0 for value in coords):
                        problems.append(
                            f"{label_file}:{line_number} -> bbox fora de 0..1."
                        )

                    if coords[2] <= 0 or coords[3] <= 0:
                        problems.append(
                            f"{label_file}:{line_number} -> "
                            "largura/altura <= 0."
                        )

    return problems


# ============================================================
# RELATÓRIOS
# ============================================================

def write_class_csv() -> None:
    csv_path = OUTPUT / "class_distribution.csv"

    with csv_path.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            ["class_id", "class_name", "train", "val", "test", "total"]
        )

        for class_id, class_name in enumerate(CLASSES):
            train_count = stats_objects_by_split["train"][class_name]
            val_count = stats_objects_by_split["val"][class_name]
            test_count = stats_objects_by_split["test"][class_name]

            writer.writerow(
                [
                    class_id,
                    class_name,
                    train_count,
                    val_count,
                    test_count,
                    train_count + val_count + test_count,
                ]
            )


def write_report(problems: list[str]) -> None:
    lines: list[str] = []

    lines.append("=" * 72)
    lines.append("SENSEWALK DATASET V1 - RELATÓRIO")
    lines.append("=" * 72)
    lines.append("")

    lines.append("IMAGENS POR SPLIT")
    lines.append("-" * 72)
    for split in ("train", "val", "test"):
        lines.append(f"{split:<10} {stats_images[split]:>10,}")

    lines.append("")
    lines.append("OBJETOS POR CLASSE")
    lines.append("-" * 72)

    for class_id, class_name in enumerate(CLASSES):
        train_count = stats_objects_by_split["train"][class_name]
        val_count = stats_objects_by_split["val"][class_name]
        test_count = stats_objects_by_split["test"][class_name]
        total = train_count + val_count + test_count

        lines.append(
            f"{class_id:2d} {class_name:<28} "
            f"train={train_count:>8,} "
            f"val={val_count:>7,} "
            f"test={test_count:>7,} "
            f"total={total:>9,}"
        )

    lines.append("")
    lines.append("IMAGENS POR ORIGEM")
    lines.append("-" * 72)

    for source, count in sorted(stats_source.items()):
        lines.append(f"{source:<35} {count:>10,}")

    lines.append("")
    lines.append("VALIDAÇÃO ESTRUTURAL")
    lines.append("-" * 72)

    if problems:
        lines.append(f"Foram encontrados {len(problems)} problema(s).")
        for problem in problems[:100]:
            lines.append(f"ERRO: {problem}")

        if len(problems) > 100:
            lines.append(
                f"... e mais {len(problems) - 100} problema(s)."
            )
    else:
        lines.append("Nenhum problema estrutural encontrado.")

    lines.append("")
    lines.append("WARNINGS")
    lines.append("-" * 72)

    if warnings:
        lines.append(f"Foram registrados {len(warnings)} warning(s).")
        for warning in warnings[:100]:
            lines.append(f"AVISO: {warning}")

        if len(warnings) > 100:
            lines.append(
                f"... e mais {len(warnings) - 100} warning(s)."
            )
    else:
        lines.append("Nenhum warning.")

    report = "\n".join(lines)

    report_path = OUTPUT / "dataset_report.txt"
    report_path.write_text(report, encoding="utf-8")

    print("\n" + report)


# ============================================================
# MAIN
# ============================================================

def main() -> None:
    print("=" * 72)
    print("SENSEWALK DATASET BUILDER V1")
    print("=" * 72)
    print(f"ROOT:    {ROOT}")
    print(f"ARCHIVE: {ARCHIVE}")
    print(f"OUTPUT:  {OUTPUT}")

    if not ARCHIVE.exists():
        print(
            "\nERRO: pasta 'archive' não encontrada ao lado deste script.\n"
            "Estrutura esperada:\n"
            "  sensewalk/\n"
            "    merge_sensewalk_dataset.py\n"
            "    archive/\n"
        )
        sys.exit(1)

    reset_output()

    print("\n[1/3] Convertendo BDD100K...")
    process_bdd100k()

    print("\n[2/3] Importando RF_DETR (Crosswalk + Traffic Lights)...")
    process_crosswalk_traffic_lights()

    print("\n[3/3] Importando Pedestrian Traffic Light...")
    process_pedestrian_traffic_lights()

    print("\n[FINAL] Criando data.yaml...")
    write_final_yaml()

    print("[FINAL] Validando dataset...")
    problems = verify_dataset()

    print("[FINAL] Gerando relatórios...")
    write_class_csv()
    write_report(problems)

    print("\n" + "=" * 72)
    print("CONCLUÍDO")
    print("=" * 72)
    print(f"Dataset: {OUTPUT}")
    print(f"YAML:    {OUTPUT / 'data.yaml'}")
    print(f"Report:  {OUTPUT / 'dataset_report.txt'}")
    print(f"CSV:     {OUTPUT / 'class_distribution.csv'}")
    print("\nNÃO inicie o treinamento ainda.")
    print("Primeiro revise dataset_report.txt e class_distribution.csv.")


if __name__ == "__main__":
    main()
