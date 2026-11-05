# Las extensiones de cámara del fabricante (Samsung, Honor…) se cargan desde el
# sistema y llaman a clases de CameraX por su nombre: no se deben renombrar.
-keep class androidx.camera.extensions.** { *; }
-dontwarn androidx.camera.extensions.impl.**

# MediaPipe (IA en el móvil) usa código nativo y protobuf por reflexión.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn autovalue.shaded.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**

# MediaPipe registra mensajes con Flogger, que busca su "plataforma" por nombre
# (com.google.common.flogger.backend.system.DefaultPlatform). Si R8 la renombra,
# la carga de los modelos falla con ExceptionInInitializerError ("IA no disponible: null").
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**
