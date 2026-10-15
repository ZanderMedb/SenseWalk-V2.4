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
        "Ative o .venv e rode:\n"
        "    pip install pillow pyyaml\n"
    )
    raise


# ============================================================
# CONFIGURAÇÃO
# ============================================================

ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / "sensewalk_dataset_v2"

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}

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

stats_images = Counter()
stats_objects = Counter()
stats_objects_by_split = defaultdict(Counter)
stats_source = Counter()
warnings: list[str] = []


# ============================================================
# UTILIDADES
# ============================================================

def normalize_name(value: str) -> str:
    return (
        str(value)
        .strip()
        .lower()
        .replace("_", "-")
        .replace(" ", "-")
    )


def count_images(folder: Path) -> int:
    if not folder.exists():
        return 0
    return sum(
        1 for p in folder.iterdir()
        if p.is_file() and p.suffix.lower() in IMAGE_EXTENSIONS
    )


def image_names(folder: Path) -> set[str]:
    if not folder.exists():
        return set()
    return {
        p.name for p in folder.iterdir()
        if p.is_file() and p.suffix.lower() in IMAGE_EXTENSIONS
    }


def reset_output() -> None:
    if OUTPUT.exists():
        print(f"\n[INFO] Removendo dataset anterior: {OUTPUT}")
        shutil.rmtree(OUTPUT)

    for split in ("train", "val", "test"):
        (OUTPUT / "images" / split).mkdir(parents=True, exist_ok=True)
        (OUTPUT / "labels" / split).mkdir(parents=True, exist_ok=True)


def safe_link_or_copy(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)

    if dst.exists():
        dst.unlink()

    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def write_label(path: Path, lines: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        ("\n".join(lines) + "\n") if lines else "",
        encoding="utf-8",
    )


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

    xc = ((x1 + x2) / 2.0) / img_w
    yc = ((y1 + y2) / 2.0) / img_h
    nw = width / img_w
    nh = height / img_h

    if not all(0.0 <= v <= 1.0 for v in (xc, yc, nw, nh)):
        return None

    return xc, yc, nw, nh


def make_output_names(prefix: str, original: str) -> tuple[str, str]:
    image_name = f"{prefix}_{Path(original).name}"
    label_name = f"{Path(image_name).stem}.txt"
    return image_name, label_name


# ============================================================
# LOCALIZAR O BDD100K NOVO
# ============================================================

def find_bdd_roots() -> list[Path]:
    """
    Procura pastas com a estrutura:
      <root>/train/images
      <root>/train/annotations/bdd100k_labels_images_train.json
      <root>/val/images
      <root>/val/annotations/bdd100k_labels_images_val.json
    """
    roots = []

    for train_json in ROOT.rglob("bdd100k_labels_images_train.json"):
        s = str(train_json).lower().replace("\\", "/")

        if "/.venv/" in s:
            continue
        if "/sensewalk_dataset_" in s:
            continue

        # train/annotations/file.json -> root
        try:
            candidate = train_json.parent.parent.parent
        except Exception:
            continue

        val_json = (
            candidate
            / "val"
            / "annotations"
            / "bdd100k_labels_images_val.json"
        )

        train_images = candidate / "train" / "images"
        val_images = candidate / "val" / "images"

        if (
            val_json.exists()
            and train_images.exists()
            and val_images.exists()
        ):
            roots.append(candidate)

    # evita duplicatas
    unique = []
    seen = set()

    for root in roots:
        resolved = root.resolve()
        if resolved not in seen:
            seen.add(resolved)
            unique.append(root)

    return unique


def choose_best_bdd_root() -> Path:
    roots = find_bdd_roots()

    if not roots:
        raise FileNotFoundError(
            "Não encontrei um BDD100K com train/images + train/annotations "
            "e val/images + val/annotations."
        )

    ranked = sorted(
        roots,
        key=lambda p: count_images(p / "train" / "images"),
        reverse=True,
    )

    print("\nBDD100K encontrados:")
    for root in ranked:
        print(
            f"  train={count_images(root / 'train' / 'images'):>7,} "
            f"val={count_images(root / 'val' / 'images'):>7,} "
            f"-> {root}"
        )

    selected = ranked[0]
    print(f"\nBDD100K selecionado automaticamente:\n  {selected}")
    return selected


# ============================================================
# PRÉ-VALIDAÇÃO BDD100K
# ============================================================

def load_json(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def preflight_bdd(bdd_root: Path) -> None:
    train_images_dir = bdd_root / "train" / "images"
    val_images_dir = bdd_root / "val" / "images"

    train_json = (
        bdd_root
        / "train"
        / "annotations"
        / "bdd100k_labels_images_train.json"
    )
    val_json = (
        bdd_root
        / "val"
        / "annotations"
        / "bdd100k_labels_images_val.json"
    )

    train_data = load_json(train_json)
    val_data = load_json(val_json)

    train_json_names = {
        item.get("name") for item in train_data if item.get("name")
    }
    val_json_names = {
        item.get("name") for item in val_data if item.get("name")
    }

    train_files = image_names(train_images_dir)
    val_files = image_names(val_images_dir)

    train_matches = len(train_json_names & train_files)
    val_matches = len(val_json_names & val_files)

    train_pct = (
        train_matches / len(train_json_names) * 100
        if train_json_names else 0
    )
    val_pct = (
        val_matches / len(val_json_names) * 100
        if val_json_names else 0
    )

    print("\n" + "=" * 72)
    print("PRÉ-VALIDAÇÃO BDD100K")
    print("=" * 72)
    print(
        f"TRAIN: JSON={len(train_json_names):,} | "
        f"imagens={len(train_files):,} | "
        f"matches={train_matches:,} ({train_pct:.2f}%)"
    )
    print(
        f"VAL:   JSON={len(val_json_names):,} | "
        f"imagens={len(val_files):,} | "
        f"matches={val_matches:,} ({val_pct:.2f}%)"
    )

    # Impede gerar um dataset ruim sem perceber.
    if train_pct < 95.0:
        raise RuntimeError(
            "\nBDD100K TRAIN incompatível/incompleto. "
            f"Apenas {train_pct:.2f}% dos labels possuem imagem.\n"
            "Não vou continuar o merge."
        )

    if val_pct < 95.0:
        raise RuntimeError(
            "\nBDD100K VAL incompatível/incompleto. "
            f"Apenas {val_pct:.2f}% dos labels possuem imagem.\n"
            "Não vou continuar o merge."
        )

    print("\nBDD100K aprovado na pré-validação.")


# ============================================================
# BDD100K -> CLASSES SENSEWALK
# ============================================================

def map_bdd_box_class(label: dict) -> str | None:
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
        color = normalize_name(
            attributes.get("trafficLightColor", "")
        )

        if color == "green":
            return "traffic-light-green"
        if color == "red":
            return "traffic-light-red"
        if color == "yellow":
            return "traffic-light-yellow"

    return None


def crosswalk_bbox_from_bdd_label(
    label: dict,
    img_w: int,
    img_h: int,
) -> tuple[float, float, float, float] | None:
    """
    Tenta converter anotações BDD laneType=crosswalk (poly2d)
    em bounding box YOLO.

    Se o JSON não contiver crosswalk desse tipo, simplesmente
    não cria nenhuma box.
    """
    category = normalize_name(label.get("category", ""))

    if category != "lane":
        return None

    attrs = label.get("attributes") or {}
    lane_type = normalize_name(attrs.get("laneType", ""))

    if lane_type != "crosswalk":
        return None

    poly_items = label.get("poly2d") or []

    xs = []
    ys = []

    for poly in poly_items:
        for vertex in poly.get("vertices", []):
            if not isinstance(vertex, (list, tuple)) or len(vertex) < 2:
                continue

            try:
                x = float(vertex[0])
                y = float(vertex[1])
            except (TypeError, ValueError):
                continue

            xs.append(x)
            ys.append(y)

    if not xs or not ys:
        return None

    x1, x2 = min(xs), max(xs)
    y1, y2 = min(ys), max(ys)

    return yolo_box(x1, y1, x2, y2, img_w, img_h)


def val_split(filename: str) -> str:
    """
    O BDD oficial não fornece labels públicos do test.
    Divide o VAL anotado deterministicamente em:
      ~50% val
      ~50% test
    """
    digest = hashlib.md5(filename.encode("utf-8")).digest()
    return "val" if digest[0] < 128 else "test"


def process_bdd_split(
    json_path: Path,
    images_dir: Path,
    source_split: str,
) -> None:

    data = load_json(json_path)
    total = len(data)

    print(f"\nProcessando BDD100K {source_split}: {total:,} registros")

    for index, item in enumerate(data, start=1):
        filename = item.get("name")
        if not filename:
            continue

        image_path = images_dir / filename

        if not image_path.exists():
            warnings.append(f"BDD imagem ausente: {filename}")
            continue

        final_split = (
            "train"
            if source_split == "train"
            else val_split(filename)
        )

        try:
            with Image.open(image_path) as image:
                img_w, img_h = image.size
        except Exception as exc:
            warnings.append(
                f"Imagem BDD inválida {filename}: {exc}"
            )
            continue

        lines = []

        for label in item.get("labels", []):
            target = map_bdd_box_class(label)

            if target is not None:
                box = label.get("box2d")

                if box:
                    converted = yolo_box(
                        box.get("x1", 0),
                        box.get("y1", 0),
                        box.get("x2", 0),
                        box.get("y2", 0),
                        img_w,
                        img_h,
                    )

                    if converted is not None:
                        cid = CLASS_TO_ID[target]
                        xc, yc, nw, nh = converted

                        lines.append(
                            f"{cid} "
                            f"{xc:.6f} {yc:.6f} "
                            f"{nw:.6f} {nh:.6f}"
                        )

                        stats_objects[target] += 1
                        stats_objects_by_split[final_split][target] += 1

            crosswalk_box = crosswalk_bbox_from_bdd_label(
                label,
                img_w,
                img_h,
            )

            if crosswalk_box is not None:
                cid = CLASS_TO_ID["crosswalk"]
                xc, yc, nw, nh = crosswalk_box

                lines.append(
                    f"{cid} "
                    f"{xc:.6f} {yc:.6f} "
                    f"{nw:.6f} {nh:.6f}"
                )

                stats_objects["crosswalk"] += 1
                stats_objects_by_split[final_split]["crosswalk"] += 1

        # Não cria negativos artificiais do BDD.
        if not lines:
            continue

        out_img, out_lbl = make_output_names("bdd", filename)

        safe_link_or_copy(
            image_path,
            OUTPUT / "images" / final_split / out_img,
        )
        write_label(
            OUTPUT / "labels" / final_split / out_lbl,
            lines,
        )

        stats_images[final_split] += 1
        stats_source[f"bdd_{final_split}"] += 1

        if index % 5000 == 0 or index == total:
            print(f"  {index:,}/{total:,}")


def process_bdd(bdd_root: Path) -> None:
    train_json = (
        bdd_root
        / "train"
        / "annotations"
        / "bdd100k_labels_images_train.json"
    )
    val_json = (
        bdd_root
        / "val"
        / "annotations"
        / "bdd100k_labels_images_val.json"
    )

    process_bdd_split(
        train_json,
        bdd_root / "train" / "images",
        "train",
    )
    process_bdd_split(
        val_json,
        bdd_root / "val" / "images",
        "val",
    )


# ============================================================
# PEDESTRIAN TRAFFIC LIGHT (YOLO)
# ============================================================

def find_ped_dataset() -> Path:
    candidates = []

    for yaml_path in ROOT.rglob("data.yaml"):
        s = str(yaml_path).lower().replace("\\", "/")

        if "/.venv/" in s:
            continue
        if "/sensewalk_dataset_" in s:
            continue

        if "pedestrian" in yaml_path.parent.name.lower():
            candidates.append(yaml_path.parent)

    if not candidates:
        raise FileNotFoundError(
            "Não encontrei o dataset pedestrian traffic light."
        )

    return sorted(candidates, key=lambda p: len(str(p)))[0]


def load_yolo_names(dataset: Path) -> list[str]:
    data = yaml.safe_load(
        (dataset / "data.yaml").read_text(encoding="utf-8")
    )

    names = data["names"]

    if isinstance(names, dict):
        names = [
            names[key]
            for key in sorted(names, key=lambda x: int(x))
        ]

    return list(names)


def split_dir(dataset: Path, split: str) -> Path | None:
    aliases = {
        "train": ["train"],
        "val": ["valid", "val"],
        "test": ["test"],
    }

    for alias in aliases[split]:
        candidate = dataset / alias
        if candidate.exists():
            return candidate

    return None


def process_pedestrian_lights() -> None:
    dataset = find_ped_dataset()
    names = load_yolo_names(dataset)

    print("\nPedestrian Traffic Light:")
    print(f"  {dataset}")

    mapping = {
        "go": "pedestrian-light-green",
        "green": "pedestrian-light-green",
        "green-pedestrian-light": "pedestrian-light-green",

        "not-go": "pedestrian-light-red",
        "red": "pedestrian-light-red",
        "red-pedestrian-light": "pedestrian-light-red",

        # Ambíguas: ignoradas.
        "pedestrian-traffic-light": None,
        "signal-light": None,
    }

    for final_split in ("train", "val", "test"):
        source = split_dir(dataset, final_split)

        if source is None:
            continue

        images_dir = source / "images"
        labels_dir = source / "labels"

        if not images_dir.exists() or not labels_dir.exists():
            continue

        images = [
            p for p in images_dir.iterdir()
            if p.is_file() and p.suffix.lower() in IMAGE_EXTENSIONS
        ]

        print(f"  {final_split}: {len(images):,} imagens")

        for image_path in images:
            label_path = labels_dir / f"{image_path.stem}.txt"

            if not label_path.exists():
                continue

            raw_lines = [
                line.strip()
                for line in label_path.read_text(
                    encoding="utf-8",
                    errors="ignore"
                ).splitlines()
                if line.strip()
            ]

            converted = []

            for line in raw_lines:
                parts = line.split()

                if len(parts) != 5:
                    continue

                try:
                    source_id = int(float(parts[0]))
                    coords = [float(v) for v in parts[1:5]]
                except ValueError:
                    continue

                if not (0 <= source_id < len(names)):
                    continue

                source_class = normalize_name(names[source_id])
                target = mapping.get(source_class)

                if target is None:
                    continue

                if not all(0 <= v <= 1 for v in coords):
                    continue

                cid = CLASS_TO_ID[target]

                converted.append(
                    f"{cid} "
                    + " ".join(f"{v:.6f}" for v in coords)
                )

                stats_objects[target] += 1
                stats_objects_by_split[final_split][target] += 1

            # Se havia classes apenas ambíguas, não vira negativo.
            if raw_lines and not converted:
                continue

            out_img, out_lbl = make_output_names(
                "pedlight",
                image_path.name,
            )

            safe_link_or_copy(
                image_path,
                OUTPUT / "images" / final_split / out_img,
            )
            write_label(
                OUTPUT / "labels" / final_split / out_lbl,
                converted,
            )

            stats_images[final_split] += 1
            stats_source[f"pedlight_{final_split}"] += 1


# ============================================================
# YAML / VALIDAÇÃO / RELATÓRIO
# ============================================================

def write_yaml() -> None:
    payload = {
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
            payload,
            handle,
            sort_keys=False,
            allow_unicode=True,
        )


def verify() -> list[str]:
    problems = []

    for split in ("train", "val", "test"):
        img_dir = OUTPUT / "images" / split
        lbl_dir = OUTPUT / "labels" / split

        image_stems = {
            p.stem
            for p in img_dir.iterdir()
            if p.is_file() and p.suffix.lower() in IMAGE_EXTENSIONS
        }
        label_stems = {
            p.stem
            for p in lbl_dir.glob("*.txt")
        }

        for missing in image_stems - label_stems:
            problems.append(
                f"{split}: imagem sem label: {missing}"
            )

        for missing in label_stems - image_stems:
            problems.append(
                f"{split}: label sem imagem: {missing}"
            )

        for path in lbl_dir.glob("*.txt"):
            for line_no, line in enumerate(
                path.read_text(encoding="utf-8").splitlines(),
                start=1,
            ):
                if not line.strip():
                    continue

                parts = line.split()

                if len(parts) != 5:
                    problems.append(
                        f"{path}:{line_no}: formato inválido"
                    )
                    continue

                try:
                    cid = int(parts[0])
                    coords = [float(v) for v in parts[1:]]
                except ValueError:
                    problems.append(
                        f"{path}:{line_no}: valor inválido"
                    )
                    continue

                if not 0 <= cid < len(CLASSES):
                    problems.append(
                        f"{path}:{line_no}: classe inválida"
                    )

                if not all(0 <= v <= 1 for v in coords):
                    problems.append(
                        f"{path}:{line_no}: bbox fora de 0..1"
                    )

    return problems


def write_reports(problems: list[str]) -> None:
    csv_path = OUTPUT / "class_distribution.csv"

    with csv_path.open(
        "w",
        newline="",
        encoding="utf-8-sig",
    ) as handle:
        writer = csv.writer(handle)
        writer.writerow(
            ["class_id", "class_name", "train", "val", "test", "total"]
        )

        for cid, name in enumerate(CLASSES):
            train = stats_objects_by_split["train"][name]
            val = stats_objects_by_split["val"][name]
            test = stats_objects_by_split["test"][name]

            writer.writerow(
                [cid, name, train, val, test, train + val + test]
            )

    lines = [
        "=" * 76,
        "SENSEWALK DATASET V2 - RELATÓRIO",
        "=" * 76,
        "",
        "IMAGENS POR SPLIT",
        "-" * 76,
    ]

    for split in ("train", "val", "test"):
        lines.append(
            f"{split:<10} {stats_images[split]:>10,}"
        )

    lines += [
        "",
        "OBJETOS POR CLASSE",
        "-" * 76,
    ]

    for cid, name in enumerate(CLASSES):
        train = stats_objects_by_split["train"][name]
        val = stats_objects_by_split["val"][name]
        test = stats_objects_by_split["test"][name]
        total = train + val + test

        lines.append(
            f"{cid:2d} {name:<29} "
            f"train={train:>9,} "
            f"val={val:>8,} "
            f"test={test:>8,} "
            f"total={total:>10,}"
        )

    lines += [
        "",
        "ORIGENS",
        "-" * 76,
    ]

    for source, count in sorted(stats_source.items()):
        lines.append(
            f"{source:<35} {count:>10,}"
        )

    lines += [
        "",
        "VALIDAÇÃO",
        "-" * 76,
    ]

    if problems:
        lines.append(
            f"{len(problems)} problema(s) estrutural(is)."
        )
        lines.extend(
            f"ERRO: {p}"
            for p in problems[:100]
        )
    else:
        lines.append(
            "Nenhum problema estrutural encontrado."
        )

    lines += [
        "",
        "WARNINGS",
        "-" * 76,
        f"{len(warnings)} warning(s).",
    ]

    lines.extend(
        f"AVISO: {w}"
        for w in warnings[:100]
    )

    report = "\n".join(lines)

    (OUTPUT / "dataset_report.txt").write_text(
        report,
        encoding="utf-8",
    )

    print("\n" + report)


# ============================================================
# MAIN
# ============================================================

def main() -> None:
    print("=" * 76)
    print("SENSEWALK DATASET BUILDER V2")
    print("=" * 76)
    print(f"ROOT:   {ROOT}")
    print(f"OUTPUT: {OUTPUT}")

    bdd_root = choose_best_bdd_root()
    preflight_bdd(bdd_root)

    reset_output()

    print("\n[1/2] BDD100K...")
    process_bdd(bdd_root)

    print("\n[2/2] Pedestrian Traffic Light...")
    process_pedestrian_lights()

    print("\nCriando data.yaml...")
    write_yaml()

    print("Validando...")
    problems = verify()

    print("Gerando relatórios...")
    write_reports(problems)

    print("\n" + "=" * 76)
    print("CONCLUÍDO")
    print("=" * 76)
    print(f"Dataset: {OUTPUT}")
    print(f"Report:  {OUTPUT / 'dataset_report.txt'}")
    print(f"CSV:     {OUTPUT / 'class_distribution.csv'}")
    print("\nAinda NÃO treine.")
    print("Primeiro revise dataset_report.txt.")


if __name__ == "__main__":
    main()
