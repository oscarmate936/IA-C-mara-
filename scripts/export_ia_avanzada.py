"""Prepara los modelos del paquete de IA avanzada (se ejecuta en un ordenador, no en el móvil).

- TinyCLIP ViT-40M/32 (LAION-400M, licencia MIT) → clip_image.onnx (codificador de imagen, int8)
  + clip_scenes.bin/.tsv: los textos de cada tipo de escena ya codificados. En el móvil solo hace falta
  el codificador de imagen; el de texto se usa aquí una vez.
- NIMA estética (MobileNet, pesos de idealo, Apache 2.0) → nima_aesthetic.onnx: nota 1–10 de lo
  "bonita" que es una foto, aprendida de ~250.000 fotos puntuadas por personas (AVA).
- YOLO11s (AGPL-3.0, uso personal) → yolo11s.onnx: detector más preciso para móviles potentes, sin la
  selección de detecciones (cortar_nms.py; la hace la app).
- Pose Landmarker full (MediaPipe, Apache 2.0) → pose_landmarker_full.task (más preciso que el lite con
  poses difíciles: sentado, de lado, brazos cruzados).

Uso: python export_ia_avanzada.py <carpeta_de_trabajo> <carpeta_assets_models>
Los pesos se cargan siempre en modo seguro (sin ejecutar código de los archivos descargados).
"""

import json
import shutil
import struct
import sys
import urllib.request
from pathlib import Path

import h5py
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

TINYCLIP = (
    "https://github.com/wkcn/TinyCLIP-model-zoo/releases/download/checkpoints/"
    "TinyCLIP-ViT-40M-32-Text-19M-LAION400M.pt"
)
NIMA = (
    "https://raw.githubusercontent.com/idealo/image-quality-assessment/master/models/MobileNet/"
    "weights_mobilenet_aesthetic_0.07.hdf5"
)
POSE = (
    "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/"
    "pose_landmarker_full.task"
)
YOLO11S = "https://github.com/ultralytics/assets/releases/download/v8.3.0/yolo11s.pt"

# Textos (en inglés, el idioma de CLIP) de cada tipo de escena. Se promedian los de cada grupo.
SCENE_PROMPTS: dict[str, list[str]] = {
    "RETRATO": ["a portrait photo of a person", "a close-up photo of a person's face", "a photo of a child"],
    "GRUPO": ["a group photo of many people", "a photo of a crowd of people", "a team photo"],
    "MASCOTA": ["a photo of a dog", "a photo of a cat", "a photo of a pet", "a photo of a horse",
                "a photo of a bird", "a photo of a farm animal"],
    "COMIDA": ["a photo of food on a plate", "a photo of a meal", "a photo of a dessert", "a photo of a drink"],
    "FLORES": ["a close-up photo of a flower", "a photo of flowers", "a photo of a garden with flowers"],
    "MACRO": ["a macro photo of an insect", "an extreme close-up photo of a small object", "a macro photo of a leaf"],
    "PAISAJE": ["a landscape photo of mountains", "a photo of a green field", "a photo of a forest",
                "a photo of nature scenery", "a photo of a lake", "a photo of a countryside road"],
    "PLAYA": ["a photo of a beach", "a photo of the sea", "a photo of waves on the shore"],
    "NIEVE": ["a photo of snow", "a photo of a snowy landscape", "a photo of a ski slope"],
    "ATARDECER": ["a photo of a sunset", "a photo of a sunrise", "a photo of the sky at golden hour"],
    "CIUDAD": ["a photo of a building", "a photo of a city street", "a photo of architecture",
               "a photo of a church", "a photo of a bridge"],
    "NOCHE": ["a photo taken at night", "a photo of a city at night", "a photo of a dark scene with lights"],
    "INTERIOR": ["a photo of a living room", "a photo of a room interior", "a photo of an office",
                 "a photo of a kitchen", "a photo inside a restaurant"],
    "OBJETO": ["a product photo of an object", "a photo of an object on a table", "a photo of a car",
               "a photo of a phone", "a photo of shoes"],
}


def download(url: str, dest: Path) -> Path:
    if not dest.exists():
        urllib.request.urlretrieve(url, dest)
    return dest


# ---------------------------------------------------------------- CLIP

def build_clip(ckpt: Path):
    import open_clip

    cfg = {
        "embed_dim": 512,
        "vision_cfg": {"image_size": 224, "layers": 12, "width": 512, "patch_size": 32},
        "text_cfg": {"context_length": 77, "vocab_size": 49408, "width": 512, "heads": 8, "layers": 6},
    }
    model = open_clip.model.CLIP(**cfg, quick_gelu=True)
    state = torch.load(ckpt, map_location="cpu", weights_only=True)["state_dict"]
    state = {k.removeprefix("module."): v for k, v in state.items()}
    missing, unexpected = model.load_state_dict(state, strict=False)
    assert not missing and not unexpected, (missing, unexpected)
    model.eval()
    tokenizer = open_clip.get_tokenizer("ViT-B-32")
    return model, tokenizer


class ImageEncoder(nn.Module):
    def __init__(self, clip):
        super().__init__()
        self.visual = clip.visual

    def forward(self, x):
        return F.normalize(self.visual(x), dim=-1)


def export_clip(work: Path, out: Path) -> None:
    from onnxruntime.quantization import QuantType, quantize_dynamic

    model, tokenizer = build_clip(download(TINYCLIP, work / "tinyclip.pt"))
    fp32 = work / "clip_image_fp32.onnx"
    torch.onnx.export(
        ImageEncoder(model), torch.zeros(1, 3, 224, 224), str(fp32),
        input_names=["image"], output_names=["embedding"], opset_version=17, dynamo=False,
    )
    # int8 por canal solo en las multiplicaciones de matrices: 45 MB en vez de 159 MB y, con las fotos de
    # prueba, la misma dirección que el original (similitud coseno ≥ 0,99).
    quantize_dynamic(
        str(fp32), str(out / "clip_image.onnx"),
        weight_type=QuantType.QInt8, per_channel=True, op_types_to_quantize=["MatMul"],
    )

    groups = list(SCENE_PROMPTS)
    with torch.no_grad():
        rows = []
        for g in groups:
            emb = F.normalize(model.encode_text(tokenizer(SCENE_PROMPTS[g])), dim=-1).mean(0)
            rows.append(F.normalize(emb, dim=-1))
        text = torch.stack(rows).numpy().astype("<f4")
    # Formato: nº de filas, dimensión, escala de los logits y la matriz (float32, little endian).
    with open(out / "clip_scenes.bin", "wb") as f:
        f.write(struct.pack("<iif", text.shape[0], text.shape[1], float(model.logit_scale.exp())))
        f.write(text.tobytes())
    (out / "clip_scenes.tsv").write_text("\n".join(groups) + "\n", encoding="utf-8")


# ---------------------------------------------------------------- NIMA (MobileNet v1 de Keras)

class KerasMobileNetNima(nn.Module):
    """MobileNet v1 (alpha 1, 224 px) como en Keras, con la cabeza de 10 notas de NIMA."""

    BLOCKS = [(64, 1), (128, 2), (128, 1), (256, 2), (256, 1), (512, 2),
              (512, 1), (512, 1), (512, 1), (512, 1), (512, 1), (1024, 2), (1024, 1)]

    def __init__(self):
        super().__init__()
        self.conv1 = nn.Conv2d(3, 32, 3, 2, 0, bias=False)
        self.bn1 = nn.BatchNorm2d(32, eps=1e-3)
        self.dw, self.dw_bn, self.pw, self.pw_bn = (nn.ModuleList() for _ in range(4))
        cin = 32
        for cout, stride in self.BLOCKS:
            self.dw.append(nn.Conv2d(cin, cin, 3, stride, 0, groups=cin, bias=False))
            self.dw_bn.append(nn.BatchNorm2d(cin, eps=1e-3))
            self.pw.append(nn.Conv2d(cin, cout, 1, 1, 0, bias=False))
            self.pw_bn.append(nn.BatchNorm2d(cout, eps=1e-3))
            cin = cout
        self.fc = nn.Linear(1024, 10)

    @staticmethod
    def pad(x, stride):
        # Keras: relleno asimétrico (0,1) antes de las capas con paso 2; 'same' (1,1) con paso 1.
        return F.pad(x, (0, 1, 0, 1)) if stride == 2 else F.pad(x, (1, 1, 1, 1))

    def forward(self, x):
        x = F.relu6(self.bn1(self.conv1(self.pad(x, 2))))
        for i, (_, stride) in enumerate(self.BLOCKS):
            x = F.relu6(self.dw_bn[i](self.dw[i](self.pad(x, stride))))
            x = F.relu6(self.pw_bn[i](self.pw[i](x)))
        x = x.mean(dim=(2, 3))
        return F.softmax(self.fc(x), dim=-1)


def load_nima(h5: Path) -> KerasMobileNetNima:
    m = KerasMobileNetNima().eval()
    with h5py.File(h5, "r") as f:
        root = f["model_weights"] if "model_weights" in f else f
        flat = {}

        def visit(name, obj):
            if isinstance(obj, h5py.Dataset):
                flat[name.split("/")[-2] + "/" + name.split("/")[-1].split(":")[0]] = np.array(obj)

        root.visititems(visit)

    def bn(mod, key):
        mod.weight.data = torch.from_numpy(flat[f"{key}/gamma"])
        mod.bias.data = torch.from_numpy(flat[f"{key}/beta"])
        mod.running_mean.data = torch.from_numpy(flat[f"{key}/moving_mean"])
        mod.running_var.data = torch.from_numpy(flat[f"{key}/moving_variance"])

    m.conv1.weight.data = torch.from_numpy(flat["conv1/kernel"]).permute(3, 2, 0, 1).contiguous()
    bn(m.bn1, "conv1_bn")
    for i in range(13):
        k = i + 1
        m.dw[i].weight.data = torch.from_numpy(flat[f"conv_dw_{k}/depthwise_kernel"]).permute(2, 3, 0, 1).contiguous()
        bn(m.dw_bn[i], f"conv_dw_{k}_bn")
        m.pw[i].weight.data = torch.from_numpy(flat[f"conv_pw_{k}/kernel"]).permute(3, 2, 0, 1).contiguous()
        bn(m.pw_bn[i], f"conv_pw_{k}_bn")
    dense = [k for k in flat if k.endswith("/kernel") and flat[k].shape == (1024, 10)][0].split("/")[0]
    m.fc.weight.data = torch.from_numpy(flat[f"{dense}/kernel"]).t().contiguous()
    m.fc.bias.data = torch.from_numpy(flat[f"{dense}/bias"])
    return m


def export_nima(work: Path, out: Path) -> None:
    model = load_nima(download(NIMA, work / "nima_aesthetic.hdf5"))
    torch.onnx.export(
        model, torch.zeros(1, 3, 224, 224), str(out / "nima_aesthetic.onnx"),
        input_names=["image"], output_names=["scores"], opset_version=17, dynamo=False,
    )


# ---------------------------------------------------------------- YOLO11s y pose

def export_yolo11s(work: Path, out: Path) -> None:
    from ultralytics import YOLO

    pt = download(YOLO11S, work / "yolo11s.pt")
    onnx_path = YOLO(str(pt)).export(format="onnx", imgsz=512, nms=True, simplify=True, dynamic=False)
    # Sin la selección de detecciones (el NPU la ejecuta mal): la hace la app.
    from cortar_nms import cut_nms

    cut_nms(str(onnx_path), str(out / "yolo11s.onnx"))


def main() -> None:
    work = Path(sys.argv[1]).resolve()
    out = Path(sys.argv[2]).resolve()
    work.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)
    only = set(sys.argv[3].split(",")) if len(sys.argv) > 3 else {"clip", "nima", "yolo", "pose"}
    if "clip" in only:
        export_clip(work, out)
    if "nima" in only:
        export_nima(work, out)
    if "yolo" in only:
        export_yolo11s(work, out)
    if "pose" in only:
        shutil.copy(download(POSE, work / "pose_landmarker_full.task"), out / "pose_landmarker_full.task")
    for p in sorted(out.glob("*")):
        print(f"{p.name}: {p.stat().st_size / 1e6:.1f} MB")
    print(json.dumps({g: len(p) for g, p in SCENE_PROMPTS.items()}))


if __name__ == "__main__":
    main()
