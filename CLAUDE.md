# PaperLink — guía para Claude Code

App Android que vincula un **código corto de 4 caracteres** (escrito a mano en un cuaderno) con uno o más recursos digitales (fotos, video, audio, PDF, archivos, links, notas). Esa función es lo único innegociable del producto.

**Estado:** reescritura en curso. La especificación completa, el diagnóstico y el plan por fases están en `docs/PLAN.md`. Leelo antes de empezar cualquier tarea; el checklist de cada fase indica qué está hecho.

## Stack

- Kotlin 2.4, Jetpack Compose + Material 3, Navigation Compose con rutas tipadas (kotlinx.serialization)
- Room 2.8 (KSP), DataStore Preferences, Coil 3, Coroutines + Flow
- DI manual (`AppContainer` en `PaperLinkApp`); no agregar Hilt salvo que se pida
- minSdk 26 · targetSdk/compileSdk 37 · AGP 9.2.1 · JVM 17
- Paquete y `applicationId`: `com.joasasso.paperlink` (debug usa sufijo `.debug`)

## Comandos

```
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest      # requiere dispositivo/emulador
./gradlew installDebug
adb logcat -s PaperLink
```

## Invariantes (no romper nunca)

1. **La app es dueña de sus archivos.** Todo lo que se vincula (cámara, galería, compartir, selector) se copia a `filesDir/media/`. Solo los links web quedan como referencia externa. Nunca usar `cacheDir` para contenido del usuario.
2. **La base guarda rutas relativas** (`media/<uuid>.jpg`), nunca URIs de FileProvider ni rutas absolutas. Las URIs se construyen al leer.
3. **Nunca `fallbackToDestructiveMigration` desde la versión nueva en adelante.** Todo cambio de esquema futuro lleva una `Migration` y un test. Los esquemas exportados en `app/schemas/` se versionan en git. Única excepción: descartar las versiones viejas 1 y 2 (ver PLAN.md).
4. **Sin soporte de legado.** Los datos de la versión anterior se descartan (solo la usaron unos pocos testers). La base nueva arranca de cero; no escribir código para importar datos viejos.
5. **Estado que deba sobrevivir a la muerte del proceso** (por ejemplo, una captura en curso) va en `SavedStateHandle` o `rememberSaveable`, nunca en `remember`.
6. **Offline-first.** La red solo se usa para el respaldo en Google Drive (opcional, activado por el usuario) y miniaturas de links web.
7. **Ningún error silencioso.** Prohibido el `catch` vacío; los errores relevantes se muestran al usuario (snackbar o estado de error).
8. **Strings en recursos:** `values/` (inglés) y `values-es/` (español). Nada hardcodeado en la UI, incluidas las `contentDescription`.
9. **Sin logs con URIs ni datos del usuario en release.** Usar un logger propio que solo escriba si `BuildConfig.DEBUG`.
10. **Balance entre simpleza y buenas prácticas.** Ni sobre-ingeniería ni atajos.
    - **Se mantiene siempre:** capas UI (Compose) → ViewModel → repositorio/almacén; estado inmutable expuesto como `StateFlow` y flujo unidireccional; nada de I/O en composables ni en ViewModels; dependencias por constructor (vía `AppContainer`) para poder testear; dispatchers inyectables; manejo explícito de errores; tests de la lógica central (códigos, `AttachmentStore`, repositorio, migraciones).
    - **Se evita:** abstracciones especulativas, interfaces con una sola implementación sin un motivo concreto (un test que la necesite es un motivo válido), capas que solo pasan llamadas, configurabilidad que nadie pidió y librerías para lo que se resuelve en pocas líneas.
    - Criterio: si una pieza hace el código más fácil de testear, de leer o de cambiar, va. Si solo agrega indirección, no. Ante la duda, preguntá.

## Forma de trabajo

- Una fase del roadmap por sesión. Leé la sección de esa fase en `docs/PLAN.md`, proponé un plan corto y después implementá.
- Trabajá directo en `master` (decisión del usuario, sin ramas por fase). Un commit por bloque lógico.
- Antes de dar una fase por terminada: compilá, corré los unit tests y, si la fase toca cámara, compartir, permisos o Drive, probala en el dispositivo con `adb` y revisá logcat.
- Revisá las **Garantías de confiabilidad** (`docs/PLAN.md`, sección 8) que correspondan a la fase y la matriz de casos difíciles. Una fase no está terminada si alguna garantía suya no se cumple.
- Al terminar, marcá el checklist de la fase en `docs/PLAN.md` y anotá ahí las decisiones que hayas tomado.
- Preguntá antes de: borrar datos o archivos del usuario, agregar dependencias pesadas, o cambiar algo marcado como "decisión pendiente" en el plan.

## Código a reutilizar de la versión anterior

`domain/CodeAlphabet.kt`, `util/PdfDecoder.kt`, `util/YouTubeUtils.kt`, `ui/theme/ColorUtils.kt`, las fuentes JetBrains Mono, los strings y la idea visual de `CodeInputField` (4 cajas grandes; hay que rehacer su accesibilidad). El resto se reescribe.
