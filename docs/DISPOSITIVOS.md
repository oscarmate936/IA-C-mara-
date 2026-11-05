# Perfil de los móviles de prueba

Datos obtenidos con la pantalla de diagnóstico de IA Cámara (v0.1.1, 5 de noviembre de 2026).
Sirven para que el Director IA sepa qué puede pedir a cada cámara (`DeviceCaps`, §8.3 del plan).

## Samsung Galaxy S22+ — SM-S906U

- **Chip:** Qualcomm SM8450 (Snapdragon 8 Gen 1) · **RAM:** 8 GB · **Android 16**
- **Extensiones CameraX:** Auto ✓ · Bokeh ✓ · Retoque facial ✓ · HDR ✗ · Noche ✗ (trasera y frontal)
- **Ultra HDR:** no

| ID | Tipo | Nivel | Resolución | Focal (equiv.) | Apertura | OIS | ISO | Exposición máx. | Enfoque mín. |
|---|---|---|---|---|---|---|---|---|---|
| 0 | Trasera **lógica** (agrupa 2, 5, 6) · zoom 0,6×–10× | LEVEL_3 | 12,5 MP | 23 mm | f/1.8 | Sí | 50–3200 | 1/6 s | 7 cm |
| 2 | Ultra gran angular (también accesible sola) | LIMITED | 12,2 MP | 13 mm | f/2.2 | No | 50–3200 | 1/5 s | Foco fijo |
| 5 | Principal (física) | LEVEL_3 | 12,5 MP | 23 mm | f/1.8 | Sí | 50–3200 | 1/6 s | 7 cm |
| 6 | Tele ≈3× (física) | LIMITED | 10 MP | 66 mm | f/2.4 | Sí | 50–3200 | 1/5 s | 40 cm |
| 1 | Frontal | FULL | 10 MP | 26 mm | f/2.2 | No | 50–3200 | 1/5 s | 10 cm |
| 3 | Frontal (modo recortado) | FULL | 7,1 MP | 30 mm | f/2.2 | No | 50–3200 | 1/5 s | 10 cm |

Todas: control manual del sensor, RAW, ráfaga. Sensor principal 8,16 × 6,12 mm (≈1/1,56").
Sin modo de alta resolución (los 50 MP no se ofrecen a otras apps).

**Consecuencias:** Noche y HDR propios de la app; Retrato con el bokeh de Samsung o el propio; tele 3× para
retratos (a más de 40 cm; más cerca Samsung cambia a la principal); exposiciones de noche apiladas en fotos
de ≤1/6 s; vigilar temperatura (Snapdragon 8 Gen 1).

## Honor Magic 8 Lite — MTN-NX3

- **Chip:** Qualcomm SM6650 (Snapdragon 6 Gen 4) · **RAM:** 8 GB · **Android 16**
- **Extensiones CameraX:** ninguna
- **Ultra HDR:** no

| ID | Tipo | Nivel | Resolución | Focal (equiv.) | Apertura | OIS declarado | ISO | Exposición máx. | Enfoque mín. |
|---|---|---|---|---|---|---|---|---|---|
| 0 | Trasera principal · zoom digital 1×–8× | LEVEL_3 | 12 MP | 24 mm | f/1.8 | No | 100–6400 | 1/2 s | 9 cm |
| 1 | Frontal | LEVEL_3 | 4 MP | 24 mm | f/2.5 | No | 100–1600 | 1/2 s | Foco fijo |

Ambas: control manual del sensor, RAW, ráfaga, reprocesado YUV. Sensor principal 7,68 × 5,76 mm (≈1/1,67").
El ultra gran angular de 5 MP **no es accesible** para otras apps. Sin modo de alta resolución (108 MP).

**Consecuencias:** el pipeline computacional propio es la única vía para HDR y Noche; los 12 MP agrupados
(píxel efectivo ≈1,9 µm) son muy buenos para apilar fotos de noche de hasta 1/2 s; el zoom es solo digital,
así que se usará super-resolución multiframe y el coach recomendará acercarse; nada de 0,6×.
