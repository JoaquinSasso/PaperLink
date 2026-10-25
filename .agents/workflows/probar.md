---
description: Compilar, correr todos los tests y hacer una prueba rápida en el teléfono
---

1. `./gradlew assembleDebug testDebugUnitTest` y reportá cuántos unit tests pasaron (resultados en `app/build/test-results/testDebugUnitTest/`).
2. Confirmá el teléfono con `adb devices`. Si no hay ninguno, avisá y frená acá.
3. `./gradlew installDebug installDebugAndroidTest` y después `adb shell am instrument -w com.joasasso.paperlink.debug.test/androidx.test.runner.AndroidJUnitRunner`. **No** uses `connectedDebugAndroidTest`.
4. Prueba rápida: `adb shell am force-stop com.joasasso.paperlink.debug`, abrí la app con `adb shell am start -W -n com.joasasso.paperlink.debug/com.joasasso.paperlink.MainActivity`, sacá una captura (`adb exec-out screencap -p`) y revisá que la Home cargue.
5. Revisá `adb logcat -d -b crash` y `adb logcat -d -s PaperLink`.
6. Reportá resultados con números (tests, tiempos, errores) y cualquier falla con su stack trace.
