# PaperLink — Plan de reescritura

Origen: auditoría del 23/10/2026. Este documento es la especificación de trabajo; mantenerlo actualizado (checklists y decisiones) al cerrar cada fase.

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
    @PrimaryKey val code: String,      // "HWQ2", siempre en mayúsculas
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

- Alfabeto actual: `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (32 caracteres, sin I/O/0/1).
- **Decisión pendiente (confirmar con el usuario en la fase 1):** pasar a un alfabeto que además excluya pares confundibles a mano (`Z`/`2`, `S`/`5`, `B`/`8`) → 26 caracteres, unas 457.000 combinaciones. Como no hay datos viejos que conservar, validación y generación usan el mismo alfabeto.
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
- [ ] Confirmar la decisión del alfabeto (sección 4).
- [ ] Entidades y DAO (uno alcanza), base versión 3 con el descarte de 1 y 2.
- [ ] `AttachmentStore` con tests (import de imagen con re-codificación, archivo genérico, borrado).
- [ ] `LinkRepository`.
- [ ] Función `log()` de debug.
- [ ] Borrar el código viejo que quede sin uso.
- **Criterio:** instalada sobre la versión vieja, la app abre sin crashear y con la base vacía; tests en verde.

### Fase 2 — Captura
- [ ] Pantalla de cámara con CameraX, multi-foto, permiso pedido en el momento.
- [ ] Pantalla del código.
- [ ] Widget y atajo del ícono que abren la cámara directo.
- [ ] Recepción de `SEND` / `SEND_MULTIPLE` con elección de código nuevo o existente.
- [ ] Agregar desde la app: Photo Picker, documentos, URL, nota.
- **Criterio (en dispositivo):** sacar 3 fotos, ver el código, cerrar la app; activar "No conservar actividades" en opciones de desarrollador y repetir sin perder fotos; compartir un link de YouTube y 2 fotos desde Google Fotos.

### Fase 3 — Recuperación
- [ ] Home nueva: campo de código accesible (TalkBack, pegar), recientes, búsqueda por etiqueta, estado vacío.
- [ ] Sugerencias de códigos parecidos.
- [ ] Detalle del código: lista de attachments, editar etiqueta, agregar más, borrar con deshacer.
- [ ] Visor de imágenes con zoom y paginado; abrir el resto con apps externas.
- [ ] Edge-to-edge correcto.
- **Criterio:** con 200 vínculos de prueba, abrir por código en menos de 1 s desde arranque en frío; TalkBack puede ingresar un código.

### Fase 4 — Respaldo
- [ ] Exportar e importar ZIP.
- [ ] Confirmar `drive.appdata` vs `drive.file` (sección 7).
- [ ] Instrucciones para que el usuario configure Google Cloud (consentimiento, client IDs, SHA-1).
- [ ] Autorización, subida incremental, restauración, pantalla de ajustes de respaldo.
- [ ] `data_extraction_rules.xml`.
- **Criterio (en dispositivo):** respaldar, desinstalar, reinstalar, restaurar y ver todos los vínculos con sus archivos.

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
