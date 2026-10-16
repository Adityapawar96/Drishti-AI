"""Export a trained YOLO checkpoint to LiteRT for the Android app."""

from __future__ import annotations

import argparse
import shutil
import sys
import warnings
from datetime import datetime
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]

from ultralytics import YOLO

from prepare_pothole_dataset import COCO_LABELS


DEFAULT_WEIGHTS = REPO_ROOT / "yolo26n.pt"


def ordered_names(names: dict | list) -> list[str]:
    if isinstance(names, dict):
        return [str(names[index]) for index in sorted(names)]
    return [str(name) for name in names]


def inspect_tflite(path: Path) -> None:
    try:
        import tensorflow as tf

        interpreter = tf.lite.Interpreter(model_path=str(path))
        interpreter.allocate_tensors()
        print("LiteRT input tensors:", [(item["shape"].tolist(), item["dtype"].__name__)
                                          for item in interpreter.get_input_details()])
        print("LiteRT output tensors:", [(item["shape"].tolist(), item["dtype"].__name__)
                                           for item in interpreter.get_output_details()])
    except Exception as error:
        print(f"Optional TensorFlow tensor inspection unavailable: {error}")


def install_assets(model_path: Path, names: list[str]) -> None:
    if len(names) != 81 or names[:80] != COCO_LABELS or names[80].lower() != "pothole":
        raise ValueError("Installing into the app requires the 80 COCO classes plus 'pothole' at index 80")

    assets = REPO_ROOT / "app/src/main/assets"
    model_target = assets / "yolo26n.tflite"
    labels_target = assets / "labels.txt"
    backup_dir = REPO_ROOT / "tools/model-backups" / datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    backup_dir.mkdir(parents=True, exist_ok=False)
    if model_target.exists():
        shutil.copy2(model_target, backup_dir / model_target.name)
    if labels_target.exists():
        shutil.copy2(labels_target, backup_dir / labels_target.name)

    model_temp = assets / "yolo26n.tflite.tmp"
    labels_temp = assets / "labels.txt.tmp"
    try:
        shutil.copy2(model_path, model_temp)
        labels_temp.write_text("\n".join(names) + "\n", encoding="utf-8")
        labels_temp.replace(labels_target)
        model_temp.replace(model_target)
    except Exception:
        model_temp.unlink(missing_ok=True)
        labels_temp.unlink(missing_ok=True)
        old_model = backup_dir / model_target.name
        old_labels = backup_dir / labels_target.name
        if old_model.exists():
            shutil.copy2(old_model, model_target)
        if old_labels.exists():
            shutil.copy2(old_labels, labels_target)
        raise
    print(f"Installed the matched 81-class model and labels; previous assets backed up to {backup_dir}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--weights", type=Path, default=DEFAULT_WEIGHTS,
                        help="Trained checkpoint (.pt); defaults to the original COCO model for export checks")
    parser.add_argument("--quantize", choices=("fp32", "int8"), default="fp32",
                        help="Use int8 only after calibration and device accuracy comparison")
    parser.add_argument("--data", type=Path,
                        help="Representative calibration YAML, such as tools/datasets/pothole81/calibration.yaml")
    parser.add_argument("--install-assets", action="store_true",
                        help="Install a verified 81-class pothole model into Android assets")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    weights = args.weights.expanduser().resolve()
    if not weights.is_file():
        raise FileNotFoundError(f"Checkpoint not found: {weights}")
    if args.quantize == "int8" and args.data is None:
        raise ValueError("INT8 export requires --data pointing to representative calibration images")
    data_path = args.data.expanduser().resolve() if args.data else None
    if data_path is not None and not data_path.is_file():
        raise FileNotFoundError(f"Calibration YAML not found: {data_path}")
    if sys.platform == "win32":
        warnings.warn(
            "Ultralytics currently documents LiteRT export for Linux x86_64 and macOS. "
            "If export fails on Windows, run this script from WSL2 or Linux.",
            RuntimeWarning,
        )

    model = YOLO(str(weights))
    names = ordered_names(model.names)
    quantize = 8 if args.quantize == "int8" else None
    export_args = {"format": "litert", "imgsz": 640}
    if quantize is not None:
        export_args["quantize"] = quantize
        export_args["data"] = str(data_path)
        export_args["fraction"] = 1.0
    exported = model.export(**export_args)
    output_path = Path(exported).resolve()
    if not output_path.is_file():
        raise FileNotFoundError(f"Export did not produce a LiteRT model: {output_path}")

    print(f"Exported model: {output_path}")
    print(f"Export precision: {args.quantize}; classes: {len(names)}")
    inspect_tflite(output_path)
    if args.install_assets:
        install_assets(output_path, names)


if __name__ == "__main__":
    main()
