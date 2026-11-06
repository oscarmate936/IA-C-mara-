"""Quita la selección de detecciones (NMS) de un YOLO exportado con nms=True.

El NPU del Snapdragon no ejecuta bien esa parte (operaciones de tamaño variable): en el S22+ las cajas y
las puntuaciones salían bien pero la clase salía corrupta (120 → 0, 108 → 131008). Sin ella, la salida es
la de la red, [1, 4 + clases, N] con cx, cy, w, h (píxeles de la entrada) y la puntuación de cada clase,
como YOLO26n; la selección la hace la app (YoloPost.kt). Comprobado: misma salida que el modelo con NMS
en 161/161 imágenes.

Uso: python cortar_nms.py origen.onnx destino.onnx  (busca solo las salidas de cajas y clases del Detect)
"""

import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


def cut_nms(src: str, dst: str, boxes: str = "/model/model.23/Mul_2_output_0",
            scores: str = "/model/model.23/Sigmoid_output_0") -> None:
    m = onnx.load(src)
    g = m.graph

    def const(name, arr):
        a = np.asarray(arr)
        g.initializer.append(numpy_helper.from_array(a.astype(np.int64 if a.dtype.kind == "i" else np.float32), name))

    const("nms_cut_0", [0])
    const("nms_cut_2", [2])
    const("nms_cut_4", [4])
    const("nms_cut_axis", [1])
    const("nms_cut_half", 0.5)
    # Las cajas del Detect con NMS van en esquinas (x1, y1, x2, y2): se pasan a centro y tamaño.
    g.node.extend([
        helper.make_node("Slice", [boxes, "nms_cut_0", "nms_cut_2", "nms_cut_axis"], ["nms_cut_xy1"]),
        helper.make_node("Slice", [boxes, "nms_cut_2", "nms_cut_4", "nms_cut_axis"], ["nms_cut_xy2"]),
        helper.make_node("Add", ["nms_cut_xy1", "nms_cut_xy2"], ["nms_cut_sum"]),
        helper.make_node("Mul", ["nms_cut_sum", "nms_cut_half"], ["nms_cut_center"]),
        helper.make_node("Sub", ["nms_cut_xy2", "nms_cut_xy1"], ["nms_cut_size"]),
        helper.make_node("Concat", ["nms_cut_center", "nms_cut_size", scores], ["output0_raw"], axis=1),
    ])
    del g.output[:]
    g.output.append(helper.make_tensor_value_info("output0_raw", TensorProto.FLOAT, None))
    # Fuera los nodos que ya no llevan a la salida (el NMS y la rama de máscaras).
    prod = {o: n for n in g.node for o in n.output}
    needed, stack = set(), ["output0_raw"]
    while stack:
        n = prod.get(stack.pop())
        if n is None or id(n) in needed:
            continue
        needed.add(id(n))
        stack += list(n.input)
    order = [n for n in g.node if id(n) in needed]
    del g.node[:]
    g.node.extend(order)
    used = {i for n in g.node for i in n.input}
    inits = [i for i in g.initializer if i.name in used]
    del g.initializer[:]
    g.initializer.extend(inits)
    del g.value_info[:]
    m = onnx.shape_inference.infer_shapes(m)
    onnx.checker.check_model(m)
    onnx.save(m, dst)


if __name__ == "__main__":
    cut_nms(sys.argv[1], sys.argv[2])
