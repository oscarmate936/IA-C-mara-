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
| 2 — Percepción en vivo | Escena, sujeto, caras, luz | Siguiente |

## Probar la versión actual

1. Instala el APK y concede el permiso de cámara.
2. Haz fotos con la cámara trasera y la frontal, con varios zooms y con los modos que aparezcan.
3. Pulsa el icono ⓘ (arriba a la derecha) → **Copiar** y pega el informe de diagnóstico en el chat.
   Con ese informe se ajusta la IA a lo que permite cada móvil.
