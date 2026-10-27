# Respaldo en Google Drive: velocidad, segundo plano y sincronización por Wi-Fi

| | |
| --- | --- |
| **Fecha** | 27/10/2026 |
| **Estado** | Resuelto y verificado en el teléfono |
| **Áreas** | `DriveAppDataClient`, `DriveBackupManager`, `DriveSyncWorker` (nuevo), `DriveBackupWorker`, `UserPreferencesRepository`, `LinkRepository`, `MainActivity`, `BackupScreen` |
| **Dispositivo de prueba** | Pixel 8 (API 37, Wi-Fi) |
| **Commits** | `99dcace` + sincronización automática por Wi-Fi |

## Resumen

El usuario pidió optimizar el respaldo en Google Drive cumpliendo tres objetivos y un flujo automático:

1. **Carga rápida:** sabiendo que casi todos los archivos (fotos WebP 2400 px q70 de ~124 KB) pesan menos de 5 MB, usar una conexión directa no reanudable y paralelismo.
2. **Incremental:** nunca volver a subir lo que ya está en `appDataFolder`.
3. **En segundo plano:** que el usuario no tenga que quedarse mirando una barra de progreso en la pantalla de Respaldo.
4. **Sincronización automática por Wi-Fi:** subir en segundo plano apenas el usuario agrega o edita algo (si hay Wi-Fi; si no, dejarlo pendiente para cuando vuelva el Wi-Fi o se reabra la app) y, al abrir la app con Wi-Fi, buscar si otro dispositivo actualizó el respaldo en Drive para fusionarlo automáticamente.

Después de medir todas las variantes reales contra `appDataFolder` y un servidor de control (Cloudflare) en el Pixel 8:

| Métrica | Antes | Después |
| --- | --- | --- |
| Protocolo para archivos ≤ 5 MB | `uploadType=resumable` (2 viajes HTTP por archivo, secuencial) | `uploadType=multipart` (1 viaje HTTP, hasta **8 en paralelo** sobre HTTP/2) |
| Subida de 16 fotos de 124 KB | **74,1 s** (4.631 ms/archivo) | **3,8 s** (237 ms/archivo, **19,5× más rápido**) |
| Subida de 16 fotos reales de `media/` (7,5 MB) | ~74 s | **11,6 s** (727 ms/archivo, **6,4× más rápido**) |
| Limpieza de archivos obsoletos en Drive | Secuencial (~650 ms/archivo) | Hasta **8 en paralelo** (~80 ms/archivo) |
| Ejecución manual ("Respaldar ahora") | Atada al `viewModelScope` con diálogo modal bloqueante | `DriveSyncWorker` en segundo plano con progreso no bloqueante |
| Al agregar/editar contenido con Wi-Fi | Esperaba al trabajo diario de 24 h | Se encola automáticamente (`debounce` de 3 s, `NetworkType.UNMETERED`); sin Wi-Fi queda pendiente (`pendingSync`) |
| Al abrir la app con Wi-Fi | No consultaba Drive | Verifica `appDataFolder`; si otro dispositivo subió cambios (`NeedsMerge`), los fusiona sin subir un manifest redundante |

---

## Línea de tiempo

| Fase | Hecho |
| --- | --- |
| Sesión previa (Claude Code) | Se implementa `multipart` para ≤ 5 MB, paralelismo de a 8 en `DriveBackupManager`, fusión previa automática (`NeedsMerge` → fusiona y sube en vez de bloquear con diálogo) y `DriveSyncWorker` para correr en segundo plano (`99dcace`). Se arma `DriveBenchReceiver` para medir en el Pixel 8, pero se agota la cuota antes de completar las mediciones y falta la sincronización reactiva al agregar contenido y al abrir la app. |
| Retome (Antigravity) | Se inspecciona el estado en el Pixel 8 y se detecta por qué `DriveBenchWorker` se cortaba con la pantalla apagada en Android 12+ (`ForegroundServiceStartNotAllowedException`). |
| Mediciones en Pixel 8 | Se corren todas las baterías de `DriveBench` en el Pixel 8: control contra Cloudflare (`cf-x1`, `cf-x8`), `resumable-x1`, `multipart-x1/x4/x8/x12`, comparación HTTP/2 vs HTTP/1.1 (`-h1`) y comparación `image/webp` vs `application/octet-stream` con las 16 fotos reales del usuario (`real-x8`, `realbin-x8`). |
| Implementación | Se corrige el inicio en segundo plano de `DriveSyncWorker`, se paraleliza el borrado de obsoletos en `DriveBackupManager` y se implementa la sincronización automática por Wi-Fi al modificar datos locales (`onLocalChange` + `pendingSync`) y al abrir la app (`onAppOpened` + `syncNow`). |
| Verificación | 42 unit tests y 80 tests instrumentados en verde en el Pixel 8, más prueba en vivo en el dispositivo verificando la subida automática a los ~3 s de crear un código. |

---

## El problema

Aunque cada foto nueva ya pesaba apenas ~124 KB gracias al paso a WebP 2400 px q70, el respaldo inicial o de una tanda de fotos en Google Drive seguía tardando más de un minuto para menos de 20 fotos, exigía dejar la pantalla abierta mirando el progreso, y solo corría manualmente o una vez por día.

---

## Hipótesis y pruebas en el Pixel 8

Se usó `DriveBenchReceiver` (`com.joasasso.paperlink.debug.DRIVE_BENCH`) sobre el Pixel 8 conectado por Wi-Fi, subiendo a `appDataFolder` lotes de **16 archivos de 124 KB** (el promedio real de una foto WebP 2400 px q70) y luego las **16 fotos reales** guardadas en `filesDir/media/` del teléfono (7.485 KB en total, promedio 468 KB porque incluye JPEGs anteriores al cambio a WebP).

### 1. ¿El cuello de botella es el Wi-Fi del teléfono o la latencia por archivo de Google Drive?

Para separar la red local del servidor de Drive, se midió la misma subida de 16 × 124 KB contra `https://speed.cloudflare.com/__up`:

| Modo | Servidor | Paralelismo | Tiempo total (16 × 124 KB) | Por archivo | Caudal efectivo |
| --- | --- | --- | --- | --- | --- |
| `cf-x1` | Cloudflare | 1 | 15.012 ms | 938 ms | 132 KB/s |
| `cf-x8` | Cloudflare | 8 | **469 ms** | **29 ms** | **4.230 KB/s (~34 Mbps)** |

**Conclusión:** el Wi-Fi del teléfono sube 2 MB en menos de medio segundo. La demora en Drive no es ancho de banda, sino **latencia fija del servidor de Google Drive por cada archivo creado en `appDataFolder`** (~1,8 a 2,0 s por transacción).

### 2. Subida reanudable (`resumable`) vs. una sola petición (`multipart`) y nivel de paralelismo

Con `uploadType=resumable`, cada archivo de 124 KB requería **2 viajes HTTP** (un `POST` para abrir la sesión y obtener el `Location`, y un `PUT` con los bytes). Con `uploadType=multipart` (`related`), metadatos y contenido viajan en **un solo `POST`** para cualquier archivo de hasta 5 MB:

| Modo | Peticiones HTTP / archivo | Paralelismo | Tiempo total (16 × 124 KB) | Por archivo | Mejora vs. original |
| --- | --- | --- | --- | --- | --- |
| `resumable-x1` *(antes)* | 2 | 1 | 74.108 ms | 4.631 ms | 1,0× |
| `multipart-x1` | 1 | 1 | 30.351 ms | 1.896 ms | **2,4×** |
| `multipart-x4` | 1 | 4 | 7.465 ms | 466 ms | **9,9×** |
| **`multipart-x8` *(elegida)*** | **1** | **8** | **3.806 ms** | **237 ms** | **19,5×** |
| `multipart-x12` | 1 | 12 | 3.578 ms | 223 ms | 20,7× |

- Pasar de `resumable` a `multipart` baja el tiempo a menos de la mitad (de 4,6 s a 1,9 s por archivo).
- Subir de a **8 en paralelo (`multipart-x8`)** oculta la latencia por archivo del backend de Drive y baja las 16 fotos de **74,1 s a 3,8 s** (19,5× más rápido).
- **Por qué se descartó `multipart-x12`:** pasar de 8 a 12 hilos solo ahorra 228 ms en 16 archivos (6 %) y acerca las peticiones al límite de concurrencia por usuario de la API de Drive (errores `403 rateLimitExceeded` / `429`).

### 3. ¿Google Drive penaliza `image/webp` frente a `application/octet-stream` o HTTP/2 frente a HTTP/1.1?

Se probó una hipótesis adicional: ¿tarda más Drive cuando ve un MIME `image/webp` (por intentar indexar o generar miniaturas) que cuando recibe `application/octet-stream`? ¿Y conviene abrir 8 conexiones TCP separadas con HTTP/1.1 (`-h1`) en vez de multiplexar sobre una sola conexión TLS con HTTP/2? Se midió con las **16 fotos reales de `media/` (7.485 KB)**:

| Modo | MIME | Protocolo | Tiempo total (16 fotos = 7.485 KB) | Por archivo |
| --- | --- | --- | --- | --- |
| **`real-x8`** | `image/webp` | **HTTP/2** | **11.644 ms** | **727 ms** |
| `realbin-x8` | `application/octet-stream` | HTTP/2 | 11.066 ms | 691 ms |
| `real-x8-h1` | `image/webp` | HTTP/1.1 | 22.062 ms | 1.378 ms |
| `realbin-x8-h1` | `application/octet-stream` | HTTP/1.1 | 20.846 ms | 1.302 ms |

- **`application/octet-stream` descartado:** la diferencia contra `image/webp` es de apenas ~5 % (dentro de la variación normal de red), por lo que en `appDataFolder` Drive no penaliza el MIME real y conviene conservarlo.
- **HTTP/1.1 (`-h1`) descartado:** obligar a OkHttp a usar HTTP/1.1 duplica el tiempo en archivos reales (de 11,6 s a 22,1 s) porque tiene que negociar 8 conexiones TCP+TLS independientes en vez de multiplexar sobre la conexión HTTP/2 ya abierta al pedir la lista de archivos.

---

## Decisión e implementación

1. **Subida híbrida en `DriveAppDataClient`:**
   - Archivos de hasta **5 MB** (`MULTIPART_LIMIT_BYTES = 5 * 1024 * 1024`): subida directa en una sola petición `multipart/related`.
   - Archivos mayores a 5 MB (videos o PDFs grandes): subida reanudable (`uploadType=resumable`) en bloques de 256 KB.
2. **Paralelismo de a 8 (`PARALLEL_TRANSFERS = 8`) en `DriveBackupManager`:**
   - Subida en paralelo de archivos nuevos (`toUpload`), descarga en paralelo al restaurar (`toDownload`) y borrado en paralelo de manifests viejos y archivos huérfanos luego de confirmar el nuevo `manifest.json`.
   - Garantía 12 intacta: todos los archivos nuevos terminan de subirse antes de subir el nuevo `manifest.json`; si cualquier subida falla o se cancela, el `manifest.json` anterior sigue vigente.
3. **Sincronización automática bidireccional por Wi-Fi (`DriveBackupManager.syncNow` + `WorkManagerBackupScheduler`):**
   - **Al agregar, editar o purgar contenido local (`LinkRepository` → `onLocalChange`):** se marca `pendingSync = true` en `UserPreferencesRepository` (persiste aunque se cierre la app) y, si el respaldo en Drive está activo, se encola `DriveBackupWorker` con `ExistingWorkPolicy.KEEP`, restricción `NetworkType.UNMETERED` (Wi-Fi) y un *debounce* de 3 segundos para agrupar ediciones seguidas. Además, `DriveBackupWorker` espera a que `BatchImporter.awaitIdle()` termine de comprimir una tanda de fotos antes de armar el snapshot.
   - **Si no hay Wi-Fi cuando el usuario agrega contenido:** `pendingSync` queda en `true` en DataStore y el trabajo único de `WorkManager` queda pendiente en `JobScheduler` esperando `NetworkType.UNMETERED` (corre solo apenas el teléfono se conecta a una red Wi-Fi, incluso con la app cerrada, o al próximo arranque de la app).
   - **Al abrir la app con Wi-Fi (`MainActivity.onStart` → `onAppOpened`):** si el respaldo está activo, encola `DriveBackupWorker` sin demora (`debounce = false`) con `NetworkType.UNMETERED`.
   - **Sin ping-pong entre dispositivos (`syncNow`):** cuando `syncNow()` corre, primero lista `appDataFolder`. Si detecta `RemoteState.NeedsMerge` (otro dispositivo subió un `manifest.json` distinto de `syncedManifestId`), descarga los archivos nuevos y fusiona el manifest remoto en la base local (`restoreWithManifestLocked`, que actualiza `syncedManifestId`). Recién después evalúa si este teléfono tiene cambios propios que no estén en ese manifest (`pendingSync == true` o `hasUnbackedLocalChanges(remoteManifest)`): si no los tiene, **no vuelve a subir otro `manifest.json`**, evitando que dos dispositivos se pasen la pelota infinitamente cada vez que abren la app.

---

## Bugs encontrados en el camino

### 1. `ForegroundServiceStartNotAllowedException` al iniciar un Worker en segundo plano en Android 12+ (API 37)

- **Cómo apareció:** al correr `DriveBenchWorker` y al dispararse un worker con el teléfono bloqueado o la app en segundo plano (`uidState: TRNB`), Android 12+ lanzó `android.app.ForegroundServiceStartNotAllowedException: startForegroundService() not allowed due to mAllowStartForeground false` al llamar a `setForeground(getForegroundInfo())`, abortando el worker.
- **Causa:** desde Android 12 (API 31), un `CoroutineWorker` que arranca cuando la app no está visible (`PROC_STATE_TOP`) no tiene permiso para promoverse a servicio en primer plano (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`), salvo exenciones específicas. Sin embargo, cuando `WorkManager` ejecuta el worker a través de `SystemJobService` con restricción de red (`NetworkType.UNMETERED` o `CONNECTED`), el propio `JobScheduler` ya le otorga `PROCESS_CAPABILITY_POWER_RESTRICTED_NETWORK` sin necesidad de un servicio en primer plano.
- **Corrección:** en `DriveSyncWorker`, las llamadas a `setForeground(...)` se envuelven en `runCatching { ... }` con fallback a una notificación normal de progreso (`NotificationManagerCompat.notify`), que se limpia en el bloque `finally`. Así, si el usuario inicia el respaldo con la app abierta obtiene el servicio en primer plano, y si el sistema arranca el worker desde segundo plano no crashea y completa la transferencia.

---

## Verificación

- **Unit tests:** 42 tests en verde (`./gradlew testDebugUnitTest`).
- **Tests instrumentados en el Pixel 8:** 80 tests en verde (`BackupTest`: 14 tests, incluidos 4 nuevos de paralelismo, fusión previa y sincronización automática por Wi-Fi sin ping-pong ni pérdida de `pendingSync` ante errores de red).
- **Prueba en vivo en el Pixel 8 (API 37, Wi-Fi):**
  - Al abrir `MainActivity`, `onAppOpened()` dispara `DriveBackupWorker` y registra `Automatic Drive sync succeeded`.
  - Al crear un código nuevo en la app, `onLocalChange()` agenda la sincronización con *debounce* de 3 s; a los ~4 s el log confirma `Drive backup: 0 files uploaded in 2 ms` + subida del `manifest.json` y `Automatic Drive sync succeeded`.
  - Al borrar el código de prueba y cumplirse los 15 s de la ventana de deshacer, `purgeExpired()` dispara automáticamente una nueva sincronización que actualiza Drive.

---

## Qué salió bien / qué se puede mejorar

- **Qué salió bien:** medir contra un servidor de control (`speed.cloudflare.com`) permitió demostrar en minutos que el cuello de botella no era la conexión Wi-Fi del usuario sino el RTT por archivo de `appDataFolder`, orientando toda la solución a reducir viajes HTTP por archivo (`multipart`) y paralelizar de a 8 sobre HTTP/2.
- **Qué se puede mejorar:** cuando se usa `WorkManager` para pruebas de diagnóstico (`DriveBenchWorker`), usar siempre `enqueueUniqueWork` con `ExistingWorkPolicy.REPLACE`; con `enqueue` suelto, un worker interrumpido por una reinstalación de APK vuelve a arrancar en paralelo con la nueva corrida y ensucia los tiempos hasta que termina.

---

## Pendientes

- Ninguno para el flujo de respaldo en Drive. Para cerrar la Fase 4 solo resta la prueba manual en una instalación limpia del botón "Ahora no" en el diálogo de respaldo encontrado al conectar la cuenta por primera vez.
