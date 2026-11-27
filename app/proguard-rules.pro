# ============================================================================
#  Unlock BYD - regras de ofuscacao (R8)
#  Objetivo: embaralhar o NOSSO codigo (com.euler.catalog) e preservar as
#  bibliotecas sensiveis, senao ADB/cluster/auto-update quebram.
# ============================================================================

-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# ---- Cliente ADB (dadb): handshake RSA/PKCS8 com o carro. NAO ofuscar. ----
-keep class dadb.** { *; }
-keep interface dadb.** { *; }
-dontwarn dadb.**

# ---- okio (IO usado pelo dadb) ----
-keep class okio.** { *; }
-dontwarn okio.**

# ---- WorkManager + Room interno (auto-update e agendamentos) ----
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**
# Nossos Workers sao instanciados por nome pelo WorkManager
-keep class * extends androidx.work.ListenableWorker { *; }

# ---- Kotlin / Coroutines (dependencia do dadb e do androidx) ----
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class kotlin.coroutines.** { *; }
-keep class kotlin.Metadata { *; }

# ============================================================================
#  Nosso app
# ============================================================================
# Device admin / device-owner: o nome do componente PRECISA ser estavel entre
# versoes, senao os carros que ja tem device-owner perdem o vinculo. NAO renomear.
-keep class com.euler.catalog.CatalogAdminReceiver { *; }

# Reflexao na API do sistema BYD (classe externa acessada por string) - sem warning.
-dontwarn android.hardware.bydauto.**
-dontwarn android.hardware.AVMCamera**
-dontwarn android.hardware.BmmCameraInfo

# Componentes citados no manifesto (Activities/Services/Receivers) ja sao mantidos
# pelo AGP; o resto de com.euler.catalog pode ser ofuscado normalmente.

# ---- WebRTC (camaras ao vivo): classes chamadas pelo codigo nativo via JNI. NAO ofuscar. ----
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# ---- OkHttp (WebSocket do hub) ----
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Vigia: roda via app_process pelo nome da classe. NAO ofuscar.
-keep class com.euler.catalog.AccDaemon { *; }

# Controles do carro: roda via app_process pelo nome da classe. NAO ofuscar.
-keep class com.euler.catalog.CarCtl { *; }
