# Modelos de IA incluidos en el APK

Todos funcionan dentro del móvil, sin internet, desde `app/src/main/assets/models/` (~170 MB).
Los modelos ONNX se regeneran con `scripts/export_models.py` (vocabulario en `scripts/yoloe_vocabulary.tsv`)
(YOLO26n, YuNet, YOLOE, U²-Net, ojos) y `scripts/export_ia_avanzada.py` (CLIP, NIMA, YOLO11s y pose); las
versiones de 8 bits para el NPU, con `scripts/cuantizar_npu.py`.

| Archivo | Motor | Para qué sirve | Frecuencia | Licencia |
|---|---|---|---|---|
| `yunet.onnx` (0,2 MB) | ONNX Runtime | **Caras (YuNet, OpenCV Zoo)**, entrada 640 px: ve caras de ~10 píxeles (las de una foto de grupo), de perfil y con poca luz. Sustituye a BlazeFace (que solo veía caras cercanas: 0 caras en las fotos de grupo frente a 13–14 de YuNet). Las cajas salen con el convenio de BlazeFace para que sigan valiendo todos los umbrales | Casi cada fotograma | MIT |
| `face_landmarker.task` (3,8 MB) | MediaPipe | 478 puntos de la cara: ojos abiertos/cerrados (disparo inteligente) y hacia dónde mira (espacio de mirada) | ~4/s si hay caras | Apache 2.0 |
| `pose_landmarker_full.task` (9,4 MB) | MediaPipe | 33 puntos del cuerpo (versión *full*, más precisa que la *lite* con poses difíciles): no cortar por rodillas, tobillos, codos o manos; ojos exactamente en la línea de la guía; brazos pegados al cuerpo | ~4/s si hay personas | Apache 2.0 |
| `clip_image.onnx` (45 MB, int8) + `clip_scenes.bin/.tsv` | ONNX Runtime | **CLIP pequeño (TinyCLIP ViT-40M/32, LAION-400M).** Compara la imagen con descripciones de 14 tipos de escena (atardecer, playa, comida, arquitectura…) para reconocer la escena; también confirma si hay animales. Sustituye a EfficientNet (que reconocía objetos, no escenas) | ~2/s | MIT |
| `nima_aesthetic.onnx` (12,9 MB) | ONNX Runtime | **NIMA estética** (MobileNet, pesos de idealo): nota 1–10 aprendida de ~250.000 fotos puntuadas por personas. Suma en la puntuación del encuadre y desempata la mejor foto de la ráfaga | ~1/s | Apache 2.0 |
| `yolo26n.onnx` (9,9 MB) + `coco_labels.txt` | ONNX Runtime | **Opción 2.** Detector rápido de 80 objetos (personas, animales, comida…), entrada 512 px. YOLO26n (2026, sucesor de YOLO11n: 40,9 frente a 39,5 de precisión COCO). Se exporta sin NMS (la red sola, que va mejor en el NPU) y la selección de detecciones la hace la app (`YoloPost.kt`, idéntica a la de Ultralytics: comprobado con 69 detecciones en 15 fotos). Sus puntuaciones se pasan a la escala de YOLO11 (personas +0,04, otros objetos −0,05; medido con 346 personas emparejadas) para que valgan todos los umbrales | ~5/s | AGPL-3.0 (uso personal) |
| `yolo11s.onnx` (38 MB) | ONNX Runtime | Detector más preciso (47,0 frente a 39,5 de precisión COCO), sin la selección de detecciones dentro (la hace la app, como con YOLO26n). Solo se usa si el móvil lo ejecuta en el NPU o la GPU en ≤ 40 ms y da los mismos resultados que en el procesador | ~5/s | AGPL-3.0 (uso personal) |
| `yoloe_vocab.onnx` (39 MB) + `yoloe_labels.tsv` | ONNX Runtime | **Opción 3.** Detector de vocabulario abierto (YOLOE-11s), sin la selección de detecciones dentro (la hace la app), con ~120 cosas habituales en fotos (estructura metálica, pila de troncos, volcán, farola…). Da nombre a lo que el detector no conoce, anula sus confusiones y nombra lo que distrae en los bordes | ~1/s | AGPL-3.0 (uso personal) |
| `u2netp.onnx` (4,6 MB) | ONNX Runtime | **Opción 1.** Saliencia (U²-Net pequeño): encuentra "lo importante" de la imagen aunque no tenga nombre y mide lo que distrae en los bordes | ~1–2/s | Apache 2.0 |
| `u2netp_int8.onnx` (3,5 MB), `yolo26n_int8.onnx` (3,1 MB), `yolo11s_int8.onnx` (10,2 MB), `yunet_int8.onnx` (0,2 MB) | ONNX Runtime (QNN) | Los mismos modelos en enteros de 8 bits, **solo para el NPU** de los Snapdragon que no admiten coma flotante (el Snapdragon 6 Gen 4 del Honor). Solo se prueban si el NPU normal falla | — | Las del original |

Notas:

- **Aceleración (NPU y GPU del Snapdragon).** Los modelos ONNX empiezan en el procesador. En segundo plano, la app
  prueba cada uno en el chip de IA (Hexagon, con Qualcomm AI Engine Direct / QNN, en media precisión) y en la gráfica
  Adreno, con una imagen real: solo se queda con un acelerador si da el mismo resultado que el procesador (mismas
  detecciones, mismo mapa o vector) y es al menos un 15 % más rápido. El resultado se recuerda en cada móvil y el grafo
  compilado para el NPU se guarda para no recompilarlo. Los modelos de MediaPipe (ojos y cuerpo) intentan la GPU.
  Si el controlador de un chip cierra la app durante la prueba, al volver a abrirla ese acelerador queda descartado;
  si se cierra de golpe dos veces seguidas con la cámara abierta, se desactiva la aceleración. Para que la comparación
  sea justa, mientras se mide la velocidad el visor pausa los demás modelos; los detectores solo se comparan con
  imágenes en las que detecten algo claro (puntuación ≥ 0,45: las detecciones dudosas aparecen y desaparecen con
  cualquier diferencia mínima de precisión, sobre todo con niebla o de noche), y cada acelerador tiene que coincidir
  en 2 de 3 imágenes reales. La versión de 8 bits solo se prueba si el NPU normal no llega a ejecutarse.
  Si no aparecen imágenes útiles suficientes para comparar (p. ej. ninguna cara más a la vista), no se descarta el
  acelerador: se vuelve a intentar más tarde. Si un acelerador no coincide, el diagnóstico dice en qué difiere (para los detectores: qué ve el procesador y qué
  ve el acelerador, clase:puntuación e IoU; para los mapas, el coseno). CLIP no se prueba en el NPU: en coma flotante
  de 16 bits algún valor intermedio se desborda y sale NaN (comprobado simulándolo); en el procesador tarda ~25–40 ms.
  YOLO26n, YOLOE y YuNet simulados en 16 bits sí coinciden con el procesador (8/8, 7/7 y 86/86). Se prueban primero los
  modelos más lentos.
  El panel 📊 muestra dónde corre cada modelo (NPU, GPU o CPU), cuánto tarda y qué se está probando; el informe de
  Diagnóstico (ⓘ → Copiar) incluye la sección «ACELERACIÓN» con los tiempos medidos y el motivo de cada descarte.
- **NPU de 8 bits (Honor y otros gama media).** El NPU del Snapdragon 6 Gen 4 (SM6650) no admite coma flotante
  («The SocModel doesn't support FP16»): los modelos normales fallan al compilarse para él. Para esos chips van copias
  cuantizadas a 8 bits (QDQ, pesos por canal, calibradas con fotos reales) de U²-Net, YOLO26n, YOLO11s y YuNet. La app
  las prueba **solo si el NPU normal falla** y con la misma comprobación que el resto (mismo resultado que el
  procesador con una imagen real; la saliencia, solo con imágenes en las que algo destaca). En el diagnóstico salen
  como «NPU 8 bits». Para que U²-Net funcione en 8 bits, `cuantizar_npu.py` pasa al sesgo dos canales que valen ~226
  en toda la imagen (con una corrección exacta en el borde) y deja en coma flotante la decodificación de cajas y el NMS
  de YOLO. Además, en esos modelos no puede quedar en coma flotante ninguna operación "sensible a la disposición"
  (Conv, Resize, Pool…): ONNX Runtime deja que el NPU las acepte en una primera pasada y solo las valida de verdad
  después; si el chip las rechaza entonces, la sesión entera falla (el error «… inserted using the NHWC format … but
  was not selected by that EP» del Honor). Por eso la convolución final de YOLO (DFL) se convierte en Mul + ReduceSum
  y, como el NPU del Snapdragon 6 Gen 4 rechaza cualquier Resize bilineal (se vio con el diagnóstico del Honor), cada
  ampliación ×2 bilineal de U²-Net se hace como vecino más próximo ×2 + relleno repitiendo el borde + filtro 3×3
  [¼ ½ ¼] por canal (exactamente lo mismo); las ×4…×32 de los mapas laterales, como varios ×2 seguidos (el mapa
  final no cambia: coseno 1,00000 en coma flotante). Comprobación con imágenes que no se usaron para calibrar: U²-Net coincide en 38/38 (coseno ≥ 0,964), YOLO26n en 28/28 (calibrado y comprobado también con imágenes
  de noche), YOLO11s en 21/21 y YuNet en 54/55. En el Honor, la saliencia pasa de 824 ms en el procesador a **47 ms en el NPU de 8 bits**.
  Con muy poca luz los detectores de 8 bits pierden algunas detecciones flojas (con tan poca señal, 8 bits dan pocos
  niveles): por eso la app compara la versión de 8 bits con el procesador en 3 imágenes y exige que coincida en 2; si
  no, el detector sigue en el procesador. NIMA y YOLOE no pasan (el error se acumula capa a capa) y se quedan en el
  procesador o la GPU; CLIP tampoco tiene versión de 8 bits para el NPU.
- Las librerías de Qualcomm (`com.qualcomm.qti:qnn-runtime`) solo incluyen las de los chips Hexagon V69 (Snapdragon
  8 Gen 1), V73, V75 y V79; añaden ~53 MB al APK.

- **YOLOE** es el sucesor de YOLO-World del mismo equipo (Ultralytics): detecta cualquier cosa a partir de una lista de
  nombres. Se eligió porque el codificador de texto de YOLO-World no se pudo descargar en el entorno de compilación.
  El vocabulario se "hornea" en el modelo al exportarlo: para añadir cosas nuevas se edita la lista y se reexporta.
- **Detectores sin la selección dentro (NMS).** Exportados con la selección incluida, en el NPU del S22+ las cajas
  y las puntuaciones salían bien, pero la clase salía corrupta (120 → 0, 108 → 131008: esa parte usa operaciones de
  tamaño variable que el NPU ejecuta mal). Ahora YOLO26n, YOLO11s y YOLOE dan la salida de la red y la app hace la
  selección (`YoloPost.kt`); `scripts/cortar_nms.py` corta los modelos ya exportados. Mismo resultado que con la
  selección incluida: 161/161 imágenes (YOLO11s, 975 detecciones; YOLOE, 375). Simulados en 16 bits: 8/8 y 7/7.
  YOLO11s de 8 bits (Honor): 57/58. YOLOE de 8 bits sigue sin pasar (22/50).
- **Calor.** Con el móvil caliente la IA se reduce sola: moderado → todo más espaciado; grave → además fuera YOLOE,
  el cuerpo y la estética si van en el procesador; crítico → también la escena y la saliencia si van en el
  procesador (lo que va en el NPU o la GPU apenas calienta y sigue). Un modelo apagado hace una pasada cuando su
  prueba de acelerador necesita una imagen, para poder pasar al NPU aunque haga calor. El visor lo avisa («Móvil muy caliente: IA
  reducida»). Con calor se sigue probando el NPU (pasar un modelo al NPU es justo lo que enfría el móvil) pero no la
  GPU (se deja para el próximo arranque); en temperatura crítica las pruebas esperan.
- **Lo validado se conserva entre versiones.** Al instalar una versión nueva, lo que ya funcionaba en el NPU o la
  GPU se sigue usando sin volver a probarlo; solo se reintenta lo que falló. Un modelo se vuelve a probar entero si
  cambia su archivo (firma por tamaño) y todos si cambian las librerías de IA (ONNX Runtime/QNN, MediaPipe) o la
  forma de probar. Un acelerador que cerró la app al probarlo no se reintenta al actualizar (se cerraría otra vez).
  En Diagnóstico, «Volver a probar los aceleradores» lo borra todo.
- **Comprobación de respaldo.** Si en 25 s no aparece ninguna imagen útil (un detector en una calle vacía, las caras
  sin nadie a la vista), se compara la salida entera de la red: para los YOLO, las cajas y las puntuaciones por
  separado (si no, las cajas pesarían tanto que un acelerador que estropeara solo las puntuaciones pasaría); para
  YuNet, las puntuaciones aparte de cajas y puntos. Similitud mínima 0,98; simulado en 16 bits: 0,9994 (YOLO26n) y
  1,0000 (YuNet) en 40 imágenes.
- **Orden y duración de las pruebas.** Primero lo que más procesador gasta por segundo (YOLO26n, ~4 veces por
  segundo, y las caras), luego la saliencia, YOLOE, la estética y CLIP. Si el NPU ya tarda menos del 30 % que el
  procesador, la GPU no se prueba (en el S22+ nunca ganó). Un modelo que no se está usando (p. ej. YOLOE con calor)
  no bloquea la cola: espera como mucho 30 s una imagen y deja pasar a los demás.
- **Caras: comprobación cruzada.** Una cara de YuNet cuenta si es clara (≥ 0,75 de día, ≥ 0,8 con poca luz), si la
  confirman los 478 puntos de la cara o si está en la cabeza de una persona clara del detector de objetos (≥ 0,55 de
  día, ≥ 0,65 de noche: en un grupo, las caras pequeñas no las ven los 478 puntos, pero cada una está en su persona).
  La "cara" de un perro (YuNet le da hasta 0,74) no cuenta si está dentro de un animal y de ninguna persona.
  Umbrales medidos con las fotos del usuario, de día y oscurecidas con ruido como de noche.
- **MobileCLIP2** (sustituto previsto de TinyCLIP) no se incluyó: sus pesos solo se publican en Hugging Face y en el
  servidor de Apple, y la red del entorno de compilación no llega a ellos. TinyCLIP sigue haciendo ese trabajo.
  Queda para la fase de pulido (hace falta dar permiso desde el ordenador), igual que YOLO26s.
- **YOLO26s** (sustituto previsto de YOLO11s) tampoco: exportarlo exige abrir el archivo de pesos de PyTorch
  descargado, y el entorno de compilación no lo permite por seguridad. YOLO11s sigue siendo el detector grande.
- EfficientDet-Lite0/Lite2 se retiraron: YOLO26n es más preciso y además hay dos comprobaciones extra
  (vocabulario abierto y CLIP) contra confusiones como la "jirafa" de hierro.
- EfficientNet-Lite0 (clasificador ImageNet) se retiró: CLIP reconoce escenas, no solo objetos.
- Cada modelo se espacia solo según lo que tarda en cada móvil (`TaskClock`) y si el móvil se calienta;
  en cada fotograma se ejecuta como mucho un modelo pesado. El panel 📊 muestra los milisegundos de cada uno.

Orígenes: modelos de MediaPipe en `storage.googleapis.com/mediapipe-models`, pesos de YOLO11s y YOLOE-11s
de `github.com/ultralytics/assets` (v8.3.0) y de YOLO26n (v8.4.0), YuNet (2023mar) de `github.com/opencv/opencv_zoo`
(su decodificación en la app da lo mismo que `cv2.FaceDetectorYN`), U²-Net pequeño de `github.com/danielgatis/rembg` (v0.0.0), TinyCLIP de
`github.com/wkcn/TinyCLIP-model-zoo` (Microsoft, MIT) y NIMA de `github.com/idealo/image-quality-assessment`
(Apache 2.0; la conversión a ONNX se comprobó contra el modelo original de Keras: misma salida).
