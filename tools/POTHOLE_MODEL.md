# Pothole detector model workflow

The Android app runs one LiteRT detector per camera frame. The model uses the existing 80 COCO classes plus class 80, `pothole`. Training and export run on the development machine; inference runs on the phone.

## Prepare data

Download and extract [RDD2022](https://figshare.com/articles/dataset/RDD2022_-_The_multi-national_Road_Damage_Dataset_released_through_CRDDC_2022/21431547) and [COCO 2017](https://cocodataset.org/#download). RDD2022 provides Pascal VOC XML boxes; the preparation script keeps D40 pothole boxes and maps them to class 80. COCO labels remain classes 0–79.

Install the training/export dependencies in the Python environment you will use:

```powershell
python -m pip install -r tools/requirements-pothole.txt
```

Expected COCO layout:

```text
coco/
  annotations/instances_train2017.json
  annotations/instances_val2017.json
  images/train2017/...
  images/val2017/...
```

RDD2022 should be extracted so the country folders and their `train` XML/image files are below the supplied root. The script creates a deterministic held-out split, converts COCO and RDD labels, and writes an INT8 calibration sample balanced across COCO and pothole examples.

```powershell
python tools/prepare_pothole_dataset.py `
  --rdd-root "D:\datasets\RDD2022" `
  --coco-root "D:\datasets\coco" `
  --output tools/datasets/pothole81
```

## Train and evaluate

The default training settings use the existing COCO-pretrained YOLO26n checkpoint, 640-pixel input, batch size 4, and class-name remapping. Lower `--batch` if the training GPU runs out of memory.

```powershell
python tools/train_pothole_model.py --epochs 50 --batch 4
python tools/evaluate_pothole_model.py `
  --trained tools/runs/pothole/yolo26n-81class/weights/best.pt
```

Evaluation compares baseline and fine-tuned COCO mAP50 and reports RDD pothole recall. Target gates are no more than a 5 percentage-point drop on existing classes and at least 85% pothole recall. Record at least one annotated local-road video set separately; RDD2022 includes India imagery, but local camera angle, road surface, and lighting still need to be checked on the phone.

## Export and install

Ultralytics currently documents LiteRT export support on Linux x86_64 and macOS. If export fails on Windows, run this step in WSL2 or a Linux/macOS environment, then copy the exported `.tflite` model into the project.

Export and install the FP32 model first. The installer checks the 81 class names and backs up the current Android model/labels pair under `tools/model-backups/`.

```powershell
python tools/export_model.py `
  --weights tools/runs/pothole/yolo26n-81class/weights/best.pt `
  --install-assets
```

For an INT8 candidate, use the representative sample generated during preparation. Installing it replaces the active pair, so benchmark each candidate on the target phone and restore the preferred pair from the backup directory if needed.

```powershell
python tools/export_model.py `
  --weights tools/runs/pothole/yolo26n-81class/weights/best.pt `
  --quantize int8 `
  --data tools/datasets/pothole81/calibration.yaml `
  --install-assets
```

On the phone, verify sustained camera throughput of at least 10 FPS, overlay and spoken pothole alerts, and fewer than one false spoken alert per minute on local footage without potholes. The CPU fallback remains available when the GPU delegate cannot run the exported model.

## License

This project currently uses Ultralytics YOLO26. Its trained weights and training workflow are under the applicable Ultralytics license terms. The current assumption is personal/open-source use; resolve licensing before closed-source distribution.
