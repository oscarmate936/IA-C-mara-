# Modelos de IA incluidos en el APK

Todos funcionan dentro del móvil, sin internet, desde `app/src/main/assets/models/` (~62 MB).
Los modelos ONNX se regeneran con `scripts/export_models.py` (vocabulario en `scripts/yoloe_vocabulary.tsv`).

| Archivo | Motor | Para qué sirve | Frecuencia | Licencia |
|---|---|---|---|---|
| `blaze_face_short_range.tflite` (0,2 MB) | MediaPipe | Detectar caras | Cada fotograma | Apache 2.0 |
| `face_landmarker.task` (3,8 MB) | MediaPipe | 478 puntos de la cara: ojos abiertos/cerrados (disparo inteligente) y hacia dónde mira (espacio de mirada) | ~4/s si hay caras | Apache 2.0 |
| `efficientnet_lite0.tflite` (5,4 MB) | MediaPipe | Clasificar la imagen en 1000 categorías (playa, montaña, iglesia, pizza…) para reconocer la escena | ~2/s | Apache 2.0 |
| `yolo11n.onnx` (10,7 MB) | ONNX Runtime | **Opción 2.** Detector rápido de 80 objetos (personas, animales, comida…), entrada 512 px, NMS incluido | ~5/s | AGPL-3.0 (uso personal) |
| `yoloe_vocab.onnx` (39 MB) + `yoloe_labels.tsv` | ONNX Runtime | **Opción 3.** Detector de vocabulario abierto (YOLOE-11s) con ~120 cosas habituales en fotos (estructura metálica, pila de troncos, volcán, farola…). Da nombre a lo que YOLO11 no conoce, anula sus confusiones y nombra lo que distrae en los bordes | ~1/s | AGPL-3.0 (uso personal) |
| `u2netp.onnx` (4,6 MB) | ONNX Runtime | **Opción 1.** Saliencia (U²-Net pequeño): encuentra "lo importante" de la imagen aunque no tenga nombre y mide lo que distrae en los bordes | ~1–2/s | Apache 2.0 |

Notas:

- **YOLOE** es el sucesor de YOLO-World del mismo equipo (Ultralytics): detecta cualquier cosa a partir de una lista de
  nombres. Se eligió porque el codificador de texto de YOLO-World no se pudo descargar en el entorno de compilación.
  El vocabulario se "hornea" en el modelo al exportarlo: para añadir cosas nuevas se edita la lista y se reexporta.
- EfficientDet-Lite0/Lite2 se retiraron: YOLO11n es más preciso y además hay dos comprobaciones extra
  (vocabulario abierto y clasificador) contra confusiones como la "jirafa" de hierro.
- Cada modelo se espacia solo según lo que tarda en cada móvil (`TaskClock`) y si el móvil se calienta;
  en cada fotograma se ejecuta como mucho un modelo pesado. El panel 📊 muestra los milisegundos de cada uno.

Orígenes: modelos de MediaPipe en `storage.googleapis.com/mediapipe-models`, pesos de YOLO11n y YOLOE-11s de
`github.com/ultralytics/assets` (v8.3.0), U²-Net pequeño de `github.com/danielgatis/rembg` (v0.0.0).
