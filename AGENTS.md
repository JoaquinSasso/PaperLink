# PaperLink — guía para agentes

App Android que vincula un **código corto de 4 caracteres** (escrito a mano en un cuaderno) con uno o más recursos digitales (fotos, video, audio, PDF, archivos, links, notas). Esa función es lo único innegociable del producto.

**Estado:** reescritura en curso, fases 0 a 3 hechas y la 4 implementada (falta cerrarla: ver "Estado actual"). Antes de cualquier tarea leé `docs/PLAN.md`: arriba de todo está **"Estado actual"** (qué sigue), y cada fase tiene su checklist, sus notas y sus decisiones. Este archivo es la guía única: `CLAUDE.md` lo importa y `.agents/` (reglas y workflows de Antigravity) apunta acá.

## Cómo trabajar con el usuario

- Escribí en **español rioplatense** (voseo), claro y sin jerga innecesaria. Código, commits y nombres en inglés; comentarios y KDoc en español.
- **Una fase del plan por sesión.** Leé su sección en `docs/PLAN.md`, preguntá primero las *decisiones pendientes* de esa fase, después proponé un plan corto y **esperá el OK** antes de tocar código.
- Trabajá directo en **`master`** (sin ramas por fase). Un commit por bloque lógico, con prefijo `Phase N:` y mensaje en inglés. No hagas push salvo que te lo pidan.
- **Preguntá antes de:** borrar datos o archivos del usuario (también en el teléfono), agregar dependencias pesadas, o cambiar algo marcado como "decisión pendiente".
- No cambies configuraciones del sistema del teléfono (TalkBack, "No conservar actividades", permisos del sistema): pedile al usuario que lo haga.
- Al terminar una fase: compilá, corré unit e instrumentados, probá en el teléfono, marcá el checklist en `docs/PLAN.md`, anotá decisiones (en la fase y en el "Registro de decisiones") y actualizá "Estado actual".

## Stack

- Kotlin 2.4, Jetpack Compose + Material 3, Navigation Compose con rutas tipadas (kotlinx.serialization)
- Room 2.8 (KSP), DataStore Preferences, Coil 3, Coroutines + Flow, CameraX 1.6 (`camera-compose`), telephoto (zoom del visor), androidx.exifinterface
- Respaldo: WorkManager, Google Identity (`play-services-auth`, scope `drive.appdata`), OkHttp (cliente REST de Drive v3 propio, sin `google-api-client`)
- DI manual (`AppContainer` en `PaperLinkApp`); no agregar Hilt salvo que se pida
- minSdk 26 · targetSdk/compileSdk 37 · AGP 9.2.1 · JVM 17 · base de datos versión 4
- Paquete y `applicationId`: `com.joasasso.paperlink` (debug: `com.joasasso.paperlink.debug`)

## Invariantes (no romper nunca)

1. **La app es dueña de sus archivos.** Todo lo que se vincula (cámara, galería, compartir, selector) se copia a `filesDir/media/`. Solo los links web quedan como referencia externa. Nunca usar `cacheDir` para contenido del usuario.
2. **La base guarda rutas relativas** (`media/<uuid>.jpg`), nunca URIs de FileProvider ni rutas absolutas. Las URIs se construyen al leer.
3. **Nunca `fallbackToDestructiveMigration`.** Todo cambio de esquema lleva una `Migration` (en `data/local/Migrations.kt`), un test en `MigrationTest` y sube `PaperLinkDatabase.VERSION`. Los esquemas de `app/schemas/` se versionan en git. Única excepción: descartar las versiones viejas 1 y 2.
4. **Sin soporte de legado.** Los datos de la app anterior se descartan; no escribir código para importarlos.
5. **Estado que deba sobrevivir a la muerte del proceso** va en `SavedStateHandle` o `rememberSaveable`, nunca en `remember`.
6. **Offline-first.** La red solo se usa para el respaldo en Google Drive (opcional) y miniaturas de links web.
7. **Ningún error silencioso.** Prohibido el `catch` vacío; los errores relevantes se muestran al usuario (snackbar o estado de error) con `Throwable.messageRes()`.
8. **Strings en recursos:** `values/` (inglés) y `values-es/` (español), siempre los dos. Nada hardcodeado en la UI, incluidas las `contentDescription`.
9. **Sin logs con URIs ni datos del usuario en release.** Usar `util/log()`, que solo escribe si `BuildConfig.DEBUG` (tag `PaperLink`).
10. **Balance entre simpleza y buenas prácticas.**
    - **Se mantiene siempre:** UI (Compose) → ViewModel → repositorio/almacén; estado inmutable como `StateFlow` y flujo unidireccional; nada de I/O en composables ni en ViewModels; dependencias por constructor (vía `AppContainer`); dispatchers inyectables; errores explícitos; tests de la lógica central.
    - **Se evita:** abstracciones especulativas, interfaces con una sola implementación sin motivo (un test que la necesite es motivo válido), capas que solo pasan llamadas, configurabilidad que nadie pidió, librerías para lo que se resuelve en pocas líneas.
    - Ante la duda, preguntá.

## Mapa del código (`app/src/main/java/com/joasasso/paperlink/`)

- `data/local/` — `Entities.kt` (`Link`, `Attachment` con `Kind`, borrado lógico `deletedAt` en ambos), `LinkDao.kt` (único DAO; las transacciones son métodos `@Transaction` con cuerpo), `PaperLinkDatabase.kt`, `Migrations.kt`.
- `data/files/AttachmentStore.kt` — **único componente que toca el disco**: importación atómica (`.part` + fsync + rename), SHA-256, chequeo de espacio, re-codificación de fotos a WebP (≤ 2400 px, calidad 70, EXIF), capturas de cámara en `filesDir/captures/`, barrido de huérfanos.
- `data/repository/LinkRepository.kt` — crear/agregar en transacción (importa primero, limpia si falla), códigos generados con reintento ante choque, borrado lógico con deshacer y purga diferida, búsqueda por etiqueta sin tildes, mantenimiento al iniciar. `AttachmentSource`: `Content(uri)`, `Stored(ya importado)`, `WebUrl`, `Note`. Los archivos importados que fallan se borran solo si ninguna fila los referencia (`discardUnreferenced`).
- `data/repository/BatchImporter.kt` — cargas de varios elementos desde la app (galería, archivos): el código se crea vacío y se devuelve al instante; los elementos se importan de a 3 y se agregan en segundo plano en orden, con progreso (`statuses`) y aviso si el proceso muere a mitad (`pendingImports` en preferencias).
- `data/backup/` — respaldo: `ZipBackupManager` (export/import manual), `DriveBackupManager` (incremental en `appDataFolder`; **nunca achica lo que hay en Drive**: si Drive tiene un respaldo que este teléfono no fusionó se niega a respaldar, y no borra en Drive archivos que una fila local todavía referencia), `DriveAppDataClient` (REST, subida reanudable), `DriveAuthManager`, `DriveBackupWorker` (diario). La fusión al restaurar es `LinkDao.mergeBackup` (por id, solo agrega).
- `domain/` — funciones puras con unit tests: `CodeAlphabet` (26 caracteres, generación, validación), `CodeSuggestions`, `SearchText`, `WebLinks`.
- `di/AppContainer.kt` — DI manual y `applicationScope` (trabajo que sobrevive a las pantallas).
- `ui/navigation/NavGraph.kt` — rutas tipadas: Home, Onboarding, Camera, Code (tras crear), Share, Viewer, Detail, Backup. `MainActivity` traduce intents (compartir, acción `CAPTURE` del widget/atajo) a rutas.
- `ui/screens/*` — una carpeta por pantalla (Screen + ViewModel con `Factory`). `ui/common/` — piezas compartidas: `CodeInputField`, `AddContentSheet`, `ContentImporter` (progreso y cancelar), `AttachmentThumbnail`, `messageRes()`.
- `util/` — `log()`, `PdfDecoder` (miniaturas de PDF para Coil), `YouTubeUtils`. `widget/` — widget que abre la cámara.
- `app/src/debug/` — `SeedReceiver` (carga/borra códigos de prueba; solo debug).

## Comandos

```
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w com.joasasso.paperlink.debug.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -s PaperLink
```

- **No uses `connectedDebugAndroidTest`:** desinstala la app al terminar y borra los datos del usuario en el teléfono. Los instrumentados usan base en memoria y un directorio temporal (`TestDir`), así que no tocan datos reales.
- En Windows (Git Bash) los comandos `adb shell` con rutas necesitan `MSYS_NO_PATHCONV=1` (por ejemplo `uiautomator dump /sdcard/ui.xml`).
- Códigos de prueba (solo debug, etiqueta `[seed]`; UNSEED borra solo esos):
  `adb shell am broadcast -a com.joasasso.paperlink.debug.SEED --ei count 200 -p com.joasasso.paperlink.debug`
  `adb shell am broadcast -a com.joasasso.paperlink.debug.UNSEED -p com.joasasso.paperlink.debug`
- Muerte del proceso: mandá la app a segundo plano (`input keyevent KEYCODE_HOME`), `am kill` o `run-as com.joasasso.paperlink.debug kill -9 <pid>`, y reabrí desde el launcher.
- `adb shell input text` inyecta teclas más rápido que una persona y Gboard pierde caracteres: para probar el campo de código, enviá de a una tecla con ~150 ms de pausa.
- Los logs de debug `Lookup started` / `Viewer shown` sirven para medir la apertura por código.

## Pruebas en dispositivo

- El usuario tiene un Pixel 8 (API 37) conectado por adb, con datos de prueba propios en la app debug: no los borres.
- Antes de cerrar una fase que toque cámara, compartir, permisos, almacenamiento o Drive: probá en el teléfono, revisá logcat (`-b crash` y `-s PaperLink`) y repasá las **Garantías de confiabilidad** y la matriz de casos difíciles (sección 8 de `docs/PLAN.md`). Una fase no está terminada si alguna garantía suya no se cumple.
- Lo que requiere manos o ajustes del sistema (TalkBack, "No conservar actividades", compartir desde Google Fotos, archivos HEIC o enormes) pedíselo al usuario con pasos concretos.

## Código heredado

De la versión anterior quedan `domain/CodeAlphabet.kt` (rehecho), `util/PdfDecoder.kt`, `util/YouTubeUtils.kt`, `ui/theme/` y las fuentes JetBrains Mono. Los strings viejos que no se usan se limpian en la fase 5. Las notas de producto viejas están en `docs/archive/` (solo referencia, no son el plan).
