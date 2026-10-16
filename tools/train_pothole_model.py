"""Fine-tune the COCO-pretrained YOLO26n checkpoint on the 81-class dataset."""

from __future__ import annotations

import argparse
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[1]

from ultralytics import YOLO


DEFAULT_DATA = REPO_ROOT / "tools/datasets/pothole81/data.yaml"
DEFAULT_WEIGHTS = REPO_ROOT / "yolo26n.pt"


def class_names(data_path: Path) -> list[str]:
    data = yaml.safe_load(data_path.read_text(encoding="utf-8"))
    names = data.get("names")
    if isinstance(names, dict):
        names = [names[index] for index in sorted(names)]
    if not isinstance(names, list) or len(names) != 81 or names[80] != "pothole":
        raise ValueError("Dataset YAML must contain the 80 COCO classes followed by 'pothole' at class 80")
    return names


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA,
                        help="Combined COCO + RDD2022 data.yaml from prepare_pothole_dataset.py")
    parser.add_argument("--weights", type=Path, default=DEFAULT_WEIGHTS,
                        help="COCO-pretrained YOLO26n checkpoint")
    parser.add_argument("--epochs", type=int, default=50)
    parser.add_argument("--batch", type=int, default=4,
                        help="Default 4 is intended for a 4 GB GPU; lower it if CUDA runs out of memory")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--device", default=None,
                        help="Optional Ultralytics device, such as 0 or cpu; defaults to auto")
    parser.add_argument("--project", type=Path, default=REPO_ROOT / "tools/runs/pothole")
    parser.add_argument("--name", default="yolo26n-81class")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    data_path = args.data.expanduser().resolve()
    weights = args.weights.expanduser().resolve()
    if not data_path.is_file():
        raise FileNotFoundError(f"Dataset config not found: {data_path}")
    if not weights.is_file():
        raise FileNotFoundError(f"Pretrained checkpoint not found: {weights}")
    class_names(data_path)
    if args.epochs <= 0 or args.batch <= 0 or args.workers < 0:
        raise ValueError("epochs and batch must be positive; workers cannot be negative")

    model = YOLO(str(weights))
    model.train(
        data=str(data_path),
        imgsz=640,
        epochs=args.epochs,
        batch=args.batch,
        workers=args.workers,
        patience=15,
        close_mosaic=10,
        cls_remap=True,
        project=str(args.project.expanduser().resolve()),
        name=args.name,
        exist_ok=False,
        device=args.device,
    )
    save_dir = Path(model.trainer.save_dir)
    best_weights = save_dir / "weights" / "best.pt"
    if not best_weights.is_file():
        raise FileNotFoundError(f"Training completed without best.pt at {best_weights}")
    print(f"Best checkpoint: {best_weights.resolve()}")
    print("Next: evaluate against coco_val_80.yaml, coco_val_81.yaml, and rdd_val_81.yaml, then export LiteRT.")


if __name__ == "__main__":
    main()
