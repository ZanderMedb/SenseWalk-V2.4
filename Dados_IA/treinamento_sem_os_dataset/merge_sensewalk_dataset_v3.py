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


# =============================================================================
# CONFIGURAÇÃO
# =============================================================================

ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / "sensewalk_dataset_v3"

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}

# Dataset final
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

# Evita que a mesma imagem exata entre em train/val/test.
ENABLE_EXACT_DEDUP = True

stats_images = Counter()
stats_objects = Counter()
stats_objects_by_split = defaultdict(Counter)
stats_source = Counter()
stats_skipped_duplicates = Counter()
warnings: list[str] = []

# hash -> (split, source, filename)
seen_image_hashes: dict[str, tuple[str, str, str]] = {}


# =============================================================================
# UTILIDADES
# =============================================================================

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
        1
        for p in folder.iterdir()
        if p.is_file() and p.suffix.lower() in IMAGE_EXTENSIONS
    )


def image_names(folder: Path) -> set[str]:
    if not folder.exists():
        return set()
    return {
        p.name
        for p in folder.iterdir()
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
    """
    Hardlink economiza muito espaço se origem e destino estiverem
    no mesmo volume. Se não der, faz cópia normal.
    """
    dst.parent.mkdir(parents=True, exist_ok=True)

    if dst.exists():
        dst.unlink()

    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def file_sha1(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def accept_image(
    image_path: Path,
    split: str,
    source_name: str,
) -> bool:
    if not ENABLE_EXACT_DEDUP:
        return True

    h = file_sha1(image_path)
    previous = seen_image_hashes.get(h)

    if previous is not None:
        previous_split, previous_source, previous_name = previous
        stats_skipped_duplicates[source_name] += 1
        warnings.append(
            "Duplicata exata ignorada: "
            f"{image_path.name} ({source_name}/{split}) == "
            f"{previous_name} ({previous_source}/{previous_split})"
        )
        return False

    seen_image_hashes[h] = (split, source_name, image_path.name)
    return True


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


def make_output_names(
    prefix: str,
    original: str,
) -> tuple[str, str]:
    image_name = f"{prefix}_{Path(original).name}"
    label_name = f"{Path(image_name).stem}.txt"
    return image_name, label_name


# =============================================================================
# BDD100K
# =============================================================================

def find_bdd_roots() -> list[Path]:
    roots = []

    for train_json in ROOT.rglob("bdd100k_labels_images_train.json"):
        normalized = str(train_json).lower().replace("\\", "/")

        if "/.venv/" in normalized:
            continue
        if "/sensewalk_dataset_" in normalized:
            continue

        # root/train/annotations/file.json
        candidate = train_json.parent.parent.parent

        train_images = candidate / "train" / "images"
        val_images = candidate / "val" / "images"
        val_json = (
            candidate
            / "val"
            / "annotations"
            / "bdd100k_labels_images_val.json"
        )

        if train_images.exists() and val_images.exists() and val_json.exists():
            roots.append(candidate)

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
            "Não encontrei o BDD100K no formato novo:\n"
            "bdd100k/train/images\n"
            "bdd100k/train/annotations/bdd100k_labels_images_train.json\n"
            "bdd100k/val/images\n"
            "bdd100k/val/annotations/bdd100k_labels_images_val.json"
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

    return ranked[0]


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

    train_names = {
        item.get("name")
        for item in train_data
        if item.get("name")
    }
    val_names = {
        item.get("name")
        for item in val_data
        if item.get("name")
    }

    train_files = image_names(train_images_dir)
    val_files = image_names(val_images_dir)

    train_matches = len(train_names & train_files)
    val_matches = len(val_names & val_files)

    train_pct = (
        train_matches / len(train_names) * 100.0
        if train_names else 0.0
    )
    val_pct = (
        val_matches / len(val_names) * 100.0
        if val_names else 0.0
    )

    print("\n" + "=" * 78)
    print("PRÉ-VALIDAÇÃO BDD100K")
    print("=" * 78)
    print(
        f"TRAIN: JSON={len(train_names):,} | "
        f"imagens={len(train_files):,} | "
        f"matches={train_matches:,} ({train_pct:.2f}%)"
    )
    print(
        f"VAL:   JSON={len(val_names):,} | "
        f"imagens={len(val_files):,} | "
        f"matches={val_matches:,} ({val_pct:.2f}%)"
    )

    if train_pct < 95.0 or val_pct < 95.0:
        raise RuntimeError(
            "BDD100K incompleto/incompatível. Merge cancelado."
        )

    print("BDD100K aprovado.")


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
        attrs = label.get("attributes") or {}
        color = normalize_name(attrs.get("trafficLightColor", ""))

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
    category = normalize_name(label.get("category", ""))

    if category != "lane":
        return None

    attrs = label.get("attributes") or {}
    lane_type = normalize_name(attrs.get("laneType", ""))

    if lane_type != "crosswalk":
        return None

    xs = []
    ys = []

    for poly in label.get("poly2d") or []:
        for vertex in poly.get("vertices", []):
            if not isinstance(vertex, (list, tuple)) or len(vertex) < 2:
                continue

            try:
                xs.append(float(vertex[0]))
                ys.append(float(vertex[1]))
            except (TypeError, ValueError):
                pass

    if not xs or not ys:
        return None

    return yolo_box(
        min(xs),
        min(ys),
        max(xs),
        max(ys),
        img_w,
        img_h,
    )


def bdd_val_split(filename: str) -> str:
    """
    Como não temos labels públicos do test BDD,
    dividimos o val anotado deterministicamente entre val e test.
    """
    digest = hashlib.md5(filename.encode("utf-8")).digest()
    return "val" if digest[0] < 128 else "test"


def process_bdd_split(
    bdd_root: Path,
    source_split: str,
) -> None:
    json_path = (
        bdd_root
        / source_split
        / "annotations"
        / f"bdd100k_labels_images_{source_split}.json"
    )
    images_dir = bdd_root / source_split / "images"

    data = load_json(json_path)
    total = len(data)

    print(
        f"\nBDD100K {source_split}: "
        f"{total:,} registros"
    )

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
            else bdd_val_split(filename)
        )

        try:
            with Image.open(image_path) as image:
                img_w, img_h = image.size
        except Exception as exc:
            warnings.append(f"BDD imagem inválida {filename}: {exc}")
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

            crosswalk = crosswalk_bbox_from_bdd_label(
                label,
                img_w,
                img_h,
            )

            if crosswalk is not None:
                cid = CLASS_TO_ID["crosswalk"]
                xc, yc, nw, nh = crosswalk

                lines.append(
                    f"{cid} "
                    f"{xc:.6f} {yc:.6f} "
                    f"{nw:.6f} {nh:.6f}"
                )

                stats_objects["crosswalk"] += 1
                stats_objects_by_split[final_split]["crosswalk"] += 1

        # Não criamos negativos artificiais.
        if not lines:
            continue

        if not accept_image(
            image_path,
            final_split,
            "bdd",
        ):
            continue

        out_img, out_lbl = make_output_names(
            "bdd",
            image_path.name,
        )

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


# =============================================================================
# DESCOBERTA DOS DATASETS YOLO / ROBOFLOW
# =============================================================================

def load_data_yaml(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def all_source_yamls() -> list[Path]:
    paths = []

    for path in ROOT.rglob("data.yaml"):
        normalized = str(path).lower().replace("\\", "/")

        if "/.venv/" in normalized:
            continue
        if "/sensewalk_dataset_" in normalized:
            continue

        paths.append(path)

    return paths


def find_dataset_by_project(project_name: str) -> Path:
    matches = []

    for yaml_path in all_source_yamls():
        try:
            data = load_data_yaml(yaml_path)
        except Exception:
            continue

        project = str(
            (data.get("roboflow") or {}).get("project", "")
        ).strip()

        if project.lower() == project_name.lower():
            matches.append(yaml_path.parent)

    if not matches:
        raise FileNotFoundError(
            f"Dataset Roboflow não encontrado: {project_name}"
        )

    matches.sort(key=lambda p: len(str(p)))
    return matches[0]


def load_yolo_names(dataset: Path) -> list[str]:
    data = load_data_yaml(dataset / "data.yaml")
    names = data.get("names", [])

    if isinstance(names, dict):
        names = [
            names[key]
            for key in sorted(names, key=lambda x: int(x))
        ]

    return [str(name) for name in names]


def source_split_dir(
    dataset: Path,
    split: str,
) -> Path | None:
    aliases = {
        "test": ["test"],
        "val": ["valid", "val"],
        "train": ["train"],
    }

    for alias in aliases[split]:
        candidate = dataset / alias
        if candidate.exists():
            return candidate

    return None


# =============================================================================
# IMPORTADOR YOLO GENÉRICO
# =============================================================================

def process_yolo_split(
    dataset: Path,
    source_name: str,
    prefix: str,
    mapping: dict[str, str | None],
    split: str,
) -> None:
    split_root = source_split_dir(dataset, split)

    if split_root is None:
        print(f"  {source_name}/{split}: split ausente")
        return

    images_dir = split_root / "images"
    labels_dir = split_root / "labels"

    if not images_dir.exists() or not labels_dir.exists():
        print(f"  {source_name}/{split}: images/labels ausentes")
        return

    names = load_yolo_names(dataset)

    images = sorted(
        p
        for p in images_dir.iterdir()
        if p.is_file() and p.suffix.lower() in IMAGE_EXTENSIONS
    )

    print(
        f"  {source_name}/{split}: "
        f"{len(images):,} imagens"
    )

    included = 0

    for image_path in images:
        label_path = labels_dir / f"{image_path.stem}.txt"

        if not label_path.exists():
            continue

        raw_lines = [
            line.strip()
            for line in label_path.read_text(
                encoding="utf-8",
                errors="ignore",
            ).splitlines()
            if line.strip()
        ]

        converted = []

        for line in raw_lines:
            parts = line.split()

            if len(parts) != 5:
                # Não queremos segmentações/polígonos aqui.
                continue

            try:
                source_id = int(float(parts[0]))
                coords = [float(v) for v in parts[1:5]]
            except ValueError:
                continue

            if not 0 <= source_id < len(names):
                continue

            source_class = normalize_name(names[source_id])
            target_class = mapping.get(source_class)

            if target_class is None:
                continue

            if not all(0.0 <= v <= 1.0 for v in coords):
                continue

            cid = CLASS_TO_ID[target_class]

            converted.append(
                f"{cid} "
                + " ".join(f"{v:.6f}" for v in coords)
            )

        # Segurança:
        # só incluímos imagens suplementares quando há ao menos
        # uma box da classe que realmente queremos.
        if not converted:
            continue

        if not accept_image(
            image_path,
            split,
            source_name,
        ):
            continue

        out_img, out_lbl = make_output_names(
            prefix,
            image_path.name,
        )

        safe_link_or_copy(
            image_path,
            OUTPUT / "images" / split / out_img,
        )
        write_label(
            OUTPUT / "labels" / split / out_lbl,
            converted,
        )

        # Estatísticas somente depois de aceitar a imagem,
        # para não contar duplicatas ignoradas.
        for line in converted:
            cid = int(line.split()[0])
            target_name = CLASSES[cid]
            stats_objects[target_name] += 1
            stats_objects_by_split[split][target_name] += 1

        stats_images[split] += 1
        stats_source[f"{source_name}_{split}"] += 1
        included += 1

    print(
        f"    incluídas={included:,} "
        f"(somente imagens com classes úteis)"
    )


# =============================================================================
# FONTES SUPLEMENTARES
# =============================================================================

SOURCE_DEFINITIONS = [
    {
        "project": "pedestrian-traffic-light-pbbl6",
        "name": "pedlight_old",
        "prefix": "pedold",
        "mapping": {
            "go": "pedestrian-light-green",
            "green": "pedestrian-light-green",
            "green-pedestrian-light": "pedestrian-light-green",
            "not-go": "pedestrian-light-red",
            "red": "pedestrian-light-red",
            "red-pedestrian-light": "pedestrian-light-red",
            "pedestrian-traffic-light": None,
            "signal-light": None,
        },
    },
    {
        "project": "pedestrian-traffic-lights-final",
        "name": "pedlight_final",
        "prefix": "pedfinal",
        "mapping": {
            "green": "pedestrian-light-green",
            "red": "pedestrian-light-red",
        },
    },
    {
        "project": "pedestrian-signal-lights-d2upo",
        "name": "ped_signal",
        "prefix": "pedsignal",
        "mapping": {
            "green-light": "pedestrian-light-green",
            "red-light": "pedestrian-light-red",
        },
    },
    {
        "project": "traffic-light-v9orl-mmkee",
        "name": "traffic_rgy",
        "prefix": "tlrgy",
        "mapping": {
            "green": "traffic-light-green",
            "red": "traffic-light-red",
            "yellow": "traffic-light-yellow",
        },
    },
    {
        "project": "motorcycle-wubuz",
        "name": "motorcycle_extra",
        "prefix": "motoextra",
        "mapping": {
            "motorcycle": "motorcycle",

            # Não são nossa classe-alvo.
            "compliant": None,
            "non-compliant": None,
            "flag": None,
        },
    },
]


def resolve_sources():
    resolved = []

    print("\n" + "=" * 78)
    print("FONTES SUPLEMENTARES")
    print("=" * 78)

    for definition in SOURCE_DEFINITIONS:
        dataset = find_dataset_by_project(definition["project"])
        names = load_yolo_names(dataset)

        print(f"\n{definition['name']}")
        print(f"  pasta: {dataset}")
        print(f"  classes: {names}")

        resolved.append((definition, dataset))

    return resolved


# =============================================================================
# DATA.YAML + VALIDAÇÃO + RELATÓRIOS
# =============================================================================

def write_final_yaml() -> None:
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

    with (OUTPUT / "data.yaml").open(
        "w",
        encoding="utf-8",
    ) as handle:
        yaml.safe_dump(
            payload,
            handle,
            sort_keys=False,
            allow_unicode=True,
        )


def verify_dataset() -> list[str]:
    problems = []

    for split in ("train", "val", "test"):
        images_dir = OUTPUT / "images" / split
        labels_dir = OUTPUT / "labels" / split

        image_stems = {
            p.stem
            for p in images_dir.iterdir()
            if p.is_file() and p.suffix.lower() in IMAGE_EXTENSIONS
        }

        label_stems = {
            p.stem
            for p in labels_dir.glob("*.txt")
        }

        if image_stems - label_stems:
            problems.append(
                f"{split}: {len(image_stems - label_stems)} "
                "imagem(ns) sem label"
            )

        if label_stems - image_stems:
            problems.append(
                f"{split}: {len(label_stems - image_stems)} "
                "label(s) sem imagem"
            )

        for label_path in labels_dir.glob("*.txt"):
            for line_no, line in enumerate(
                label_path.read_text(
                    encoding="utf-8",
                    errors="ignore",
                ).splitlines(),
                start=1,
            ):
                if not line.strip():
                    continue

                parts = line.split()

                if len(parts) != 5:
                    problems.append(
                        f"{label_path}:{line_no}: formato inválido"
                    )
                    continue

                try:
                    cid = int(parts[0])
                    coords = [float(v) for v in parts[1:]]
                except ValueError:
                    problems.append(
                        f"{label_path}:{line_no}: valor inválido"
                    )
                    continue

                if not 0 <= cid < len(CLASSES):
                    problems.append(
                        f"{label_path}:{line_no}: classe inválida"
                    )

                if not all(0 <= v <= 1 for v in coords):
                    problems.append(
                        f"{label_path}:{line_no}: bbox fora de 0..1"
                    )

                if coords[2] <= 0 or coords[3] <= 0:
                    problems.append(
                        f"{label_path}:{line_no}: bbox sem área"
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
            [
                "class_id",
                "class_name",
                "train",
                "val",
                "test",
                "total",
            ]
        )

        for cid, name in enumerate(CLASSES):
            train = stats_objects_by_split["train"][name]
            val = stats_objects_by_split["val"][name]
            test = stats_objects_by_split["test"][name]

            writer.writerow(
                [
                    cid,
                    name,
                    train,
                    val,
                    test,
                    train + val + test,
                ]
            )

    lines = [
        "=" * 82,
        "SENSEWALK DATASET V3 - RELATÓRIO",
        "=" * 82,
        "",
        "IMAGENS POR SPLIT",
        "-" * 82,
    ]

    for split in ("train", "val", "test"):
        lines.append(
            f"{split:<10} {stats_images[split]:>10,}"
        )

    lines += [
        "",
        "OBJETOS POR CLASSE",
        "-" * 82,
    ]

    for cid, name in enumerate(CLASSES):
        train = stats_objects_by_split["train"][name]
        val = stats_objects_by_split["val"][name]
        test = stats_objects_by_split["test"][name]
        total = train + val + test

        lines.append(
            f"{cid:2d} {name:<29} "
            f"train={train:>10,} "
            f"val={val:>8,} "
            f"test={test:>8,} "
            f"total={total:>11,}"
        )

    lines += [
        "",
        "IMAGENS POR ORIGEM",
        "-" * 82,
    ]

    for source, count in sorted(stats_source.items()):
        lines.append(
            f"{source:<40} {count:>10,}"
        )

    lines += [
        "",
        "DUPLICATAS EXATAS IGNORADAS",
        "-" * 82,
    ]

    if stats_skipped_duplicates:
        for source, count in sorted(
            stats_skipped_duplicates.items()
        ):
            lines.append(
                f"{source:<40} {count:>10,}"
            )
    else:
        lines.append("Nenhuma.")

    lines += [
        "",
        "VALIDAÇÃO",
        "-" * 82,
    ]

    if problems:
        lines.append(
            f"{len(problems)} problema(s) estrutural(is)."
        )
        lines.extend(
            f"ERRO: {problem}"
            for problem in problems[:100]
        )
    else:
        lines.append("Nenhum problema estrutural encontrado.")

    lines += [
        "",
        "WARNINGS",
        "-" * 82,
        f"{len(warnings)} warning(s).",
    ]

    lines.extend(
        f"AVISO: {warning}"
        for warning in warnings[:100]
    )

    report = "\n".join(lines)

    (OUTPUT / "dataset_report.txt").write_text(
        report,
        encoding="utf-8",
    )

    print("\n" + report)


# =============================================================================
# MAIN
# =============================================================================

def main() -> None:
    print("=" * 82)
    print("SENSEWALK DATASET BUILDER V3")
    print("=" * 82)
    print(f"ROOT:   {ROOT}")
    print(f"OUTPUT: {OUTPUT}")

    bdd_root = choose_best_bdd_root()
    print(f"\nBDD100K selecionado:\n  {bdd_root}")
    preflight_bdd(bdd_root)

    sources = resolve_sources()

    reset_output()

    # -------------------------------------------------------------------------
    # ORDEM IMPORTANTE PARA EVITAR LEAKAGE DE DUPLICATAS:
    # 1) avaliação primeiro
    # 2) treinamento depois
    # -------------------------------------------------------------------------

    print("\n" + "=" * 82)
    print("[FASE 1] SPLITS DE AVALIAÇÃO")
    print("=" * 82)

    # BDD val gera val + test.
    process_bdd_split(
        bdd_root,
        "val",
    )

    # Test primeiro, depois val, em cada suplementar.
    for split in ("test", "val"):
        for definition, dataset in sources:
            process_yolo_split(
                dataset=dataset,
                source_name=definition["name"],
                prefix=definition["prefix"],
                mapping=definition["mapping"],
                split=split,
            )

    print("\n" + "=" * 82)
    print("[FASE 2] TRAIN")
    print("=" * 82)

    process_bdd_split(
        bdd_root,
        "train",
    )

    for definition, dataset in sources:
        process_yolo_split(
            dataset=dataset,
            source_name=definition["name"],
            prefix=definition["prefix"],
            mapping=definition["mapping"],
            split="train",
        )

    print("\nCriando data.yaml...")
    write_final_yaml()

    print("Validando dataset...")
    problems = verify_dataset()

    print("Gerando relatórios...")
    write_reports(problems)

    print("\n" + "=" * 82)
    print("CONCLUÍDO")
    print("=" * 82)
    print(f"Dataset: {OUTPUT}")
    print(f"YAML:    {OUTPUT / 'data.yaml'}")
    print(f"Report:  {OUTPUT / 'dataset_report.txt'}")
    print(f"CSV:     {OUTPUT / 'class_distribution.csv'}")
    print("\nAinda NÃO comece o treinamento.")
    print("Primeiro rode a auditoria visual das bounding boxes.")


if __name__ == "__main__":
    main()
