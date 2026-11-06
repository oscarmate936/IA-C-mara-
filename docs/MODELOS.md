# Modelos de IA incluidos en el APK

Todos funcionan dentro del móvil, sin internet, desde `app/src/main/assets/models/` (~170 MB).
Los modelos ONNX se regeneran con `scripts/export_models.py` (vocabulario en `scripts/yoloe_vocabulary.tsv`)
y `scripts/export_ia_avanzada.py` (CLIP, NIMA, YOLO11s y pose); las versiones de 8 bits para el NPU, con
`scripts/cuantizar_npu.py`.

| Archivo | Motor | Para qué sirve | Frecuencia | Licencia |
|---|---|---|---|---|
| `blaze_face_short_range.tflite` (0,2 MB) | MediaPipe | Detectar caras | Cada fotograma | Apache 2.0 |
| `face_landmarker.task` (3,8 MB) | MediaPipe | 478 puntos de la cara: ojos abiertos/cerrados (disparo inteligente) y hacia dónde mira (espacio de mirada) | ~4/s si hay caras | Apache 2.0 |
| `pose_landmarker_lite.task` (5,8 MB) | MediaPipe | 33 puntos del cuerpo: no cortar por rodillas, tobillos, codos o manos; ojos exactamente en la línea de la guía; brazos pegados al cuerpo | ~4/s si hay personas | Apache 2.0 |
| `clip_image.onnx` (45 MB, int8) + `clip_scenes.bin/.tsv` | ONNX Runtime | **CLIP pequeño (TinyCLIP ViT-40M/32, LAION-400M).** Compara la imagen con descripciones de 14 tipos de escena (atardecer, playa, comida, arquitectura…) para reconocer la escena; también confirma si hay animales. Sustituye a EfficientNet (que reconocía objetos, no escenas) | ~2/s | MIT |
| `nima_aesthetic.onnx` (12,9 MB) | ONNX Runtime | **NIMA estética** (MobileNet, pesos de idealo): nota 1–10 aprendida de ~250.000 fotos puntuadas por personas. Suma en la puntuación del encuadre y desempata la mejor foto de la ráfaga | ~1/s | Apache 2.0 |
| `yolo11n.onnx` (10,7 MB) | ONNX Runtime | **Opción 2.** Detector rápido de 80 objetos (personas, animales, comida…), entrada 512 px, NMS incluido | ~5/s | AGPL-3.0 (uso personal) |
| `yolo11s.onnx` (38 MB) | ONNX Runtime | Detector más preciso (47,0 frente a 39,5 de precisión COCO). Solo se usa si el móvil lo ejecuta en el NPU o la GPU en ≤ 40 ms y da los mismos resultados que en el procesador | ~5/s | AGPL-3.0 (uso personal) |
| `yoloe_vocab.onnx` (39 MB) + `yoloe_labels.tsv` | ONNX Runtime | **Opción 3.** Detector de vocabulario abierto (YOLOE-11s) con ~120 cosas habituales en fotos (estructura metálica, pila de troncos, volcán, farola…). Da nombre a lo que YOLO11 no conoce, anula sus confusiones y nombra lo que distrae en los bordes | ~1/s | AGPL-3.0 (uso personal) |
| `u2netp.onnx` (4,6 MB) | ONNX Runtime | **Opción 1.** Saliencia (U²-Net pequeño): encuentra "lo importante" de la imagen aunque no tenga nombre y mide lo que distrae en los bordes | ~1–2/s | Apache 2.0 |
| `u2netp_int8.onnx` (3,4 MB), `yolo11n_int8.onnx` (3,3 MB), `yolo11s_int8.onnx` (10,2 MB) | ONNX Runtime (QNN) | Los mismos modelos en enteros de 8 bits, **solo para el NPU** de los Snapdragon que no admiten coma flotante (el Snapdragon 6 Gen 4 del Honor). Solo se prueban si el NPU normal falla | — | Las del original |

Notas:

- **Aceleración (NPU y GPU del Snapdragon).** Los modelos ONNX empiezan en el procesador. En segundo plano, la app
  prueba cada uno en el chip de IA (Hexagon, con Qualcomm AI Engine Direct / QNN, en media precisión) y en la gráfica
  Adreno, con una imagen real: solo se queda con un acelerador si da el mismo resultado que el procesador (mismas
  detecciones, mismo mapa o vector) y es al menos un 15 % más rápido. El resultado se recuerda en cada móvil y el grafo
  compilado para el NPU se guarda para no recompilarlo. Los modelos de MediaPipe (ojos y cuerpo) intentan la GPU.
  Si el controlador de un chip cierra la app durante la prueba, al volver a abrirla ese acelerador queda descartado;
  si se cierra de golpe dos veces seguidas con la cámara abierta, se desactiva la aceleración. Para que la comparación
  sea justa, mientras se mide la velocidad el visor pausa los demás modelos; los detectores solo se comparan con una
  imagen en la que detecten algo (si no, un acelerador roto "coincidiría"). Se prueban primero los modelos más lentos.
  El panel 📊 muestra dónde corre cada modelo (NPU, GPU o CPU), cuánto tarda y qué se está probando; el informe de
  Diagnóstico (ⓘ → Copiar) incluye la sección «ACELERACIÓN» con los tiempos medidos y el motivo de cada descarte.
- **NPU de 8 bits (Honor y otros gama media).** El NPU del Snapdragon 6 Gen 4 (SM6650) no admite coma flotante
  («The SocModel doesn't support FP16»): los modelos normales fallan al compilarse para él. Para esos chips van copias
  cuantizadas a 8 bits (QDQ, pesos por canal, calibradas con fotos reales) de U²-Net, YOLO11n y YOLO11s. La app
  las prueba **solo si el NPU normal falla** y con la misma comprobación que el resto (mismo resultado que el
  procesador con una imagen real; la saliencia, solo con imágenes en las que algo destaca). En el diagnóstico salen
  como «NPU 8 bits». Para que U²-Net funcione en 8 bits, `cuantizar_npu.py` pasa al sesgo dos canales que valen ~226
  en toda la imagen (con una corrección exacta en el borde) y deja en coma flotante la decodificación de cajas y el NMS
  de YOLO. Además, en esos modelos no puede quedar en coma flotante ninguna operación "sensible a la disposición"
  (Conv, Resize, Pool…): ONNX Runtime deja que el NPU las acepte en una primera pasada y solo las valida de verdad
  después; si el chip las rechaza entonces, la sesión entera falla (el error «… inserted using the NHWC format … but
  was not selected by that EP» del Honor). Por eso la convolución final de YOLO (DFL) se convierte en Mul + ReduceSum
  y los Resize de U²-Net usan `half_pixel` (en el NPU, ResizeBilinear) en vez de `pytorch_half_pixel` (Resize
  genérico): mismo resultado. Comprobación con imágenes que no se usaron para calibrar: U²-Net coincide en 38/38 (coseno ≥ 0,979),
  YOLO11n en 20/20 y YOLO11s en 21/21. NIMA y YOLOE no pasan (el error se acumula capa a capa) y se quedan en el
  procesador; CLIP tampoco tiene versión de 8 bits para el NPU.
- Las librerías de Qualcomm (`com.qualcomm.qti:qnn-runtime`) solo incluyen las de los chips Hexagon V69 (Snapdragon
  8 Gen 1), V73, V75 y V79; añaden ~53 MB al APK.

- **YOLOE** es el sucesor de YOLO-World del mismo equipo (Ultralytics): detecta cualquier cosa a partir de una lista de
  nombres. Se eligió porque el codificador de texto de YOLO-World no se pudo descargar en el entorno de compilación.
  El vocabulario se "hornea" en el modelo al exportarlo: para añadir cosas nuevas se edita la lista y se reexporta.
- EfficientDet-Lite0/Lite2 se retiraron: YOLO11n es más preciso y además hay dos comprobaciones extra
  (vocabulario abierto y CLIP) contra confusiones como la "jirafa" de hierro.
- EfficientNet-Lite0 (clasificador ImageNet) se retiró: CLIP reconoce escenas, no solo objetos.
- Cada modelo se espacia solo según lo que tarda en cada móvil (`TaskClock`) y si el móvil se calienta;
  en cada fotograma se ejecuta como mucho un modelo pesado. El panel 📊 muestra los milisegundos de cada uno.

Orígenes: modelos de MediaPipe en `storage.googleapis.com/mediapipe-models`, pesos de YOLO11n, YOLO11s y YOLOE-11s
de `github.com/ultralytics/assets` (v8.3.0), U²-Net pequeño de `github.com/danielgatis/rembg` (v0.0.0), TinyCLIP de
`github.com/wkcn/TinyCLIP-model-zoo` (Microsoft, MIT) y NIMA de `github.com/idealo/image-quality-assessment`
(Apache 2.0; la conversión a ONNX se comprobó contra el modelo original de Keras: misma salida).
