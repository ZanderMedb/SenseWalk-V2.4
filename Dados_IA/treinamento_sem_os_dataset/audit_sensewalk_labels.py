from __future__ import annotations

import argparse
import csv
import random
from collections import defaultdict
from pathlib import Path

import yaml
from PIL import Image, ImageDraw, ImageFont


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}

DEFAULT_SAMPLES = {
    "car": 30,
    "motorcycle": 60,
    "bicycle": 40,
    "truck": 30,
    "bus": 30,
    "traffic-light-green": 60,
    "traffic-light-red": 60,
    "traffic-light-yellow": 80,
    "crosswalk": 100,
    "pedestrian-light-green": 100,
    "pedestrian-light-red": 100,
}


def load_yaml(dataset_root: Path):
    data_yaml = dataset_root / "data.yaml"
    if not data_yaml.exists():
        raise FileNotFoundError(f"data.yaml não encontrado em: {dataset_root}")

    data = yaml.safe_load(data_yaml.read_text(encoding="utf-8"))
    names = data.get("names", {})

    if isinstance(names, list):
        id_to_name = {i: str(name) for i, name in enumerate(names)}
    else:
        id_to_name = {int(k): str(v) for k, v in names.items()}

    return data, id_to_name


def find_image(images_dir: Path, stem: str) -> Path | None:
    for ext in IMAGE_EXTENSIONS:
        p = images_dir / f"{stem}{ext}"
        if p.exists():
            return p
    return None


def parse_label_file(path: Path):
    boxes = []

    for line_no, line in enumerate(
        path.read_text(encoding="utf-8", errors="ignore").splitlines(),
        start=1,
    ):
        line = line.strip()
        if not line:
            continue

        parts = line.split()
        if len(parts) != 5:
            continue

        try:
            class_id = int(float(parts[0]))
            xc, yc, w, h = map(float, parts[1:5])
        except ValueError:
            continue

        if not all(0.0 <= value <= 1.0 for value in (xc, yc, w, h)):
            continue
        if w <= 0 or h <= 0:
            continue

        boxes.append((class_id, xc, yc, w, h, line_no))

    return boxes


def label_to_pixels(box, width: int, height: int):
    class_id, xc, yc, bw, bh, _ = box

    x1 = (xc - bw / 2.0) * width
    y1 = (yc - bh / 2.0) * height
    x2 = (xc + bw / 2.0) * width
    y2 = (yc + bh / 2.0) * height

    return class_id, x1, y1, x2, y2


def get_font():
    try:
        return ImageFont.truetype("arial.ttf", 22)
    except Exception:
        return ImageFont.load_default()


def draw_boxes(
    image_path: Path,
    boxes,
    id_to_name,
    target_class_id: int,
    output_path: Path,
):
    with Image.open(image_path) as src:
        image = src.convert("RGB")

    draw = ImageDraw.Draw(image)
    font = get_font()

    width, height = image.size

    for box in boxes:
        class_id, x1, y1, x2, y2 = label_to_pixels(box, width, height)
        class_name = id_to_name.get(class_id, f"class_{class_id}")

        # Não especificamos uma paleta complexa: alvo mais espesso,
        # demais classes mais finas.
        line_width = 6 if class_id == target_class_id else 2

        draw.rectangle(
            [x1, y1, x2, y2],
            outline="white",
            width=line_width,
        )

        label = f"{class_id}: {class_name}"

        try:
            bbox = draw.textbbox((x1, y1), label, font=font)
            tw = bbox[2] - bbox[0]
            th = bbox[3] - bbox[1]
        except Exception:
            tw, th = draw.textlength(label, font=font), 16

        top = max(0, y1 - th - 8)

        draw.rectangle(
            [x1, top, x1 + tw + 8, top + th + 8],
            fill="black",
        )
        draw.text(
            (x1 + 4, top + 4),
            label,
            fill="white",
            font=font,
        )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    image.save(output_path, quality=92)


def build_contact_sheet(image_paths, output_path: Path, title: str):
    if not image_paths:
        return

    thumbs = []
    thumb_w, thumb_h = 320, 180

    for path in image_paths[:25]:
        with Image.open(path) as img:
            img = img.convert("RGB")
            img.thumbnail((thumb_w, thumb_h))

            canvas = Image.new("RGB", (thumb_w, thumb_h), "black")
            x = (thumb_w - img.width) // 2
            y = (thumb_h - img.height) // 2
            canvas.paste(img, (x, y))
            thumbs.append(canvas)

    cols = 5
    rows = (len(thumbs) + cols - 1) // cols
    title_h = 40

    sheet = Image.new(
        "RGB",
        (cols * thumb_w, rows * thumb_h + title_h),
        "black",
    )

    draw = ImageDraw.Draw(sheet)
    draw.text((10, 10), title, fill="white", font=get_font())

    for i, thumb in enumerate(thumbs):
        x = (i % cols) * thumb_w
        y = title_h + (i // cols) * thumb_h
        sheet.paste(thumb, (x, y))

    output_path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(output_path, quality=92)


def main():
    parser = argparse.ArgumentParser(
        description="Sorteia imagens por classe e desenha labels YOLO para auditoria visual."
    )
    parser.add_argument(
        "--dataset",
        default="sensewalk_dataset_v2",
        help="Pasta do dataset YOLO final.",
    )
    parser.add_argument(
        "--split",
        default="train",
        choices=["train", "val", "test"],
        help="Split a auditar.",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="Seed para sorteio reproduzível.",
    )
    parser.add_argument(
        "--output",
        default="audit_samples",
        help="Pasta de saída.",
    )
    parser.add_argument(
        "--default",
        type=int,
        default=50,
        help="Quantidade por classe se a classe não estiver no mapa DEFAULT_SAMPLES.",
    )
    args = parser.parse_args()

    random.seed(args.seed)

    dataset_root = Path(args.dataset).resolve()
    output_root = Path(args.output).resolve()

    _, id_to_name = load_yaml(dataset_root)
    name_to_id = {name: cid for cid, name in id_to_name.items()}

    images_dir = dataset_root / "images" / args.split
    labels_dir = dataset_root / "labels" / args.split

    if not images_dir.exists() or not labels_dir.exists():
        raise FileNotFoundError(
            f"Não encontrei images/{args.split} e labels/{args.split} em {dataset_root}"
        )

    candidates_by_class = defaultdict(list)
    label_cache = {}

    label_files = sorted(labels_dir.glob("*.txt"))

    print(f"Dataset: {dataset_root}")
    print(f"Split:   {args.split}")
    print(f"Labels:  {len(label_files):,}")

    for idx, label_path in enumerate(label_files, start=1):
        boxes = parse_label_file(label_path)
        label_cache[label_path.stem] = boxes

        present_ids = {box[0] for box in boxes}

        for cid in present_ids:
            if cid in id_to_name:
                candidates_by_class[cid].append(label_path.stem)

        if idx % 10000 == 0:
            print(f"Indexados: {idx:,}/{len(label_files):,}")

    if output_root.exists():
        # Mantém o comportamento seguro: limpa somente a pasta de auditoria.
        import shutil
        shutil.rmtree(output_root)

    output_root.mkdir(parents=True, exist_ok=True)

    report_rows = []

    print("\nSORTEIO POR CLASSE")
    print("=" * 72)

    for cid in sorted(id_to_name):
        class_name = id_to_name[cid]
        candidates = sorted(set(candidates_by_class.get(cid, [])))

        requested = DEFAULT_SAMPLES.get(class_name, args.default)
        actual = min(requested, len(candidates))

        if actual == 0:
            print(f"{cid:2d} {class_name:<28} sem candidatos")
            report_rows.append(
                [cid, class_name, requested, 0, len(candidates)]
            )
            continue

        chosen = random.sample(candidates, actual)

        class_dir = output_root / f"{cid:02d}_{class_name}"
        annotated_paths = []

        for order, stem in enumerate(chosen, start=1):
            image_path = find_image(images_dir, stem)

            if image_path is None:
                continue

            boxes = label_cache.get(stem, [])
            output_path = class_dir / f"{order:03d}_{image_path.name}"

            draw_boxes(
                image_path=image_path,
                boxes=boxes,
                id_to_name=id_to_name,
                target_class_id=cid,
                output_path=output_path,
            )

            annotated_paths.append(output_path)

        build_contact_sheet(
            annotated_paths,
            class_dir / "_contact_sheet.jpg",
            f"{cid}: {class_name} | split={args.split} | seed={args.seed}",
        )

        print(
            f"{cid:2d} {class_name:<28} "
            f"sorteadas={len(annotated_paths):>3} "
            f"candidatos={len(candidates):>6,}"
        )

        report_rows.append(
            [
                cid,
                class_name,
                requested,
                len(annotated_paths),
                len(candidates),
            ]
        )

    report_path = output_root / "audit_report.csv"

    with report_path.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "class_id",
                "class_name",
                "requested",
                "generated",
                "candidate_images",
            ]
        )
        writer.writerows(report_rows)

    print("\n" + "=" * 72)
    print("AUDITORIA GERADA")
    print("=" * 72)
    print(f"Pasta:   {output_root}")
    print(f"Relatório: {report_path}")
    print("\nAbra os _contact_sheet.jpg de cada classe primeiro.")
    print("Depois inspecione imagens individuais quando encontrar algo suspeito.")


if __name__ == "__main__":
    main()
