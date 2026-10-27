# PaperLink — Plan de reescritura

Origen: auditoría del 23/10/2026. Este documento es la especificación de trabajo; mantenerlo actualizado (checklists, decisiones y "Estado actual") al cerrar cada fase. La guía para agentes (invariantes, comandos, mapa del código, forma de trabajo) está en `AGENTS.md`.

## Estado actual (para retomar)

Actualizado: 26/10/2026.

- **Hechas:** fases 0 a 3. **Fase 4 implementada** (hecha con Antigravity y revisada/corregida con Claude Code), todavía sin cerrar. Todo commiteado en `master`, sin push. Base de datos en versión 4.
- **Para cerrar la Fase 4 (lo demás ya está verificado):**
  1. Probar en el teléfono "Ahora no" en el aviso de respaldo encontrado, con la versión actual: Drive no debe cambiar y la Home debe avisar que el respaldo automático está en pausa.
  2. Dejar en el repo (por ejemplo `docs/GOOGLE_CLOUD.md`) las instrucciones de configuración de Google Cloud que pedía el plan: no están.
- **Pendiente del usuario (Fase 3):** verificar con TalkBack el ingreso de código en la Home (tiene que anunciarse "Código, N de 4 caracteres", leer las letras de a una y permitir pegar).
- **Siguiente después:** Fase 5 (pulido y publicación).
- **Garantías:** 1 a 11 implementadas y probadas (fases 1 a 3). 12 a 15 implementadas en la Fase 4 y cubiertas por tests; la restauración completa se probó en el teléfono.
- **Matriz de casos difíciles pendiente:** foto HEIC real y video de más de 1 GB (el usuario no tenía archivos así), dispositivo de gama baja y build de release con R8 → Fase 5.
- **Herramientas:** el trabajo de las fases 0 a 3 se hizo con Claude Code; desde acá se puede seguir con Antigravity (`AGENTS.md`, `.agents/rules/`, `.agents/workflows/`: `/fase`, `/probar`, `/retomar`) o con Claude Code (`CLAUDE.md` importa `AGENTS.md`).

## 1. Contexto y diagnóstico

PaperLink promete permanencia (un código escrito en papel dura años), pero la versión anterior se apoya en almacenamiento frágil. Problemas que motivan la reescritura:

- **Fotos de cámara que desaparecen.** Causas probables:
  1. Hasta el commit `12e0b9f` (9/6/2026) la cámara guardaba en `cacheDir/camera`, que Android vacía solo. Ese commit además renombró la ruta del FileProvider de `camera_temp` a `camera_photos`, así que los vínculos viejos apuntan a una ruta inexistente.
  2. Las fotos viven solo en `filesDir`: reinstalar o cambiar de teléfono las pierde, y Auto Backup (tope de 25 MB) no las salva.
  3. `tempCameraUri` usa `remember`: si el sistema mata el proceso con la cámara abierta, la foto queda huérfana y sin código.
- `fallbackToDestructiveMigration()` borra todos los vínculos ante cualquier cambio de esquema.
- No hay exportación ni respaldo.
- Los archivos elegidos con SAF son referencias: si se borran o mueven en origen, el vínculo se rompe.
- UX: después de guardar no se muestra el código (el momento clave); la Home mezcla "tipear código" con una grilla que no escala; pide `READ_MEDIA_IMAGES` al abrir; no acepta links compartidos (`text/plain`) ni varios archivos (`SEND_MULTIPLE`).
- Código: dos caminos distintos para crear vínculos, I/O dentro de ViewModels, flag `shouldLaunchCamera` frágil, errores tragados, logs con URIs, cero tests.

## 2. Decisiones de producto

- **Enfoque minimalista**: anotar y recuperar, hecho de forma confiable. La confiabilidad es parte del núcleo.
- **Sí ahora:** respaldo (ZIP manual + Google Drive), varios archivos por código, etiqueta opcional (nunca obligatoria).
- **Después, si el uso lo pide:** escanear el código con la cámara (ML Kit on-device), sincronización entre dispositivos.
- **No:** backend propio, cliente web, IA/RAG/búsqueda semántica, OCR de manuscritos, materias u horarios de cursada.

## 3. Modelo de datos

```kotlin
@Entity(tableName = "links")
data class Link(
    @PrimaryKey val code: String,      // "HWQ3", siempre en mayúsculas
    val label: String?,                // opcional
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?               // borrado lógico (deshacer); se purga después
)

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(Link::class, ["code"], ["linkCode"], onDelete = CASCADE)],
    indices = [Index("linkCode")]
)
data class Attachment(
    @PrimaryKey val id: String,        // UUID
    val linkCode: String,
    val kind: Kind,                    // IMAGE, VIDEO, AUDIO, PDF, FILE, URL, NOTE
    val relativePath: String?,         // "media/<uuid>.<ext>" dentro de filesDir
    val url: String?,                  // solo URL
    val text: String?,                 // solo NOTE
    val mimeType: String?,
    val sizeBytes: Long?,
    val sha256: String?,               // integridad del archivo y del respaldo
    val position: Int,                 // orden dentro del código
    val createdAt: Long
)
```

- Las notas de texto se guardan en `text`, no como archivo.
- Si un archivo falta en disco, la UI muestra "archivo no disponible" sin ocultar el código (se detecta al leer; no hace falta columna de estado).

## 4. Códigos

- Alfabeto: `ACDEFGHJKLMNPQRTUVWXY34679` (26 caracteres, 26⁴ = 456.976 combinaciones). Excluye ambos miembros de cada par confundible a mano: `I`/`1`, `O`/`0`, `Z`/`2`, `S`/`5`, `B`/`8`. Decidido el 23/10/2026 (antes eran 32 caracteres, sin I/O/0/1). Como no hay datos viejos que conservar, validación y generación usan el mismo alfabeto.
- Al buscar un código inexistente, sugerir códigos existentes a distancia 1 (sustituciones de pares confundibles primero).

## 5. Componentes

- **`AttachmentStore`**: único componente que toca el disco. `import(uri, kind): ImportResult`, `delete(relativePath)`, `uriFor(relativePath)`, `fileFor(relativePath)`. Escribe en `filesDir/media/`. Las imágenes se re-codifican a JPEG con el lado mayor ≤ 3000 px y calidad 85 (valores en constantes). Testeado.
- **`LinkRepository`**: CRUD de `Link` + `Attachment` en transacciones, búsqueda por código y por etiqueta, recientes.
- Crear, agregar y borrar (con deshacer) son métodos de `LinkRepository`; la generación de código es una función pura junto al alfabeto. Sin capa de casos de uso.
- **Room:** versión 3 del mismo archivo `paperlink.db`, con `fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2)` para descartar las versiones viejas sin crashear al actualizar. De la 3 en adelante, migraciones reales con test (`MigrationTestHelper`). Sacar `/app/schemas` del `.gitignore`.
- Sin migración de datos viejos: los archivos de la versión anterior (`files/camera/`, `files/shared/`, `files/notes/`) se ignoran; quien tenga la versión vieja puede desinstalarla.
- **Cámara:** CameraX dentro de la app, con captura de varias fotos seguidas. El estado de la sesión de captura sobrevive a la muerte del proceso.
- **Logger:** una función `log()` de pocas líneas que solo escribe si `BuildConfig.DEBUG`.

## 6. Flujos de UX

1. **Capturar:** widget o atajo del ícono de la app → cámara propia → 1 a N fotos → "Listo".
2. **Pantalla del código** (nueva, obligatoria tras crear): código gigante en JetBrains Mono, vibración de confirmación, campo de etiqueta opcional, botones "Agregar más" y "Listo". Desde la Home se puede volver al "último código creado".
3. **Recuperar:** la Home es el campo de código arriba, los recientes abajo (lista con miniatura, etiqueta y fecha; no grilla de todo) y búsqueda por etiqueta. Al completar 4 caracteres, si existe, abre; si no, error claro + sugerencias.
4. **Ver:** visor propio con zoom (pinch/doble toque) y deslizamiento entre los archivos del código. Video, audio, PDF y archivos pueden abrirse con apps externas en esta versión.
5. **Compartir hacia la app:** `SEND` y `SEND_MULTIPLE` para `image/*`, `video/*`, `audio/*`, `application/pdf` y `text/plain` (links). Pantalla de destino: "Nuevo código" o "Agregar a un código existente".
6. **Agregar desde la app:** Photo Picker (`PickMultipleVisualMedia`, sin permisos) y `OpenMultipleDocuments` para el resto; URL; nota de texto.
7. **Borrar:** desde el detalle del código, con snackbar "Deshacer" (el borrado físico se difiere unos segundos).
8. **Onboarding:** una sola pantalla, sin pedir permisos. La cámara pide permiso recién al usarse.

## 7. Respaldo

### ZIP manual
`manifest.json` (versión de formato, links, attachments) + carpeta `media/`. Exportar con `CreateDocument`, importar con `OpenDocument` (fusiona sin duplicar: si el código existe, se agregan los attachments que falten por id).

### Google Drive (como WhatsApp)
Sin servidor propio, con la cuenta de Google del usuario.

- **Decisión pendiente (confirmar con el usuario en la fase 4):**
  - `drive.appdata`: carpeta oculta de la app, igual que WhatsApp; el usuario solo puede borrarla desde Drive → Configuración → Administrar apps.
  - `drive.file`: carpeta visible "PaperLink"; el usuario ve sus fotos desde la PC.
  - Ambos scopes son no sensibles: solo verificación básica de OAuth.
- Autorización con Credential Manager + `Identity.getAuthorizationClient()` pidiendo solo el scope elegido. Requiere proyecto en Google Cloud, pantalla de consentimiento y SHA-1 de debug y release (el usuario hace esa configuración; dejale instrucciones paso a paso).
- Cliente REST de Drive v3 con OkHttp (evitar `google-api-client` por su peso, salvo que se justifique).
- Subida incremental con WorkManager (diaria, Wi-Fi, opcionalmente solo cargando): cada attachment se sube una vez, identificado por su UUID; en cada respaldo se sube un snapshot de la DB/manifest.
- Restauración: en el primer inicio, si el usuario inicia sesión y hay respaldo, ofrecer restaurarlo.
- Opcional y activado explícitamente; sin sesión la app es 100% offline. Mostrar fecha del último respaldo y tamaño.
- No usar SAF para el respaldo automático: Drive no permite elegir una carpeta de forma confiable.

Además, configurar `data_extraction_rules.xml` para incluir DB y `media/` en la transferencia entre dispositivos (sin tope de 25 MB).

## 8. Garantías de confiabilidad

La app hace poco, así que eso poco tiene que ser impecable. Cada punto se implementa en la fase indicada y se verifica con su criterio.

| # | Garantía | Cómo | Fase |
| --- | --- | --- | --- |
| 1 | Guardado atómico | Escribir a `media/<uuid>.part`, `fsync`, renombrar al nombre final y recién ahí insertar en la base dentro de una transacción. Si algo falla, no queda nada a medias. | 1 |
| 2 | Sin huérfanos | Al iniciar (en segundo plano): borrar `.part` viejos y archivos de `media/` sin fila; marcar en la UI filas cuyo archivo falte. | 1 |
| 3 | Códigos sin carreras | Generar e insertar directamente; si choca la clave primaria, reintentar con otro código. Nunca "verificar y después insertar". | 1 |
| 4 | Borrado seguro con deshacer | Borrado lógico con `deletedAt`; las consultas lo excluyen; la purga física corre después de la ventana de deshacer y también al iniciar. | 1 |
| 5 | Espacio lleno | Verificar espacio libre antes de importar (tamaño estimado + margen) y fallar con mensaje claro, sin escribir a medias. | 1 |
| 6 | Imágenes correctas | Aplicar la orientación EXIF al re-codificar; soportar HEIC/HEIF; si no se puede decodificar, guardar el original sin re-codificar. Videos y otros archivos se copian tal cual, con progreso y opción de cancelar. | 1 |
| 7 | Integridad | `sha256` calculado al importar. | 1 |
| 8 | El código se muestra solo si está guardado | Pantalla del código, vibración y código visible únicamente después del commit en la base. | 2 |
| 9 | Ninguna foto perdida en ráfaga | Cada foto se persiste al sacarla; el `Link` se crea con la primera. Si el proceso muere, al volver se retoma la sesión con lo ya guardado. | 2 |
| 10 | Compartir sin URIs vencidas | Copiar el archivo antes de soltar el intent recibido; si falla, avisar y no crear el vínculo. | 2 |
| 11 | Recurso abierto evidente | El visor muestra arriba el código y la etiqueta, para que un código mal leído se note sin agregar demoras. La apertura sigue siendo instantánea al cuarto carácter. | 3 |
| 12 | Respaldo verificable | Subir primero los archivos y al final el manifest; verificar `sha256` al restaurar; un respaldo cortado nunca queda como "el último". | 4 |
| 13 | Respaldo que no se cae en silencio | Mostrar fecha del último respaldo exitoso; aviso visible si pasaron más de 7 días o si se revocó el acceso a Drive. | 4 |
| 14 | Restaurar sobre datos existentes | Fusión por id de attachment; si un código existe con otro contenido, se agregan los attachments faltantes y no se pisa nada. | 4 |
| 15 | No perder todo al desinstalar | Después de los primeros 5 códigos, sugerir una vez activar el respaldo (descartable, sin obligar). | 4 |

**Matriz de pruebas de casos difíciles** (antes de cerrar cada fase, los que apliquen; todos en la fase 5):

- [ ] "No conservar actividades" activado durante cámara, compartir y respaldo.
- [ ] Almacenamiento casi lleno al importar fotos y videos.
- [ ] Modo avión y pérdida de red a mitad de un respaldo.
- [ ] Desinstalar, reinstalar y restaurar en instalación limpia.
- [ ] Acceso a Drive revocado desde la cuenta de Google.
- [ ] Foto HEIC, foto girada (EXIF), video de más de 1 GB.
- [ ] Dispositivo de gama baja o emulador con poca RAM.
- [ ] Build de release con R8.

## 9. Fases

### Fase 0 — Preparación
- [x] ~~Rama `rewrite/fase-0` desde `master`.~~ Omitido por decisión del usuario: se trabaja directo en `master`.
- [x] Sacar del índice de git `app/release/app-release.aab` (sigue en `.gitignore`); sacar `/app/schemas` del `.gitignore`.
- [x] Borrar `app/src/res/` (fuera de `main`), `.github/modernize/` y los esquemas viejos de `app/schemas/` (la base nueva arranca en la versión 3).
- [x] Mover `PaperLink_Vision.md`, `paperlink-contexto-desarrollo.md` y `Apps Grandes_ Problemas y Soluciones Simples.md` a `docs/archive/`.
- [x] Agregar las dependencias de test mínimas (JUnit ya está; Room testing y Robolectric solo si hacen falta) y un test de `CodeAlphabet`.
- **Criterio:** compila, tests en verde, repo limpio.
- **Notas:** `app/release/` no estaba en `.gitignore` pese a lo que decía el plan; se agregó. `app/schemas/` y `.github/modernize/` no estaban trackeados (solo se borraron del disco). El build regenera el esquema viejo `PaperLinkDatabase/2.json`; se borra a mano hasta la Fase 1, que arranca en la versión 3. Se agregó `CodeAlphabetTest` (8 tests) y se quitó `ExampleUnitTest`.

### Fase 1 — Base de datos y almacén
- [x] Confirmar la decisión del alfabeto (sección 4): 26 caracteres, ya aplicado en `CodeAlphabet` con sus tests.
- [x] Entidades y DAO (uno alcanza), base versión 3 con el descarte de 1 y 2.
- [x] `AttachmentStore` con tests (import de imagen con re-codificación, archivo genérico, borrado).
- [x] `LinkRepository`.
- [x] Función `log()` de debug.
- [x] Borrar el código viejo que quede sin uso.
- **Criterio:** instalada sobre la versión vieja, la app abre sin crashear y con la base vacía; tests en verde.
- **Verificación (23/10/2026):** instalada sobre la 0.1.0 (con datos) en un Pixel 8 (API 37): abre sin crashear, base en versión 3 y vacía, sin errores en logcat. 16 unit tests y 29 instrumentados en verde.
- **Notas y decisiones:**
  - UI vieja (Home, AddLink, TxtViewer, cámara del Home, banner de foto reciente) borrada. Queda una Home temporal que muestra la cantidad de códigos; se reemplaza en la fase 3. Se mantienen onboarding, tema y widget (su acción de cámara vuelve en la fase 2). Se quitó el `intent-filter` de `SEND` hasta la fase 2. Se borró `values-en/` (duplicaba a `values/`).
  - `AppContainer` pasó a ser una clase (la interfaz tenía una sola implementación). Tiene un `applicationScope` para mantenimiento y purgas diferidas.
  - Imágenes: un solo camino con `BitmapFactory` (con `inSampleSize`) y `androidx.exifinterface` para la orientación, en todas las versiones de API. HEIC/HEIF se decodifica desde API 28; si no se puede decodificar (o falta memoria) se guarda el original. Los GIF no se re-codifican (se perdería la animación). Las transparencias se componen sobre blanco.
  - Constantes: ventana de deshacer de 15 s (`LinkRepository.UNDO_WINDOW_MS`), margen de espacio libre de 50 MB y antigüedad mínima de 1 h para que el barrido borre algo (evita pisar una importación en curso).
  - La generación de código es `CodeAlphabet.generate()`; el repositorio inserta con `IGNORE` y reintenta si choca (incluye códigos con borrado lógico).
  - Los tests instrumentados usan base en memoria y un directorio temporal en `cacheDir`, sin tocar los datos reales. Se corren con `./gradlew installDebug installDebugAndroidTest` + `adb shell am instrument -w com.joasasso.paperlink.debug.test/androidx.test.runner.AndroidJUnitRunner`, porque `connectedDebugAndroidTest` desinstala la app al terminar.
  - `room-testing` no se agregó: todavía no hay migraciones. Entra con la primera.
  - Búsqueda por etiqueta: `LIKE` ignora mayúsculas solo en ASCII y no ignora tildes ("fisica" no encuentra "Física"). A resolver en la fase 3.
  - Pendiente de la matriz: probar una foto HEIC real y un video de más de 1 GB (fase 2, con la cámara y el compartir).

### Fase 2 — Captura
- [x] Pantalla de cámara con CameraX, multi-foto, permiso pedido en el momento.
- [x] Pantalla del código.
- [x] Widget y atajo del ícono que abren la cámara directo.
- [x] Recepción de `SEND` / `SEND_MULTIPLE` con elección de código nuevo o existente.
- [x] Agregar desde la app: Photo Picker, documentos, URL, nota.
- **Criterio (en dispositivo):** sacar 3 fotos, ver el código, cerrar la app; activar "No conservar actividades" en opciones de desarrollador y repetir sin perder fotos; compartir un link de YouTube y 2 fotos desde Google Fotos.
- **Verificación (23/10/2026, Pixel 8, API 37, con "No conservar actividades" activado):** 3 fotos en ráfaga → un código con 3 fotos. Proceso matado a mitad de sesión (2 fotos) → al volver la cámara retoma con 2 y el código termina con 3. Link de YouTube compartido y agregado a un código existente; "Listo" vuelve a la app de origen. 2 fotos compartidas desde Google Fotos, widget y atajo del ícono: OK (probado por el usuario). Video de 150 MB por compartir: OK. 21 unit tests y 44 instrumentados en verde; sin errores en logcat.
- **Notas y decisiones:**
  - Las fotos de la cámara se escriben en `filesDir/captures/` (fuera de `media/` y del barrido de 1 h) y se importan con `AttachmentStore` (re-codificación incluida). Las capturas de sesiones abandonadas se borran a los 7 días. El guardado corre en el `applicationScope`, así que salir de la cámara no lo cancela; "atrás" con fotos equivale a "Listo" para que el código siempre se muestre.
  - Lo compartido se copia a `media/` al llegar y se vincula con `AttachmentSource.Stored` (no se copia dos veces y no se borra si falla, para reintentar con otro código). Si el usuario sale sin elegir destino, el barrido lo borra pasada 1 h; con "Cancelar" se borra en el momento y se frena la copia en curso.
  - Texto compartido: si tiene links http(s), se guarda cada uno como link; si no, como nota.
  - Compartir y la cámara desde el widget/atajo reemplazan toda la pila de navegación; al terminar de compartir, "Listo" cierra la app y vuelve a la de origen. Compartir no pasa por el onboarding.
  - El atajo del ícono es dinámico (`ShortcutManagerCompat`), porque uno estático no admite el sufijo `.debug` del paquete.
  - Lo elegido desde la app (Photo Picker, documentos) se copia al elegirlo; si el proceso muere a mitad de la copia, hay que volver a elegirlo.
  - Corregido tras la prueba manual: al compartir no había botón "Cancelar" durante la copia y la X no frenaba la copia en curso.
  - Pendiente de la matriz: foto HEIC y video de más de 1 GB (el usuario no tenía archivos así); queda para la fase 5.

### Fase 3 — Recuperación
- [x] Home nueva: campo de código accesible (TalkBack, pegar), recientes, búsqueda por etiqueta, estado vacío.
- [x] Sugerencias de códigos parecidos.
- [x] Detalle del código: lista de attachments, editar etiqueta, agregar más, borrar con deshacer.
- [x] Visor de imágenes con zoom y paginado; abrir el resto con apps externas.
- [x] Edge-to-edge correcto.
- **Criterio:** con 200 vínculos de prueba, abrir por código en menos de 1 s desde arranque en frío; TalkBack puede ingresar un código.
- **Verificación (23/10/2026, Pixel 8, API 37):** con 203 códigos (200 de prueba vía `SeedReceiver`), arranque en frío 0,64–0,70 s y del cuarto carácter al visor ~0,19 s. La migración 3→4 corrió sobre los datos reales del usuario sin perder nada. Probado en el teléfono: código inexistente con sugerencias ("36OF" sugiere 36TF), visor con zoom por doble toque, detalle, borrar y deshacer un elemento, borrar y deshacer un código. 33 unit tests y 54 instrumentados en verde. **Pendiente: verificación con TalkBack por parte del usuario** (ver "Estado actual").
- **Notas y decisiones:**
  - Al completar un código existente se abre directo el **visor** (deslizable por todos los elementos); el detalle se abre desde el visor. El visor muestra arriba código y etiqueta (garantía 11).
  - Sugerencias (`domain/CodeSuggestions.kt`): códigos a un carácter de distancia; primero los pares confundibles a mano U/V, M/N, G/6, Q/9, T/7, A/4. Los caracteres fuera del alfabeto (O, 0, I, 1…) funcionan como comodín (hasta 2) y además muestran un aviso específico.
  - Borrar elementos sueltos: **primera migración real (v3 → v4)**, `attachments.deletedAt` con la misma ventana de deshacer y purga que los códigos. `@Relation` no filtra, así que el repositorio oculta los attachments borrados. `PaperLinkDatabase.VERSION` es la constante de versión que usan los tests. Se agregó `room-testing` y los esquemas se exponen como assets de `androidTest`.
  - Zoom con **telephoto** (`zoomable-image-coil3` 0.19.0): carga la imagen a resolución completa por partes al hacer zoom, clave para leer apuntes. Medido: agrega ~80 KB al APK de release (3,54 MB con, 3,46 MB sin).
  - Búsqueda por etiqueta en Kotlin (`domain/SearchText.kt`, sin tildes ni mayúsculas) sobre los códigos con etiqueta: sin cambio de esquema. Si algún día hay miles de códigos, considerar una columna normalizada.
  - Campo de código: 4 cajas sobre un único `BasicTextField(TextFieldState)` con `InputTransformation`. Pasar a mayúsculas en `onValueChange` desincronizaba el teclado y perdía caracteres al escribir rápido (en una prueba abrió otro código). Accesibilidad: `contentDescription` "Código" + `stateDescription` "N de 4 caracteres"; los códigos se leen letra por letra (`code_spoken`).
  - Recientes: los 50 últimos por fecha de modificación; el resto se encuentra por código o etiqueta. "Último código creado" (sección 6.2) = el primero de recientes.
  - Borrar un código pide confirmación y además ofrece "Deshacer" en la Home. Los snackbars de deshacer marcan el evento como atendido **después** de mostrarse (si no, se cancelaban al instante).
  - `AttachmentStore.fileFor` valida la ruta como texto (sin I/O), para usarse desde la UI al armar miniaturas. La disponibilidad real de archivos se calcula en `LinkRepository.observeLinkContent`.
  - `PdfDecoder` ya no tiene `catch` vacío (loguea). Edge-to-edge con `enableEdgeToEdge()`.
  - Herramienta de prueba solo en debug: `SeedReceiver` (`app/src/debug/`), carga/borra códigos con etiqueta `[seed]`.

### Fase 4 — Respaldo
- [x] Exportar e importar ZIP.
- [x] Confirmar `drive.appdata` vs `drive.file` (sección 7).
- [ ] Instrucciones para que el usuario configure Google Cloud (consentimiento, client IDs, SHA-1).
- [x] Autorización, subida incremental, restauración, pantalla de ajustes de respaldo.
- [x] `data_extraction_rules.xml`.
- **Criterio (en dispositivo):** respaldar, desinstalar, reinstalar, restaurar y ver todos los vínculos con sus archivos.
- **Verificación:**
  - **Criterio cumplido (26/10/2026, Pixel 8, probado por el usuario):** respaldar en Drive, desinstalar, reinstalar, restaurar y ver todos los códigos con sus archivos. Se probó con la versión anterior a la corrección de seguridad del respaldo; esa corrección no cambió la restauración (solo agrega que, al terminar, se guarda el id del manifest fusionado).
  - Después de la corrección (27/10/2026): 42 unit tests y 65 instrumentados en verde en el Pixel 8 (incluidos los 6 nuevos de `BackupTest`); la app abre sin errores en logcat.
  - Pendiente: "Ahora no" en el aviso de respaldo encontrado y las instrucciones de Google Cloud en el repo (ver "Estado actual").
- **Notas y decisiones:**
  - Decisión pendiente resuelta: **`drive.appdata`** (carpeta oculta de la app, scope no sensible). Cliente REST de Drive v3 con OkHttp, autorización con `Identity.getAuthorizationClient()`.
  - ZIP: `manifest.json` (formato versión 1) + `media/`; al importar se valida el manifest y las rutas, se verifica el SHA-256 de cada archivo antes del rename atómico y se fusiona por id en una transacción (`LinkDao.mergeBackup`: solo agrega, conserva la etiqueta local).
  - Drive: sube primero los archivos nuevos y al final el manifest; limpia manifests viejos y archivos huérfanos recién después (garantía 12). Ante 401 reintenta una vez y si sigue marca "volver a autorizar" (garantía 13). Respaldo diario con WorkManager, Wi-Fi y opcionalmente solo cargando.
  - **Corrección tras la revisión (26/10/2026): un respaldo nunca achica lo que hay en Drive.** Se guarda el id del último `manifest.json` que este teléfono subió o restauró (`syncedManifestId`). Si el de Drive es otro y tiene códigos, `backupNow` se niega (`RemoteNotMerged`), se marca `remotePending` y la Home muestra un aviso; al conectar se ofrece restaurar (fusionar) y el respaldo automático se activa recién después. "Ahora no" ya no respalda (antes subía la base vacía y borraba todo en Drive). Las filas cuyo archivo falta en el teléfono pero sigue en Drive se conservan (antes la limpieza lo borraba de Drive).
  - Sin respaldo automático de Android en la nube (`cloud-backup` excluye todo; `backup_rules.xml` igual para Android ≤ 11): restauraba la base y las preferencias sin las fotos, con "Drive activado", y el respaldo diario podía pisar Drive. La transferencia entre dispositivos (`device-transfer`) sí lleva base, `media/` y preferencias.
  - Un respaldo o restauración a la vez (`Mutex` en `DriveBackupManager`). Subida reanudable (`uploadType=resumable`) para cualquier tamaño, en vez de multipart (pensada para ≤ 5 MB). Un respaldo exitoso ya no reactiva el respaldo automático si el usuario lo apagó mientras corría, ni lo reprograma.
  - Garantía 13: si el respaldo está activo y nunca terminó bien, el aviso de 7 días cuenta desde que se activó (`driveEnabledAt`).
  - Garantía 15: sugerencia en la Home al llegar a 5 códigos sin respaldo, descartable.

### Fase 5 — Pulido y publicación
- [ ] Reglas de R8 revisadas y release probado en dispositivo.
- [ ] Revisar textos ES/EN, íconos, onboarding de una pantalla.
- [ ] Quitar `READ_MEDIA_*` y `READ_EXTERNAL_STORAGE` del manifest.
- [ ] Actualizar README.
- **Criterio:** `bundleRelease` instalado vía `bundletool` funciona de punta a punta.

## 10. Registro de decisiones

| Fecha | Decisión | Motivo |
| --- | --- | --- |
| 2026-10-23 | Reescritura sobre base nueva, reutilizando piezas | Problemas de base en almacenamiento y migraciones |
| 2026-10-23 | Enfoque minimalista + respaldo | La Vision v2 es otro producto |
| 2026-10-23 | Sin soporte de legado; la base arranca de cero | Solo la usaron el autor y un par de testers; no justifica código extra |
| 2026-10-23 | Garantías de confiabilidad (sección 8) | Lo poco que hace la app tiene que hacerlo perfecto |
| 2026-10-23 | Se mantiene la apertura instantánea al cuarto carácter; sin confirmación previa | La velocidad de uso es prioritaria; el visor muestra el código para detectar errores |
| 2026-10-23 | Balance simpleza / buenas prácticas: se mantienen capas, inyección y tests; se evita la indirección sin uso | Código simple pero mantenible y testeable |
| 2026-10-23 | Se trabaja directo en `master`, sin ramas `rewrite/fase-N` | No interesa conservar una rama con la versión anterior |
| 2026-10-23 | Alfabeto de 26 caracteres (sin I/1, O/0, Z/2, S/5, B/8) | Menos errores al leer códigos escritos a mano; 456.976 combinaciones sobran para una persona |
| 2026-10-23 | UI vieja borrada; Home temporal hasta la fase 3 | Adaptarla al modelo nuevo era trabajo descartable |
| 2026-10-23 | Tests de `AttachmentStore` y repositorio instrumentados (sin Robolectric) | La decodificación de imágenes y EXIF solo es fiel en un dispositivo real |
| 2026-10-23 | CameraX 1.6.2 con `camera-compose` | Visor nativo de Compose, sin `PreviewView` |
| 2026-10-23 | Lo compartido se copia antes de elegir destino | Las URIs recibidas pueden vencer; garantía 10 |
| 2026-10-23 | Al completar un código se abre el visor directo | Lo más rápido para el caso típico (fotos de apuntes); el detalle queda a un toque |
| 2026-10-23 | Pares confundibles U/V, M/N, G/6, Q/9, T/7, A/4 para sugerencias | Los más confundibles ya están fuera del alfabeto |
| 2026-10-23 | Borrar elementos sueltos con deshacer (esquema v4) | Caso real (foto equivocada); estrena el circuito de migraciones con test |
| 2026-10-23 | telephoto para el zoom | Carga a resolución completa por partes; ~80 KB medidos en release |
| 2026-10-25 | Guía única en `AGENTS.md`; soporte para Antigravity (`.agents/`) | El usuario sigue el proyecto con Antigravity; `CLAUDE.md` la importa |
| 2026-10-26 | Respaldo en Drive con `drive.appdata` | Carpeta oculta, scope no sensible; sin necesidad de ver los archivos desde la PC |
| 2026-10-26 | Un respaldo nunca achica Drive sin fusionar antes; sin Auto Backup de Android en la nube | Evita perder el respaldo al cambiar de teléfono o al tocar "Ahora no" |
