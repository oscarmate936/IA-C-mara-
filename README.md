# IA Cámara

Cámara profesional para Android construida alrededor de la IA, de uso personal.
Pensada para **Samsung Galaxy S22+** y **Honor Magic 8 Lite**.

- **Antes del disparo:** analiza la escena en tiempo real (sujeto, caras, luz, horizonte, líneas) y se autoajusta.
- **Guía de composición:** líneas guía dinámicas, flechas de movimiento, puntuación de encuadre y consejos de ángulo y luz.
- **En el disparo:** fotografía computacional (ráfaga, HDR, noche multiframe, mejor fotograma, super-resolución).
- **Después del disparo:** mejora automática y herramientas IA (luz, ruido, retrato, borrador, zoom IA, recorte).
- **Aprende contigo:** se adapta a tu estilo con cada foto.

Solo incluye funciones para tomar mejores fotos o mejorar su calidad.
Funciona 100 % dentro del móvil: sin internet, sin APIs, sin claves, sin cuentas y gratis.

📄 **Plan de creación completo:** [docs/PLAN.md](docs/PLAN.md)

## Instalar en el móvil

1. Abre en el móvil la página de versiones: **https://github.com/oscarmate936/IA-C-mara-/releases/latest**
2. Descarga **IA-Camara.apk** y ábrelo.
3. Si Android lo pide, permite "instalar apps desconocidas" para el navegador o el gestor de archivos.
4. Las versiones nuevas se instalan encima de la anterior, sin desinstalar nada.

El APK lo compila GitHub automáticamente con cada cambio (pestaña *Actions*).
La app no tiene permiso de internet: nunca se conecta a GitHub ni a ningún otro sitio.

### Firma fija (una sola vez)

Para que cada versión nueva se instale **encima** de la anterior, el APK debe firmarse siempre con la misma
clave. La clave se genera a partir de una frase secreta que solo conoces tú y que nunca está en el repositorio:

1. En GitHub, abre el repositorio → **Settings** → **Secrets and variables** → **Actions**.
2. Pulsa **New repository secret**.
3. Nombre: `CLAVE_FIRMA`. Valor: una frase larga que solo tú sepas (mínimo 12 caracteres). Guárdala bien:
   si la cambias, habrá que desinstalar la app una vez.

Mientras no exista ese secreto, el APK se firma con una clave temporal y, para actualizar,
hay que desinstalar la versión anterior antes de instalar la nueva.

## Estado

| Fase | Contenido | Estado |
|---|---|---|
| 0 — Base | Proyecto Android, compilación automática del APK, firma fija, sin permiso de internet | ✅ |
| 1 — Cámara sólida | Visor 3:4, disparo, zoom (0.6x/1x/2x/3x/10x y pellizco), toque para enfocar, flash, rejilla de tercios, nivel de horizonte y nivel de burbuja para fotos cenitales, modos AUTO y RETRATO de Samsung en el S22+, botones de volumen como disparador, diagnóstico de cámara | ✅ Diagnóstico hecho en ambos móviles ([DISPOSITIVOS.md](docs/DISPOSITIVOS.md)); pendiente la prueba de fotos |
| 2 — Percepción en vivo | IA en el móvil (MediaPipe): reconoce 16 tipos de escena, detecta caras, personas, animales, comida y objetos, mide la luz (histograma, contraluz, luces quemadas) y el temblor; enfoca y mide la luz en el sujeto sola, corrige la exposición según la escena y da un consejo (tocable para cambiar a RETRATO o al 3x) | ✅ (pendiente de probar en tus móviles) |
| 3 — Coach de composición | Guías de composición que la IA elige sola según la escena: **tercios**, **proporción áurea**, **espiral áurea**, **centro**, **simetría** (edificios, pasillos y reflejos en el agua) y **líneas guía** (caminos, vías, vallas, con su punto de fuga); el nombre de la guía aparece bajo la puntuación y al tocarlo puedes fijar otra. Puntuación del encuadre 0–100, dónde debería ir el sujeto y flecha, equilibrio visual, espacio hacia donde mira, aire sobre la cabeza, sujeto cortado, horizonte en un tercio, móvil torcido, elementos que distraen en los bordes (con su nombre); disparo inteligente que espera a que el encuadre sea bueno, el móvil esté quieto y todos tengan los ojos abiertos. Nuevos modelos: YOLO11n, YOLOE (vocabulario abierto), U²-Net (sujeto principal) y Face Landmarker (ojos) | ✅ (pendiente de probar en tus móviles) |
| 4 — Fotografía computacional | El Director IA elige la técnica al disparar (se ve en la etiqueta junto al botón): **Noche IA** (6–8 fotos alineadas y apiladas para quitar ruido sin fantasmas), **HDR IA** (3 exposiciones −2/0/+2 EV fusionadas: recupera cielo y sombras), **Mejor de 4** (con personas o movimiento se queda con la foto más nítida y con los ojos abiertos) o foto normal. Modos NOCHE y HDR forzados y modo **PRO** (ISO y velocidad manuales). Todo en el móvil, probado con fotos reales. Ultra HDR: ninguno de tus móviles lo admite. Super-resolución y RAW: pendientes, pasan a la Fase 5 | ✅ (pendiente de probar en tus móviles) |
| IA avanzada (antes de la Fase 5) | La IA usa el **NPU y la GPU del Snapdragon** cuando dan el mismo resultado que el procesador (más rápida, menos calor), con protección si el chip falla. Para los NPU que no admiten coma flotante (el del Honor) van versiones de 8 bits de la saliencia y de YOLO11. **Pose del cuerpo** (no cortar por rodillas, tobillos, codos o manos; ojos en la línea de la guía), **escenas con CLIP** (compara la imagen con descripciones de cada escena), **nota de estética NIMA** (puntuación y mejor foto de la ráfaga) y **YOLO11s** en móviles que lo mueven rápido. Panel 📊: dónde corre cada modelo | ✅ (pendiente de probar en tus móviles) |
| 5 — Lo que solo la cámara puede hacer | **Mejora automática** por escena al disparar (original + mejorada, en ⚙ Ajustes), **enderezado y perspectiva** con el sensor del disparo, **recorte según la guía**, **todos con los ojos abiertos** en fotos de grupo (cambia la cara de quien parpadeó por la de otra foto de la ráfaga), modo **SIN GENTE** (quita a quien pasa), modo **LARGA EXP.** (agua sedosa de día, estelas de luz de noche), **zoom con detalle** (con zoom digital junta 6 fotos) y **RAW (DNG)** en PRO. La IA compara GPU y procesador en los modelos de MediaPipe | ✅ (pendiente de probar en tus móviles) |

## Probar la versión actual

1. Instala el APK y concede el permiso de cámara.
2. Haz fotos con la cámara trasera y la frontal, con varios zooms y con los modos que aparezcan.
3. Pulsa el icono ⓘ (arriba a la derecha) → **Copiar** y pega el informe de diagnóstico en el chat.
   Con ese informe se ajusta la IA a lo que permite cada móvil.

## Color y fiabilidad: errores corregidos

Cada caso tiene su prueba automática (`ColorFidelityTest`, `BurstFeaturesTest`) que simula lo que entrega la cámara.

| Escenario | Qué pasaba | Ahora |
|---|---|---|
| Noche sin flash | Las sombras salían rosas (el negro del sensor trae algo de rojo y azul que al aclarar se multiplica) | Se mide ese tinte como lo que es, una cantidad que se **suma** a cada canal (extrapolando a luz cero), y se resta antes de aclarar |
| Noche con farolas, hojas en sombra | El arreglo del rosa medía el color medio de las sombras y borraba también la luz naranja de las farolas o el verde de las hojas | El color de la escena **escala** con la luz y ya no se confunde con el tinte; solo se corrige en fotos oscuras |
| Neones y luces de colores de noche | Al aclarar, el canal más alto se recortaba y el color viraba (naranja → amarillo) | El aclarado nunca pasa del máximo del canal más alto: mismo tono |
| HDR con horizonte (cielo quemado) | La alineación "encajaba" igual moviendo la foto a los lados y elegía el desplazamiento más extremo: fantasmas o cielo quemado | A igualdad se elige el desplazamiento más pequeño |
| HDR de atardecer | Contaba la foto normal aunque el rojo estuviera quemado: el cielo viraba a amarillo | Cada foto cuenta según su canal más alto; los colores que se salen se oscurecen un poco en vez de cambiar de tono |
| Mejora automática a la hora dorada | Enfriaba la luz cálida de verdad (playa, arena al sol) | El color de la luz se mide solo en superficies casi neutras, con corrección más prudente |
| Zoom con detalle de día | Quitaba el verde a las hojas en sombra | Sin corrección de negros en fotos de día |
| Todos con los ojos abiertos | Si la otra foto de la ráfaga salía más clara, la cara pegada se notaba | Se igualan la luz y el color de la cara con los de la foto |
| Ráfagas con flash AUTO | Unas fotos con flash y otras sin él, y un preflash por foto | Durante la ráfaga no hay flash (el modo noche lo sustituye) |
| Girar el móvil durante una ráfaga | Fotos con distinta orientación que no encajaban | La orientación se fija hasta que termina la ráfaga |
| Falta de memoria (sensores de muchos MP) | Si la fusión fallaba se perdía la foto; la mejora y la fusión podían ir a la vez | Se guarda la foto normal y se avisa; un solo trabajo pesado a la vez y el ruido de color usa 5 veces menos memoria |
| Móviles que guardan en Display P3 | Las copias procesadas saldrían apagadas | Todo se decodifica en sRGB |
