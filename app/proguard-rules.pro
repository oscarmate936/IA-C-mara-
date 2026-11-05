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
