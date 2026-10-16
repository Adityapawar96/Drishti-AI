"""Prepare COCO 2017 + RDD2020/RDD2022 for the app's 81-class detector.

RDD2022 source annotations are Pascal VOC XML. This script keeps only the D40
pothole boxes and maps them to class 80; COCO retains its original 0..79 IDs.
COCO images are hard-linked into the output tree where possible to avoid a
second copy of the large image dataset.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

import yaml


REPO_ROOT = Path(__file__).resolve().parents[1]

from ultralytics.data.converter import coco91_to_coco80_class


COCO_LABELS = [
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
    "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
    "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
    "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
    "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
    "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
    "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
    "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote",
    "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book",
    "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush",
]
CLASS_NAMES = COCO_LABELS + ["pothole"]
COCO_CATEGORY_TO_INDEX = {
    category_id: class_id
    for category_id, class_id in enumerate(coco91_to_coco80_class(), start=1)
    if class_id is not None
}


def place_image(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        return
    try:
        os.link(source, destination)
    except OSError:
        shutil.copy2(source, destination)


def convert_coco_split(coco_root: Path, output_root: Path, split: str) -> int:
    image_dir = coco_root / "images" / split
    annotation_file = coco_root / "annotations" / f"instances_{split}.json"
    if not image_dir.is_dir() or not annotation_file.is_file():
        raise FileNotFoundError(
            f"Expected COCO layout with {image_dir} and {annotation_file}"
        )

    data = json.loads(annotation_file.read_text(encoding="utf-8"))
    image_by_id = {int(item["id"]): item for item in data["images"]}
    annotations: dict[int, list[dict]] = {}
    for item in data["annotations"]:
        if item.get("iscrowd", 0):
            continue
        annotations.setdefault(int(item["image_id"]), []).append(item)

    labels_root = output_root / "labels" / "coco" / split
    output_images = output_root / "images" / "coco" / split
    labels_root.mkdir(parents=True, exist_ok=True)
    count = 0
    for image_id, record in image_by_id.items():
        relative_name = Path(record["file_name"])
        source_image = image_dir / relative_name
        if not source_image.is_file():
            raise FileNotFoundError(f"COCO image listed in annotation file is missing: {source_image}")
        target_image = output_images / relative_name
        place_image(source_image, target_image)

        width = float(record["width"])
        height = float(record["height"])
        rows: list[str] = []
        for item in annotations.get(image_id, []):
            category_id = int(item["category_id"])
            if category_id not in COCO_CATEGORY_TO_INDEX:
                continue
            class_id = COCO_CATEGORY_TO_INDEX[category_id]
            x, y, box_width, box_height = (float(value) for value in item["bbox"])
            if width <= 0 or height <= 0 or box_width <= 0 or box_height <= 0:
                continue
            x1 = min(max(x, 0.0), width)
            y1 = min(max(y, 0.0), height)
            x2 = min(max(x + box_width, 0.0), width)
            y2 = min(max(y + box_height, 0.0), height)
            if x2 <= x1 or y2 <= y1:
                continue
            rows.append("%d %.6f %.6f %.6f %.6f" % (
                class_id, ((x1 + x2) / 2) / width, ((y1 + y2) / 2) / height,
                (x2 - x1) / width, (y2 - y1) / height,
            ))
        label_file = labels_root / relative_name.with_suffix(".txt")
        label_file.parent.mkdir(parents=True, exist_ok=True)
        label_file.write_text("\n".join(rows) + ("\n" if rows else ""), encoding="utf-8")
        count += 1
    return count


def find_rdd_image(annotation: Path, root: Path, filename: str | None) -> Path | None:
    candidates: list[Path] = []
    if filename:
        image_name = Path(filename).name
        candidates.append(annotation.parent / image_name)
        # RDD2020 and RDD2022 keep VOC XML files under annotations/xmls
        # and the matching images in a neighboring images directory.
        for ancestor in annotation.parents:
            for directory in ("images", "JPEGImages"):
                candidates.append(ancestor / directory / image_name)
    candidates.extend(annotation.with_suffix(suffix) for suffix in (".jpg", ".jpeg", ".png", ".JPG"))
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return None


def pothole_rows(annotation: Path) -> tuple[int, int, list[str]]:
    root = ET.parse(annotation).getroot()
    width = int(root.findtext("size/width", "0"))
    height = int(root.findtext("size/height", "0"))
    if width <= 0 or height <= 0:
        raise ValueError(f"Missing valid image dimensions in {annotation}")

    rows: list[str] = []
    for item in root.findall("object"):
        name = (item.findtext("name") or "").strip().lower()
        if name not in {"d40", "pothole", "potholes"}:
            continue
        box = item.find("bndbox")
        if box is None:
            continue
        try:
            x1 = float(box.findtext("xmin", "0"))
            y1 = float(box.findtext("ymin", "0"))
            x2 = float(box.findtext("xmax", "0"))
            y2 = float(box.findtext("ymax", "0"))
        except ValueError:
            continue
        x1, x2 = min(max(x1, 0.0), width), min(max(x2, 0.0), width)
        y1, y2 = min(max(y1, 0.0), height), min(max(y2, 0.0), height)
        if x2 <= x1 or y2 <= y1:
            continue
        rows.append("80 %.6f %.6f %.6f %.6f" % (
            ((x1 + x2) / 2) / width, ((y1 + y2) / 2) / height,
            (x2 - x1) / width, (y2 - y1) / height,
        ))
    return width, height, rows


def rdd_split(relative_path: Path, validation_fraction: float) -> str:
    key = relative_path.as_posix().encode("utf-8")
    value = int(hashlib.sha256(key).hexdigest()[:8], 16) / 0xFFFFFFFF
    return "val" if value < validation_fraction else "train"


def convert_rdd(rdd_root: Path, output_root: Path, validation_fraction: float) -> tuple[int, int]:
    xml_files = sorted(rdd_root.rglob("*.xml"))
    if not xml_files:
        raise FileNotFoundError(f"No Pascal VOC XML annotations found under {rdd_root}")

    counts = {"train": 0, "val": 0}
    for annotation in xml_files:
        relative = annotation.relative_to(rdd_root)
        image_name = ET.parse(annotation).getroot().findtext("filename")
        source_image = find_rdd_image(annotation, rdd_root, image_name)
        if source_image is None:
            raise FileNotFoundError(f"Could not find the image matching {annotation}")
        _, _, rows = pothole_rows(annotation)
        split = rdd_split(relative, validation_fraction)
        safe_stem = "__".join(relative.with_suffix("").parts)
        target_image = output_root / "images" / "rdd" / split / f"{safe_stem}{source_image.suffix.lower()}"
        target_label = output_root / "labels" / "rdd" / split / f"{safe_stem}.txt"
        target_image.parent.mkdir(parents=True, exist_ok=True)
        target_label.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_image, target_image)
        target_label.write_text("\n".join(rows) + ("\n" if rows else ""), encoding="utf-8")
        counts[split] += 1
    return counts["train"], counts["val"]


def write_data_yaml(output_root: Path) -> None:
    output_root.mkdir(parents=True, exist_ok=True)
    data = {
        "path": output_root.as_posix(),
        "train": [
            "images/coco/train2017",
            "images/rdd/train",
        ],
        "val": [
            "images/coco/val2017",
            "images/rdd/val",
        ],
        "names": {index: name for index, name in enumerate(CLASS_NAMES)},
    }
    (output_root / "data.yaml").write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")

    for filename, names, validation_path in (
        ("coco_val_80.yaml", COCO_LABELS, "images/coco/val2017"),
        ("coco_val_81.yaml", CLASS_NAMES, "images/coco/val2017"),
        ("rdd_val_81.yaml", CLASS_NAMES, "images/rdd/val"),
    ):
        validation = {
            "path": output_root.as_posix(),
            "train": validation_path,
            "val": validation_path,
            "names": {index: name for index, name in enumerate(names)},
        }
        (output_root / filename).write_text(yaml.safe_dump(validation, sort_keys=False), encoding="utf-8")

    rng = random.Random(2026)
    coco_train = sorted((output_root / "images/coco/train2017").glob("*"))
    rdd_train = sorted((output_root / "images/rdd/train").glob("*"))
    rdd_positive = [image for image in rdd_train
                    if (output_root / "labels/rdd/train" / f"{image.stem}.txt").read_text(encoding="utf-8").strip()]
    rdd_positive_set = set(rdd_positive)
    rdd_negative = [image for image in rdd_train if image not in rdd_positive_set]
    if not rdd_positive:
        raise ValueError("No D40 pothole boxes found in the RDD training split")
    if not coco_train:
        raise ValueError("No COCO training images were prepared")
    rng.shuffle(coco_train)
    rng.shuffle(rdd_positive)
    rng.shuffle(rdd_negative)
    calibration_images = coco_train[:250] + rdd_positive[:250] + rdd_negative[:100]
    if not calibration_images:
        raise ValueError("No calibration images were prepared")
    calibration_list = output_root / "calibration.txt"
    calibration_list.write_text("\n".join(path.resolve().as_posix() for path in calibration_images) + "\n",
                                encoding="utf-8")
    calibration = {
        "path": output_root.as_posix(),
        "train": calibration_list.name,
        "names": {index: name for index, name in enumerate(CLASS_NAMES)},
    }
    (output_root / "calibration.yaml").write_text(yaml.safe_dump(calibration, sort_keys=False), encoding="utf-8")

    (output_root / "labels.txt").write_text("\n".join(CLASS_NAMES) + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rdd-root", required=True, type=Path,
                        help="Extracted RDD directory with Pascal VOC XMLs and matching road images")
    parser.add_argument("--coco-root", required=True, type=Path,
                        help="COCO 2017 root with images/{train2017,val2017} and annotations JSON files")
    parser.add_argument("--output", type=Path, default=REPO_ROOT / "tools/datasets/pothole81")
    parser.add_argument("--validation-fraction", type=float, default=0.15)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if not 0.05 <= args.validation_fraction <= 0.4:
        raise ValueError("--validation-fraction must be between 0.05 and 0.4")
    rdd_root = args.rdd_root.expanduser().resolve()
    coco_root = args.coco_root.expanduser().resolve()
    output_root = args.output.expanduser().resolve()
    if output_root == coco_root or coco_root in output_root.parents:
        raise ValueError("Output must not be inside the source COCO dataset")

    output_root.mkdir(parents=True, exist_ok=True)
    train_count = convert_coco_split(coco_root, output_root, "train2017")
    val_count = convert_coco_split(coco_root, output_root, "val2017")
    rdd_train, rdd_val = convert_rdd(rdd_root, output_root, args.validation_fraction)
    if not rdd_train or not rdd_val:
        raise ValueError("RDD conversion produced an empty train or validation split")
    write_data_yaml(output_root)

    print(f"Prepared {train_count:,} COCO train and {val_count:,} COCO val images")
    print(f"Prepared {rdd_train:,} RDD train and {rdd_val:,} RDD val images")
    print(f"81-class dataset config: {output_root / 'data.yaml'}")
    print(f"Generated Android labels: {output_root / 'labels.txt'}")


if __name__ == "__main__":
    main()
