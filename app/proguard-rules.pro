# Las extensiones de cámara del fabricante (Samsung, Honor…) se cargan desde el
# sistema y llaman a clases de CameraX por su nombre: no se deben renombrar.
-keep class androidx.camera.extensions.** { *; }
-dontwarn androidx.camera.extensions.impl.**
