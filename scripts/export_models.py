"""Prepara los modelos ONNX de IA Cámara (se ejecuta en un ordenador, no en el móvil).

- YOLO11n (detector rápido, 80 clases COCO) → yolo11n.onnx con NMS incluido.
- YOLOE-11s (vocabulario abierto) con el vocabulario de yoloe_vocabulary.tsv ya integrado
  → yoloe_vocab.onnx con NMS incluido, más yoloe_labels.tsv con los nombres en español.
- U²-Net pequeño (saliencia: "qué llama la atención") → u2netp.onnx (descargado de rembg).
- Face Landmarker de MediaPipe (ojos abiertos y mirada) → face_landmarker.task.

Uso: python export_models.py <carpeta_de_trabajo> <carpeta_assets_models>
Licencias: YOLO11 y YOLOE son AGPL-3.0 (permitido en uso personal); U²-Net Apache 2.0.
"""

import shutil
import sys
import urllib.request
from pathlib import Path

import onnx.utils
from ultralytics import YOLO, YOLOE

ASSETS = "https://github.com/ultralytics/assets/releases/download/v8.3.0"
U2NETP = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx"
FACE_LANDMARKER = (
    "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task"
)


def download(url: str, dest: Path) -> Path:
    if not dest.exists():
        urllib.request.urlretrieve(url, dest)
    return dest


def read_vocabulary(path: Path) -> list[tuple[str, str]]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        english, spanish = line.split("\t")
        rows.append((english.strip(), spanish.strip()))
    return rows


def main() -> None:
    work = Path(sys.argv[1]).resolve()
    out = Path(sys.argv[2]).resolve()
    work.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)
    vocab_file = Path(__file__).with_name("yoloe_vocabulary.tsv")

    # 1) YOLO11n
    yolo_pt = download(f"{ASSETS}/yolo11n.pt", work / "yolo11n.pt")
    onnx_path = YOLO(str(yolo_pt)).export(format="onnx", imgsz=512, nms=True, simplify=True, dynamic=False)
    shutil.copy(onnx_path, out / "yolo11n.onnx")

    # 2) YOLOE con vocabulario propio
    vocab = read_vocabulary(vocab_file)
    names = [english for english, _ in vocab]
    yoloe_pt = download(f"{ASSETS}/yoloe-11s-seg.pt", work / "yoloe-11s-seg.pt")
    model = YOLOE(str(yoloe_pt))
    model.set_classes(names, model.get_text_pe(names))
    onnx_path = model.export(format="onnx", imgsz=480, nms=True, simplify=True, dynamic=False)
    # Solo se necesitan las cajas: se quita la rama de máscaras (output1) para que sea más rápido.
    onnx.utils.extract_model(onnx_path, str(out / "yoloe_vocab.onnx"), ["images"], ["output0"])
    with open(out / "yoloe_labels.tsv", "w", encoding="utf-8") as f:
        for english, spanish in vocab:
            f.write(f"{english}\t{spanish}\n")

    # 3) U²-Net pequeño
    shutil.copy(download(U2NETP, work / "u2netp.onnx"), out / "u2netp.onnx")

    # 4) Face Landmarker (MediaPipe)
    shutil.copy(download(FACE_LANDMARKER, work / "face_landmarker.task"), out / "face_landmarker.task")

    for p in sorted(out.glob("*")):
        print(f"{p.name}: {p.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
