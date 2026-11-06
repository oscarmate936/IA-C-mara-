"""Prepara los modelos ONNX de IA Cámara (se ejecuta en un ordenador, no en el móvil).

- YOLO26n (detector rápido, 80 clases COCO) → yolo26n.onnx sin NMS (salida [1, 84, 5376]; la selección de
  detecciones la hace la app, YoloPost.kt) y coco_labels.txt.
- YuNet (caras, OpenCV Zoo, MIT) → yunet.onnx a 640×640 con una sola salida [1, 8400, 16].
- YOLOE-11s (vocabulario abierto) con el vocabulario de yoloe_vocabulary.tsv ya integrado
  → yoloe_vocab.onnx con NMS incluido, más yoloe_labels.tsv con los nombres en español.
- U²-Net pequeño (saliencia: "qué llama la atención") → u2netp.onnx (descargado de rembg).
- Face Landmarker de MediaPipe (ojos abiertos y mirada) → face_landmarker.task.

Uso: python export_models.py <carpeta_de_trabajo> <carpeta_assets_models>
Licencias: YOLO26 y YOLOE son AGPL-3.0 (permitido en uso personal); U²-Net Apache 2.0; YuNet MIT.
"""

import shutil
import sys
import urllib.request
from pathlib import Path

import onnx
import onnx.utils
from ultralytics import YOLO, YOLOE

ASSETS = "https://github.com/ultralytics/assets/releases/download/v8.3.0"
YOLO26N = "https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo26n.pt"
YUNET = (
    "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_detection_yunet/"
    "face_detection_yunet_2023mar.onnx"
)
U2NETP = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx"
FACE_LANDMARKER = (
    "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task"
)


def download(url: str, dest: Path) -> Path:
    if not dest.exists():
        urllib.request.urlretrieve(url, dest)
    return dest


def export_yunet(src: Path, dst: Path, size: int = 640) -> None:
    """[1, N, 16] por posición: cls, obj, caja (4) y 5 puntos (10); primero paso 8, luego 16 y 32."""
    from onnx import TensorProto, helper

    m = onnx.load(str(src))
    dims = m.graph.input[0].type.tensor_type.shape.dim
    dims[2].dim_value = size
    dims[3].dim_value = size
    del m.graph.value_info[:]
    g = m.graph
    parts = []
    for s in (8, 16, 32):
        g.node.append(helper.make_node("Concat", [f"cls_{s}", f"obj_{s}", f"bbox_{s}", f"kps_{s}"], [f"faces_{s}"], axis=2))
        parts.append(f"faces_{s}")
    g.node.append(helper.make_node("Concat", parts, ["faces"], axis=1))
    del g.output[:]
    n = sum((size // s) ** 2 for s in (8, 16, 32))
    g.output.append(helper.make_tensor_value_info("faces", TensorProto.FLOAT, [1, n, 16]))
    m = onnx.shape_inference.infer_shapes(m)
    onnx.checker.check_model(m, full_check=True)
    onnx.save(m, str(dst))


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

    # 1) YOLO26n, sin NMS (end2end=False): la red sola va mejor en el NPU y la selección se hace en la app.
    yolo_pt = download(YOLO26N, work / "yolo26n.pt")
    model = YOLO(str(yolo_pt))
    onnx_path = model.export(format="onnx", imgsz=512, end2end=False, simplify=True, dynamic=False, opset=18)
    shutil.copy(onnx_path, out / "yolo26n.onnx")
    (out / "coco_labels.txt").write_text("\n".join(model.names[i] for i in range(len(model.names))) + "\n")

    # 1b) YuNet: entrada fija de 640×640 y las 12 salidas juntas en una (la app usa la primera salida).
    export_yunet(download(YUNET, work / "face_detection_yunet_2023mar.onnx"), out / "yunet.onnx")

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
