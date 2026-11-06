"""Versiones INT8 de los modelos para el NPU de los Snapdragon que no admiten FP16 (p. ej. el del Honor).

Los modelos normales (FP32) se ejecutan en el procesador o, en NPU con FP16, en los Snapdragon de gama alta.
Los NPU de gama media (Snapdragon 6 Gen 4, SM6650) solo trabajan en enteros: para ellos se crean copias
cuantizadas a 8 bits (QDQ, pesos por canal), con la configuración de ONNX Runtime para QNN.

Antes de cuantizar:
- se pasa a opset 13 (la cuantización por canal lo necesita) y se calculan de antemano las operaciones de
  forma (la entrada tiene tamaño fijo);
- los canales casi constantes (U²-Net tiene uno que vale ~226 en toda la imagen) se pasan al sesgo de la
  convolución siguiente, con una corrección exacta en el borde: en 8 bits no caben;
- lo que va después de "aplanar" la imagen (la decodificación de cajas y el NMS de YOLO) se deja en coma
  flotante: junta valores de 0–512 con probabilidades de 0–1 y es poco cálculo.

Calibración: imágenes reales y sintéticas preprocesadas igual que en la app. Después se comprueba con otras
imágenes que la versión INT8 da los mismos resultados que la original (mismas detecciones, mismo mapa de
saliencia, misma nota de estética) con los mismos criterios que la app (OutputCheck); si falla en más del
10 % de las imágenes, no se guarda. NIMA no pasa (el error se acumula capa a capa en MobileNet) y se queda
sin versión INT8: es ligera y en el procesador va bien.

Uso: python cuantizar_npu.py [--solo=u2netp,yolo26n] <carpeta_assets_models> <carpeta_salida> <carpeta_imágenes>...
     python cuantizar_npu.py --ajustar <modelo_int8.onnx>...   (solo los ajustes para el NPU)
Los *_int8.onnx de la carpeta de salida se copian a app/src/main/assets/models/.
"""

import glob
import shutil
import sys
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from onnxruntime.quantization import CalibrationDataReader, QuantType, quantize
from onnxruntime.quantization.execution_providers.qnn import get_qnn_qdq_config, qnn_preprocess_model
from PIL import Image, ImageEnhance, ImageOps

# Operaciones que se cuantizan (la red); el resto (NMS, TopK, Gather…) queda en coma flotante.
NET_OPS = ["Conv", "Add", "Mul", "Sub", "Div", "Sigmoid", "Concat", "MaxPool", "AveragePool", "GlobalAveragePool",
           "Resize", "Split", "Slice", "Reshape", "Transpose", "Softmax", "MatMul", "Gemm", "Relu", "Clip",
           "BatchNormalization"]


def load_images(dirs):
    files = []
    for d in dirs:
        files += sorted(glob.glob(str(Path(d) / "*.jpg"))) + sorted(glob.glob(str(Path(d) / "*.png")))
    return [ImageOps.exif_transpose(Image.open(f)).convert("RGB") for f in files]


def night(im, k, seed):
    """La foto como de noche: oscura y con el ruido del sensor (el visor también se usa de noche)."""
    rnd = np.random.default_rng(seed)
    a = np.asarray(ImageEnhance.Brightness(im).enhance(k)).astype(np.float32)
    a += rnd.normal(0, 3, a.shape)
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def augment(images):
    """Recortes, giros y cambios de luz (también de noche): más variedad para calibrar y comprobar."""
    out = []
    for i, im in enumerate(images):
        w, h = im.size
        out.append(im)
        out.append(ImageOps.mirror(im))
        out.append(im.crop((w // 6, h // 6, w - w // 6, h - h // 6)))
        out.append(ImageEnhance.Brightness(im).enhance(0.45))
        out.append(ImageEnhance.Brightness(im).enhance(1.4))
        out.append(night(im, 0.3, i))
        out.append(night(ImageOps.mirror(im), 0.15, i + 1000))
    return out


def letterbox(im, size):
    w, h = im.size
    s = size / max(w, h)
    nw, nh = round(w * s), round(h * s)
    canvas = Image.new("RGB", (size, size), (114, 114, 114))
    canvas.paste(im.resize((nw, nh), Image.BILINEAR), ((size - nw) // 2, (size - nh) // 2))
    return np.asarray(canvas).astype(np.float32).transpose(2, 0, 1)[None] / 255.0


def u2net_input(im):
    a = np.asarray(im.resize((320, 320), Image.BILINEAR)).astype(np.float32) / 255.0
    a = (a - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]
    return a.transpose(2, 0, 1)[None].astype(np.float32)


def nima_input(im):
    a = np.asarray(im.resize((224, 224), Image.BILINEAR)).astype(np.float32) / 127.5 - 1.0
    return a.transpose(2, 0, 1)[None].astype(np.float32)


def yunet_input(im, size=640):
    """Como la app: pegada arriba a la izquierda, relleno negro, BGR de 0 a 255 (sin normalizar)."""
    s = size / max(im.width, im.height)
    canvas = Image.new("RGB", (size, size), (0, 0, 0))
    canvas.paste(im.resize((round(im.width * s), round(im.height * s)), Image.BILINEAR), (0, 0))
    a = np.asarray(canvas).astype(np.float32)[:, :, ::-1]
    return a.transpose(2, 0, 1)[None].copy()


MODELS = {
    "u2netp": ("u2netp.onnx", u2net_input),
    "yolo26n": ("yolo26n.onnx", lambda im: letterbox(im, 512)),
    "yolo11s": ("yolo11s.onnx", lambda im: letterbox(im, 512)),
    "yoloe": ("yoloe_vocab.onnx", lambda im: letterbox(im, 480)),
    "nima": ("nima_aesthetic.onnx", nima_input),
    "yunet": ("yunet.onnx", yunet_input),
}


# ---------------------------------------------------------------- canales casi constantes

# Operaciones por canal que conmutan con restar una constante a cada canal (si nada pasa a negativo).
_SHIFTABLE = {"Relu", "MaxPool", "Resize"}


def remove_constant_channels(model, name, inputs):
    """Saca de las activaciones los canales que valen casi siempre lo mismo y lo pasa al sesgo.

    U²-Net tiene canales que valen ~226 en toda la imagen y que la convolución siguiente multiplica por
    pesos grandes: hace de sesgo enorme. En 8 bits eso no cabe (el error del peso por 226 se come la
    señal). Se resta el valor m en la convolución que lo produce (el canal sigue siendo positivo, así
    que la Relu no cambia nada) y se suma m·Σpesos al sesgo de la siguiente. En el borde, la segunda
    convolución rellena con ceros (que en el original equivalían a restar m): justo después se suma un
    mapa fijo con esa diferencia, así que en coma flotante da lo mismo.
    """
    from onnx import helper, numpy_helper

    g = model.graph
    prod = {o: n for n in g.node for o in n.output}
    users = {}
    for n in g.node:
        for i in n.input:
            users.setdefault(i, []).append(n)
    graph_outputs = {o.name for o in g.output}
    inits = {i.name: i for i in g.initializer}

    def group(n):
        return next((a.i for a in n.attribute if a.name == "group"), 1)

    def single_use(t):
        return len(users.get(t, [])) == 1 and t not in graph_outputs

    def source_conv(t):
        """Convolución de la que sale `t` pasando solo por operaciones por canal de un único uso."""
        while single_use(t):
            p = prod.get(t)
            if p is None:
                return None
            if p.op_type == "Conv":
                ok = len(p.input) == 3 and p.input[1] in inits and p.input[2] in inits and group(p) == 1
                return p if ok else None
            if p.op_type not in _SHIFTABLE:
                return None
            t = p.input[0]
        return None

    plans = []
    for q in g.node:
        if q.op_type != "Conv" or len(q.input) < 3 or q.input[1] not in inits or q.input[2] not in inits or group(q) != 1:
            continue
        x = q.input[0]
        if not single_use(x):
            continue
        cat = prod.get(x)
        parts = list(cat.input) if cat is not None and cat.op_type == "Concat" else [x]
        plans.append((q, parts))
    tensors = sorted({t for _, parts in plans for t in parts if source_conv(t) is not None})
    if not tensors:
        return model, 0

    probe = onnx.ModelProto()
    probe.CopyFrom(model)
    for t in tensors:
        probe.graph.output.append(helper.make_tensor_value_info(t, onnx.TensorProto.FLOAT, None))
    sess = ort.InferenceSession(probe.SerializeToString(), providers=["CPUExecutionProvider"])
    lo, hi, total, sq, count = {}, {}, {}, {}, {}
    for xin in inputs:
        for t, v in zip(tensors, sess.run(tensors, {name: xin})):
            m = v[0].reshape(v.shape[1], -1).astype(np.float64)
            lo[t] = np.minimum(lo.get(t, np.inf), m.min(1))
            hi[t] = np.maximum(hi.get(t, -np.inf), m.max(1))
            total[t] = total.get(t, 0) + m.sum(1)
            sq[t] = sq.get(t, 0) + (m * m).sum(1)
            count[t] = count.get(t, 0) + m.shape[1]

    arrays = {k: numpy_helper.to_array(v).copy() for k, v in inits.items()}
    moved = 0
    borders = {}
    shapes = {v.name: [d.dim_value for d in v.type.tensor_type.shape.dim]
              for v in onnx.shape_inference.infer_shapes(model).graph.value_info}
    for q, parts in plans:
        off = 0
        for t in parts:
            if t not in lo:
                # Tramo que no viene de una convolución: solo se cuentan sus canales.
                off += _channels(model, t, q, parts, lo)
                continue
            n = len(lo[t])
            p = source_conv(t)
            mean = total[t] / count[t]
            std = np.sqrt(np.maximum(sq[t] / count[t] - mean * mean, 0))
            # Casi constante y grande: la variación es menos del 1 % del valor.
            for c in np.nonzero((lo[t] > 20) & (std < 0.01 * mean))[0]:
                shift = np.float32(lo[t][c] - max(0.5, 0.2 * (hi[t][c] - lo[t][c])))
                arrays[p.input[2]][c] -= shift
                wq = arrays[q.input[1]]
                arrays[q.input[2]] += shift * wq[:, off + c].sum(axis=(1, 2))
                ring = _border_map(q, wq[:, off + c], shapes[q.input[0]][2:], shift)
                if ring is not None:
                    borders[q.output[0]] = borders.get(q.output[0], 0) + ring
                moved += 1
            off += n
    for k, v in arrays.items():
        inits[k].CopyFrom(numpy_helper.from_array(v.astype(numpy_helper.to_array(inits[k]).dtype), k))
    # Corrección del borde: conv → (+ mapa fijo) → lo que viniera después.
    for out_name, ring in borders.items():
        q = next(n for n in g.node if n.output[0] == out_name)
        raw = out_name + "_sin_borde"
        q.output[0] = raw
        g.initializer.append(numpy_helper.from_array(ring[None].astype(np.float32), out_name + "_borde"))
        g.node.insert(list(g.node).index(q) + 1, helper.make_node("Add", [raw, out_name + "_borde"], [out_name]))
    return model, moved


def _border_map(conv, w, size, shift):
    """Lo que el relleno con ceros de `conv` deja de restar: shift·Σ pesos que caen fuera, por píxel."""
    att = {a.name: list(a.ints) for a in conv.attribute}
    if any(v != 1 for v in att.get("strides", [1, 1])):
        return None
    pads = att.get("pads", [0, 0, 0, 0])
    if not any(pads):
        return None
    dil = att.get("dilations", [1, 1])
    h, wd = size
    kh, kw = w.shape[1:]
    inside = np.pad(np.ones((h, wd), np.float32), ((pads[0], pads[2]), (pads[1], pads[3])))
    resp = np.zeros((w.shape[0], h, wd), np.float32)
    for ky in range(kh):
        for kx in range(kw):
            win = inside[ky * dil[0]:ky * dil[0] + h, kx * dil[1]:kx * dil[1] + wd]
            resp += w[:, ky, kx][:, None, None] * win[None]
    # Dentro de la imagen resp = Σ pesos (ya está en el sesgo); en el borde falta la parte de fuera.
    return shift * (resp - w.sum(axis=(1, 2))[:, None, None])


def _channels(model, t, q, parts, known):
    """Número de canales de `t` (por la inferencia de formas)."""
    for vi in list(model.graph.value_info) + list(model.graph.input):
        if vi.name == t:
            return vi.type.tensor_type.shape.dim[1].dim_value
    inferred = onnx.shape_inference.infer_shapes(model)
    for vi in inferred.graph.value_info:
        if vi.name == t:
            return vi.type.tensor_type.shape.dim[1].dim_value
    raise ValueError(f"no se sabe cuántos canales tiene {t}")


def float_tail(model):
    """Nodos que se dejan en coma flotante: todo lo que va después de "aplanar" la imagen.

    En YOLO es la cabeza que decodifica las cajas (en píxeles, 0–512) y las junta con las probabilidades
    (0–1) en un mismo tensor, más el NMS: con una sola escala de 8 bits las probabilidades se pierden.
    En NIMA es la capa final (Dense + Softmax). Es poco cálculo y va en el procesador.
    """
    from onnx import shape_inference

    m = shape_inference.infer_shapes(model)
    rank = {v.name: len(v.type.tensor_type.shape.dim) for v in list(m.graph.value_info) + list(m.graph.input)
            + list(m.graph.output)}
    starts = [n for n in m.graph.node if n.op_type in ("Reshape", "Flatten", "Squeeze", "ReduceMean")
              and rank.get(n.input[0]) == 4 and rank.get(n.output[0], 4) < 4]
    users = {}
    for n in m.graph.node:
        for i in n.input:
            users.setdefault(i, []).append(n)
    tail, stack = set(), list(starts)
    while stack:
        n = stack.pop()
        if n.name in tail:
            continue
        tail.add(n.name)
        for o in n.output:
            stack += users.get(o, [])
    return sorted(tail)


# Operaciones que dependen de la disposición de los datos (NCHW/NHWC). ONNX Runtime deja que el NPU las acepte
# provisionalmente en una primera pasada y solo las valida de verdad tras pasarlas a NHWC: si entonces el chip
# las rechaza, la sesión entera queda inválida ("... was inserted using the NHWC format ... but was not
# selected by that EP"). Por eso en el modelo de 8 bits no puede quedar ninguna en coma flotante.
LAYOUT_SENSITIVE = {"Conv", "ConvTranspose", "MaxPool", "AveragePool", "GlobalAveragePool", "GlobalMaxPool",
                    "Resize", "BatchNormalization", "InstanceNormalization", "DepthToSpace", "SpaceToDepth",
                    "GridSample", "LRN"}


def npu_friendly(model):
    """Ajustes para que el NPU acepte el modelo de 8 bits sin cambiar el resultado.

    - Resize lineal con 'pytorch_half_pixel' → 'half_pixel' (idénticos si la salida mide más de 1 píxel):
      en el NPU el primero usa la operación Resize genérica y el segundo ResizeBilinear, que admiten más chips.
    - Conv 1×1 en coma flotante con una sola salida (la "DFL" de YOLO) → Mul + ReduceSum, que el NPU
      rechaza limpiamente (y van al procesador) en vez de aceptarla y fallar después.
    @return el modelo y la lista de operaciones sensibles a la disposición que siguen en coma flotante.
    """
    from onnx import helper, numpy_helper, shape_inference

    g = model.graph
    shapes = {v.name: [d.dim_value for d in v.type.tensor_type.shape.dim]
              for v in list(shape_inference.infer_shapes(model).graph.value_info) + list(g.output)}
    prod = {o: n for n in g.node for o in n.output}
    inits = {i.name: i for i in g.initializer}

    def quantized_input(n):
        p = prod.get(n.input[0])
        return p is not None and p.op_type == "DequantizeLinear"

    new_nodes = []
    for n in g.node:
        if n.op_type == "Resize":
            attrs = {a.name: a for a in n.attribute}
            mode = attrs["mode"].s.decode() if "mode" in attrs else "nearest"
            ctm = attrs.get("coordinate_transformation_mode")
            out = shapes.get(n.output[0], [])
            if mode == "linear" and ctm is not None and ctm.s == b"pytorch_half_pixel" and len(out) == 4 and min(out[2:]) > 1:
                ctm.s = b"half_pixel"
        if n.op_type == "Conv" and not quantized_input(n) and len(n.input) == 2 and n.input[1] in inits:
            w = numpy_helper.to_array(inits[n.input[1]])
            attrs = {a.name: helper.get_attribute_value(a) for a in n.attribute}
            simple = (w.shape[0] == 1 and w.shape[2:] == (1, 1) and attrs.get("group", 1) == 1
                      and all(v == 1 for v in attrs.get("strides", [1, 1]))
                      and not any(attrs.get("pads", [0, 0, 0, 0])))
            if simple:
                wname = n.name + "_peso"
                aname = n.name + "_eje"
                g.initializer.append(numpy_helper.from_array(w.reshape(1, w.shape[1], 1, 1), wname))
                g.initializer.append(numpy_helper.from_array(np.array([1], dtype=np.int64), aname))
                mul = helper.make_node("Mul", [n.input[0], wname], [n.output[0] + "_mul"], name=n.name + "_mul")
                red = helper.make_node("ReduceSum", [n.output[0] + "_mul", aname], [n.output[0]], keepdims=1,
                                       name=n.name + "_suma")
                new_nodes += [mul, red]
                continue
        new_nodes.append(n)
    del g.node[:]
    g.node.extend(new_nodes)
    used = {i for n in g.node for i in n.input}
    unused = [i for i in g.initializer if i.name not in used]
    for i in unused:
        g.initializer.remove(i)
    prod = {o: n for n in g.node for o in n.output}
    left = [n.name for n in g.node if n.op_type in LAYOUT_SENSITIVE
            and not (prod.get(n.input[0]) is not None and prod[n.input[0]].op_type == "DequantizeLinear")]
    return model, left


def replace_bilinear(model):
    """Cambia cada Resize bilineal por operaciones que el NPU de gama media sí acepta.

    En el Snapdragon 6 Gen 4 el NPU rechaza el Resize bilineal (tanto el genérico como ResizeBilinear), pero
    acepta el de vecino más próximo y las convoluciones por canal. Una ampliación ×2 bilineal ('half_pixel')
    es exactamente: vecino más próximo ×2 → rellenar 1 píxel repitiendo el borde → filtro 3×3 [¼ ½ ¼]⊗[¼ ½ ¼]
    por canal. Las ampliaciones ×4…×32 (los mapas laterales de U²-Net, de un canal) se hacen como varios ×2
    seguidos: casi lo mismo que un solo paso (se comprueba el resultado final).
    @return el modelo y cuántos Resize se cambiaron.
    """
    from onnx import helper, numpy_helper, shape_inference

    g = model.graph
    shapes = {v.name: [d.dim_value for d in v.type.tensor_type.shape.dim]
              for v in list(shape_inference.infer_shapes(model).graph.value_info) + list(g.input) + list(g.output)}
    kernel1d = np.array([0.25, 0.5, 0.25], dtype=np.float32)
    kernel = np.outer(kernel1d, kernel1d)
    g.initializer.append(numpy_helper.from_array(np.array([1, 1, 2, 2], dtype=np.float32), "x2_escalas"))
    g.initializer.append(numpy_helper.from_array(np.array([0, 0, 1, 1, 0, 0, 1, 1], dtype=np.int64), "x2_relleno"))
    new_nodes = []
    changed = 0
    for n in g.node:
        attrs = {a.name: a for a in n.attribute}
        mode = attrs["mode"].s.decode() if "mode" in attrs else "nearest"
        src = shapes.get(n.input[0], [])
        dst = shapes.get(n.output[0], [])
        if n.op_type != "Resize" or mode != "linear" or len(src) != 4 or len(dst) != 4:
            new_nodes.append(n)
            continue
        factor = dst[2] // max(1, src[2])
        steps = int(round(np.log2(max(1, factor))))
        if (src[2] << steps, src[3] << steps) != (dst[2], dst[3]) or steps < 1:
            new_nodes.append(n)
            continue
        channels = src[1]
        wname = f"{n.name}_suave"
        g.initializer.append(numpy_helper.from_array(np.tile(kernel, (channels, 1, 1, 1)).reshape(channels, 1, 3, 3), wname))
        x = n.input[0]
        for k in range(steps):
            last = k == steps - 1
            up, pad = f"{n.name}_x2_{k}", f"{n.name}_borde_{k}"
            out = n.output[0] if last else f"{n.name}_paso_{k}"
            new_nodes.append(helper.make_node("Resize", [x, "", "x2_escalas"], [up], name=up, mode="nearest",
                                              coordinate_transformation_mode="asymmetric", nearest_mode="floor"))
            new_nodes.append(helper.make_node("Pad", [up, "x2_relleno"], [pad], name=pad, mode="edge"))
            new_nodes.append(helper.make_node("Conv", [pad, wname], [out], name=f"{n.name}_filtro_{k}",
                                              group=channels, kernel_shape=[3, 3], pads=[0, 0, 0, 0]))
            x = out
        changed += 1
    del g.node[:]
    g.node.extend(new_nodes)
    return model, changed


def fold_shapes(src, dst):
    """Calcula de antemano las operaciones de forma (Shape, Gather…): la entrada tiene tamaño fijo."""
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    so.optimized_model_filepath = str(dst)
    ort.InferenceSession(str(src), so, providers=["CPUExecutionProvider"])


# Proporción mínima de imágenes de prueba en las que la versión INT8 tiene que coincidir.
MIN_MATCH = 0.9


class Reader(CalibrationDataReader):
    def __init__(self, name, inputs):
        self.name = name
        self.items = iter(inputs)

    def get_next(self):
        x = next(self.items, None)
        return None if x is None else {self.name: x}


# ---------------------------------------------------------------- comprobaciones (como OutputCheck)

def iou(a, b):
    w = min(a[2], b[2]) - max(a[0], b[0])
    h = min(a[3], b[3]) - max(a[1], b[1])
    if w <= 0 or h <= 0:
        return 0.0
    inter = w * h
    return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)


def yolo_rows(out, conf=0.25, iou_max=0.7, max_det=300):
    """Como YoloPost en la app: un YOLO sin NMS ([1, 4 + clases, N]: cx, cy, w, h y la puntuación de cada
    clase) pasa a filas x1, y1, x2, y2, puntuación, clase; uno con NMS ([1, n, 6]) se deja igual."""
    if out.ndim != 3 or not (5 <= out.shape[1] < out.shape[2] and out.shape[2] > max_det):
        return out
    p = out[0]
    cls = p[4:].argmax(0)
    best = p[4:].max(0)
    keep = np.where(best >= conf)[0]
    keep = keep[np.argsort(-best[keep], kind="stable")]
    rows = []
    for i in keep:
        cx, cy, w, h = p[:4, i]
        b = [cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, float(best[i]), float(cls[i])]
        if not any(int(k[5]) == int(b[5]) and iou(k, b) > iou_max for k in rows):
            rows.append(b)
        if len(rows) >= max_det:
            break
    return np.array(rows, dtype=np.float32).reshape(1, -1, 6)


def yunet_rows(out, size=640, conf=0.5, iou_max=0.3):
    """Como YuNetDetector en la app: [1, N, 16] (cls, obj, caja, 5 puntos) → filas x1, y1, x2, y2, puntuación, 0."""
    p = out[0]
    rows, start = [], 0
    for stride in (8, 16, 32):
        cols = size // stride
        n = cols * cols
        q = p[start:start + n]
        score = np.sqrt(np.clip(q[:, 0], 0, 1) * np.clip(q[:, 1], 0, 1))
        for i in np.where(score >= conf)[0]:
            r, c = divmod(int(i), cols)
            cx, cy = (c + q[i, 2]) * stride, (r + q[i, 3]) * stride
            w, h = np.exp(q[i, 4]) * stride, np.exp(q[i, 5]) * stride
            rows.append([cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, float(score[i]), 0.0])
        start += n
    rows.sort(key=lambda r: -r[4])
    kept = []
    for r in rows:
        if all(iou(k, r) <= iou_max for k in kept):
            kept.append(r)
    return np.array(kept, dtype=np.float32).reshape(1, -1, 6)


def same_detections(ref, out, min_score=0.3):
    ref, out = yolo_rows(ref), yolo_rows(out)
    r = [x for x in ref[0] if x[4] >= min_score]
    o = out[0]
    strong = sum(1 for x in o if x[4] >= min_score + 0.15)
    if not r:
        return None  # imagen sin detecciones: no cuenta
    matched = sum(1 for x in r if any(int(y[5]) == int(x[5]) and abs(y[4] - x[4]) < 0.15 and iou(x, y) >= 0.6 for y in o))
    return matched >= 0.8 * len(r) and strong <= len(r) + 2


def cosine(a, b):
    a = a.ravel()
    b = b.ravel()
    return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))


def check(key, ref_sess, q_sess, inputs):
    name = ref_sess.get_inputs()[0].name
    oks, total, detail = 0, 0, []
    for x in inputs:
        r = ref_sess.run(None, {name: x})[0]
        q = q_sess.run(None, {name: x})[0]
        if key == "yunet":
            res = same_detections(yunet_rows(r), yunet_rows(q), 0.6)
            if res is None:
                continue
        elif key.startswith("yolo"):
            res = same_detections(r, q)
            if res is None:
                continue
        elif key == "u2netp":
            if r.max() < 0.2:
                continue  # nada destaca: como en la app, no sirve para comparar (OutputCheck.hasSalience)
            c = cosine(r, q)
            detail.append(c)
            res = c >= 0.95
        else:  # nima
            m = lambda p: float((p.ravel() * np.arange(1, 11)).sum())
            d = abs(m(r) - m(q))
            detail.append(d)
            res = d <= 0.15
        total += 1
        oks += int(res)
    return oks, total, detail


def main():
    if len(sys.argv) > 2 and sys.argv[1] == "--ajustar":
        # Ajusta para el NPU modelos de 8 bits ya generados (sin volver a cuantizar).
        for f in sys.argv[2:]:
            model, left = npu_friendly(onnx.load(f))
            onnx.save(model, f)
            print(f"{Path(f).name}: ajustado" + (f"; siguen en coma flotante: {left}" if left else ""))
        return
    only = [a[7:] for a in sys.argv if a.startswith("--solo=")]
    sys.argv = [a for a in sys.argv if not a.startswith("--solo=")]
    assets = Path(sys.argv[1])
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    images = load_images(sys.argv[3:])
    calib_imgs = augment(images[::2] + images[1::4])
    test_imgs = augment(images[1::2])
    for key, (file, prep) in MODELS.items():
        src = assets / file
        if not src.exists() or (only and key not in only[0].split(",")):
            continue
        pre = out / f"{key}_pre.onnx"
        up = out / f"{key}_op.onnx"
        model = onnx.load(str(src))
        del model.metadata_props[:]  # qnn_preprocess_model falla con algunos metadatos
        if next(o.version for o in model.opset_import if o.domain in ("", "ai.onnx")) < 13:
            # La cuantización por canal (DequantizeLinear con eje) necesita opset 13 o superior.
            model = onnx.version_converter.convert_version(model, 13)
        onnx.save(model, str(up))
        fold_shapes(up, pre)
        shutil.move(pre, up)
        ref = ort.InferenceSession(str(src), providers=["CPUExecutionProvider"])
        name = ref.get_inputs()[0].name
        calib = [prep(im) for im in calib_imgs]
        model, moved = remove_constant_channels(onnx.load(str(up)), name, calib)
        # Después de lo anterior (el filtro nuevo cortaría la cadena que detecta los canales constantes).
        model, resized = replace_bilinear(model)
        onnx.save(model, str(up))
        if not qnn_preprocess_model(str(up), str(pre)):
            shutil.copy(up, pre)
        up.unlink(missing_ok=True)
        eq = ort.InferenceSession(str(pre), providers=["CPUExecutionProvider"])
        same = min(cosine(ref.run(None, {name: x})[0], eq.run(None, {name: x})[0]) for x in calib[:4])
        print(f"{key}: {moved} canales constantes pasados al sesgo, {resized} Resize bilineales cambiados "
              f"(coseno en coma flotante {same:.5f})", flush=True)
        tail = float_tail(onnx.load(str(pre)))
        cfg = get_qnn_qdq_config(
            str(pre), Reader(name, calib),
            activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8,
            per_channel=True, weight_symmetric=True, op_types_to_quantize=NET_OPS, nodes_to_exclude=tail,
        )
        dst = out / f"{Path(file).stem}_int8.onnx"
        quantize(str(pre), str(dst), cfg)
        model, left = npu_friendly(onnx.load(str(dst)))
        onnx.save(model, str(dst))
        if left:
            print(f"{key}: AVISO, quedan en coma flotante operaciones que el NPU podría rechazar: {left}", flush=True)
        q = ort.InferenceSession(str(dst), providers=["CPUExecutionProvider"])
        oks, total, detail = check(key, ref, q, [prep(im) for im in test_imgs])
        extra = f" (mín {min(detail):.3f}, máx {max(detail):.3f})" if detail else ""
        good = total > 0 and oks >= MIN_MATCH * total
        print(f"{key}: {dst.stat().st_size / 1e6:.1f} MB · coincide en {oks}/{total} imágenes de prueba{extra}"
              + ("" if good else " → DESCARTADO"), flush=True)
        if not good:
            dst.unlink()
        pre.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
