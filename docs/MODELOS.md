# Modelos de IA incluidos en el APK

Todos funcionan dentro del móvil, sin internet. Se descargaron de Google (MediaPipe) y están en
`app/src/main/assets/models/`.

| Archivo | Para qué sirve | Origen | Licencia |
|---|---|---|---|
| `blaze_face_short_range.tflite` (0,2 MB) | Detectar caras en cada fotograma | MediaPipe Face Detector | Apache 2.0 |
| `efficientdet_lite0.tflite` (4,6 MB, int8) | Detectar 80 tipos de objetos (personas, animales, comida…) | MediaPipe Object Detector | Apache 2.0 |
| `efficientnet_lite0.tflite` (5,4 MB, int8) | Clasificar la imagen en 1000 categorías (playa, montaña, iglesia, pizza…) | MediaPipe Image Classifier | Apache 2.0 |

URLs de origen: `https://storage.googleapis.com/mediapipe-models/...` (`face_detector/blaze_face_short_range/float16`,
`object_detector/efficientdet_lite0/int8`, `image_classifier/efficientnet_lite0/int8`, versión `latest` a 5 nov 2026).
