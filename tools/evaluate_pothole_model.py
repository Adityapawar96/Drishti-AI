"""Compare the original and fine-tuned model on COCO and RDD validation splits."""

from __future__ import annotations

import argparse
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]

from ultralytics import YOLO


DATA_ROOT = REPO_ROOT / "tools/datasets/pothole81"


def evaluate(weights: Path, data: Path, title: str, device: str | None) -> object:
    model = YOLO(str(weights))
    metrics = model.val(data=str(data), imgsz=640, device=device, plots=False, verbose=False)
    print(f"\n{title}: {weights}")
    if hasattr(metrics, "results_dict"):
        for name, value in metrics.results_dict.items():
            if isinstance(value, (int, float)):
                print(f"  {name}: {value:.4f}")
    box = getattr(metrics, "box", None)
    if box is not None:
        print(f"  mAP50: {box.map50:.4f}; mAP50-95: {box.map:.4f}")
    return metrics


def class_metric(metrics: object, class_id: int, metric_name: str) -> float | None:
    indices = getattr(metrics, "ap_class_index", [])
    values = getattr(getattr(metrics, "box", None), metric_name, None)
    if values is None:
        return None
    for position, index in enumerate(indices):
        if int(index) == class_id and position < len(values):
            return float(values[position])
    return None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=REPO_ROOT / "yolo26n.pt")
    parser.add_argument("--trained", type=Path, required=True)
    parser.add_argument("--data-root", type=Path, default=DATA_ROOT)
    parser.add_argument("--device", default=None)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    baseline = args.baseline.expanduser().resolve()
    trained = args.trained.expanduser().resolve()
    data_root = args.data_root.expanduser().resolve()
    for path in (baseline, trained):
        if not path.is_file():
            raise FileNotFoundError(f"Model checkpoint not found: {path}")

    baseline_coco = evaluate(baseline, data_root / "coco_val_80.yaml", "Baseline COCO", args.device)
    trained_coco = evaluate(trained, data_root / "coco_val_81.yaml", "Fine-tuned COCO", args.device)
    rdd = evaluate(trained, data_root / "rdd_val_81.yaml", "Fine-tuned RDD2022", args.device)

    baseline_map = float(baseline_coco.box.map50)
    trained_map = float(trained_coco.box.map50)
    drop_points = (baseline_map - trained_map) * 100
    pothole_recall = class_metric(rdd, 80, "r")
    print("\nAcceptance checks:")
    print(f"  Existing COCO mAP50 change: {(trained_map - baseline_map) * 100:+.2f} percentage points")
    print(f"  Existing-object regression within 5 points: {'PASS' if drop_points <= 5 else 'FAIL'}")
    if pothole_recall is None:
        print("  RDD pothole recall: unavailable (check that the validation split contains D40 boxes)")
    else:
        print(f"  RDD pothole recall: {pothole_recall:.4f} ({'PASS' if pothole_recall >= 0.85 else 'BELOW 0.85 TARGET'})")
    print("  Local footage recall and false spoken alerts/minute still require device validation in the app.")


if __name__ == "__main__":
    main()
