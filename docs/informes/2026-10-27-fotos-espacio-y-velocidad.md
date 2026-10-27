# Fotos: espacio en el respaldo y velocidad de carga

| | |
| --- | --- |
| **Fecha** | 27/10/2026 |
| **Estado** | Resuelto y verificado en el teléfono |
| **Áreas** | `AttachmentStore`, `LinkRepository`, `BatchImporter` (nuevo), pantallas Home, código y detalle |
| **Dispositivo de prueba** | Pixel 8 (API 37) |
| **Commits** | `0e4e45f`, `69b4f3f`, `7f58d6f`, `642dcc0`, `d61302e`, `5491e9e`, `0ec86b0`, `750af1e` |

## Resumen

Las fotos son el contenido principal de PaperLink y tenían dos problemas que se notaban con el uso real:

1. **Ocupaban mucho.** Unos 590 KB por foto de cuaderno: 400 fotos serían ~230 MB en el teléfono y otro tanto en Google Drive.
2. **Cargar varias desde la galería era lento.** 25 fotos tardaban entre 15 y 25 segundos, y el código no aparecía hasta el final.

Después de medir antes de decidir, quedaron así:

| | Antes | Después |
| --- | --- | --- |
| Peso promedio de una foto de cuaderno | 590 KB (JPEG 3000 px q85) | 124 KB (WebP 2400 px q70), **−79 %** |
| 400 fotos | ~230 MB | ~49 MB |
| Código visible al cargar 25 fotos de la galería | al terminar (15–25 s) | **9 ms** |
| 25 fotos guardadas | 15–25 s | **4,2 s** |

En el camino, los tests nuevos encontraron **cuatro bugs reales**, uno de ellos capaz de dejar una fila en la base sin su archivo, que es justo lo que la garantía 1 prohíbe. Todos quedaron corregidos y cubiertos por tests.

## Línea de tiempo

| Hora (27/10) | Hecho |
| --- | --- |
| ~00:00 | Detectamos que con 17 fotos el respaldo en Drive ya ocupaba 7 MB y planteamos la hipótesis inicial de comprimirlo (por ejemplo, en un ZIP). |
| ~00:05 | Medición: comprimir en ZIP ahorraría apenas 0,25 %. Lo descartamos. |
| ~00:10 | Prueba de formatos con nuestras fotos reales de estudio: JPEG, WebP y HEIC con distintas calidades y tamaños. |
| ~00:30 | Decisión del equipo: WebP, lado mayor 2400 px, calidad 70. Implementado y probado (`0e4e45f`). |
| ~00:40 | Al probar la carga de 25 fotos desde la galería, notamos que se sentía muy lenta. |
| ~00:45 | Benchmark por etapas y de paralelismo en el Pixel 8. |
| ~00:50–01:05 | Implementamos la carga en segundo plano (3 a la vez, código tras la primera foto). Los tests nuevos encuentran 4 bugs; los corregimos (`7f58d6f`, `642dcc0`, `d61302e`). |
| ~01:08 | Prueba en el dispositivo: código a los 0,64 s, 25 fotos en 4,7 s. Observamos que "las primeras 3 tardan mucho más". |
| ~01:10 | Análisis: no son más lentas, es el llenado de la cola en paralelo. Decidimos crear el código vacío al instante. |
| ~01:22 | Prueba final en el dispositivo: código a los 9 ms, 25 fotos en 4,2 s, sin errores (`0ec86b0`, `750af1e`). |

## Parte 1: espacio

### El problema

Con 17 fotos el respaldo ya ocupaba 7 MB. Extrapolando a un uso de varios meses (400 fotos), el respaldo ocuparía cientos de MB de los 15 GB gratuitos de Google Drive, y lo mismo en el teléfono. Con videos, mucho más.

### Hipótesis 1: comprimir el respaldo (ZIP)

Nuestra primera idea fue comprimir los archivos en un ZIP antes de subirlos. Lo medimos directamente en el teléfono, con gzip al máximo sobre las fotos guardadas:

| | Bytes |
| --- | --- |
| 15 fotos guardadas (JPEG) | 7.087.453 |
| Las mismas, comprimidas con gzip -9 | 7.069.930 |
| Ahorro | **0,25 %** |

JPEG (y los videos) ya están comprimidos: un compresor genérico no tiene qué sacar. Además, meter todo en un ZIP rompería el respaldo incremental (hoy cada foto se sube una sola vez). **Descartada.**

La conclusión importante: el ahorro tenía que venir de **guardar las fotos más chicas al importarlas**, lo que achica a la vez el espacio en el teléfono y el respaldo.

### Hipótesis 2: otro formato o tamaño

Armamos un experimento temporal (test instrumentado, no commiteado) que codificaba las mismas fotos con los **codificadores del propio teléfono** en varias variantes, medía el tamaño y generaba recortes ampliados de zonas con letra para comparar la legibilidad. Probamos con un lote real de 25 fotos: hojas de cuaderno en lápiz y birome, un parcial impreso, pizarrones y una pantalla proyectada.

| Variante | Promedio | Frente a lo de antes | 400 fotos | Codificación |
| --- | --- | --- | --- | --- |
| JPEG 3000 q85 (antes) | 590 KB | — | ~230 MB | ~0,06 s |
| JPEG 2400 q80 | 339 KB | −42 % | ~133 MB | ~0,04 s |
| WebP 3000 q80 | 255 KB | −57 % | ~100 MB | ~0,6 s |
| WebP 3000 q70 | 186 KB | −69 % | ~72 MB | ~0,6 s |
| WebP 2400 q80 | 167 KB | −72 % | ~65 MB | ~0,4 s |
| **WebP 2400 q70** | **124 KB** | **−79 %** | **~49 MB** | ~0,4 s |
| HEIC 2400 q30 | 703 KB | +19 % | ~275 MB | ~0,3 s |
| HEIC 2400 q50 | 874 KB | +48 % | ~341 MB | ~0,3 s |
| HEIC 2400 q70 | 928 KB | +57 % | ~363 MB | ~0,3 s |
| HEIC 2400 q90 | 914 KB | +55 % | ~357 MB | ~0,3 s |
| HEIC 3000 q50 | 1018 KB | +73 % | ~397 MB | ~0,3 s |

**Legibilidad:** en los recortes ampliados (como al hacer zoom en el visor) la letra se lee en todas las variantes WebP, incluido el lápiz chico. Las de 2400 px se ven apenas más suaves; con q70 aparece algo de escalonado en los reflejos del pizarrón, no en el trazo.

**HEIC:** en teoría ocupa menos que WebP, pero Android solo permite generarlo con `HeifWriter`, que usa el codificador de video por hardware. En el Pixel ese codificador casi ignora la calidad pedida (q70 y q90 pesan lo mismo), da archivos más pesados que el JPEG y su comportamiento depende del modelo del teléfono. Además suma una dependencia y no se abre en Android 8 y 9. **Descartado.**

### Decisión e implementación

- **WebP con pérdida, lado mayor 2400 px, calidad 70** (elegida buscando el máximo ahorro sin perder legibilidad en trazos de lápiz).
- En Android 11+ se usa `WEBP_LOSSY`; antes, `WEBP`, que con calidad menor a 100 también es con pérdida.
- Aplica a las fotos nuevas; las ya guardadas quedan en JPEG (recomprimirlas cambiaría sus huellas SHA-256 y obligaría a volver a subirlas).
- Test nuevo: importar una imagen que ya es WebP no pisa el original con el re-codificado (el mismo tipo de bug que encontramos en la fase 2 con JPEG, cuando el `.part` del original y el del re-codificado tenían el mismo nombre).

### Costo aceptado

Codificar WebP es ~10 veces más lento que JPEG (0,4 s contra 0,04 s por foto). Ese costo es el que hizo visible el problema de la parte 2.

## Parte 2: velocidad de carga

### El problema

Al cargar 25 fotos desde la galería, la app quedaba esperando con un diálogo de progreso. Las fotos se importaban de a una y el código recién aparecía al final.

### Medición

Benchmark temporal en el teléfono con 8 fotos del tamaño de las de la cámara (4080×3072, ~4,8 MB, con rotación EXIF):

| Etapa (por foto, de a una) | Tiempo |
| --- | --- |
| Copiar el original + fsync | 42 ms |
| Decodificar | 134 ms |
| Rotar y escalar | 156 ms |
| **Codificar WebP + fsync** | **699 ms** |
| Total | ~1,03 s |

- Decodificar con `ImageDecoder` directo al tamaño final casi no ahorró tiempo (974 ms contra 1032 ms), pero usa mucha menos memoria.
- WebP se lleva el 70 % del tiempo y Android no permite elegir un modo más rápido.

| En paralelo | Tiempo efectivo por foto |
| --- | --- |
| 1 | 1066 ms |
| 2 | 551 ms |
| **3** | **417 ms** |
| 4 | 367 ms |

Las imágenes de prueba tenían ruido a propósito (peor caso para WebP); con fotos reales cada una tarda ~0,6 s en paralelo.

### Decisión e implementación (primera versión)

- **3 fotos a la vez** (1 en teléfonos con poca RAM): más allá de 3 la ganancia es chica y suben memoria y batería.
- **`ImageDecoder` con tamaño final** en Android 9+, para que 3 a la vez no se queden sin memoria (además aplica la rotación EXIF sola).
- **Sin `fsync` de la copia temporal** del original cuando se va a re-codificar.
- **Carga progresiva** con un componente nuevo, `BatchImporter`: el código se creaba con la primera foto y su pantalla se abría enseguida; el resto se agregaba en segundo plano, en el orden elegido, con una tarjeta de progreso y opción de cancelar.
- **Aviso si la app se cierra a mitad:** la carga queda anotada en preferencias; al volver, la Home avisa cuántos elementos se guardaron. (Retomar sola la carga necesitaría permisos persistentes de las fotos y una cola, y no funcionaría con lo que llega por compartir; la ventana de riesgo es de pocos segundos.)

### Resultado de la primera versión

Prueba en el dispositivo con las mismas 25 fotos (logs de debug `Batch: …`):

- Código visible a los **0,64 s**.
- 25 fotos guardadas en **4,7 s** (antes, 15–25 s).

Durante la prueba observamos que "las primeras 3 fotos tardaron mucho más que el resto". Las marcas de tiempo de los archivos mostraron que no eran más lentas: las 3 primeras terminaron juntas a los ~0,6 s y después terminó una cada ~0,2 s. Es el llenado de una cola en paralelo: la primera tanda tarda una foto entera; desde ahí, el ritmo es 3 veces más rápido.

### Ajuste: código al instante

Evaluamos dos opciones: darle prioridad a la primera foto (código en ~0,4 s) o **crear el código vacío antes de importar nada** (código en milisegundos). Optamos por la segunda, ajustando la garantía 8: el código se sigue mostrando solo cuando está guardado en la base, pero puede estar unos segundos sin fotos mientras se importan en segundo plano.

- Si no se puede guardar ninguna foto, el código queda (ya está anotado en papel) y la tarjeta avisa que se vuelvan a agregar con "Agregar más".
- Los códigos vacíos se incluyen en el respaldo, para que sigan reservados después de restaurar.
- La cámara sigue creando el código con la primera foto (ahí el código aparece apenas se termina de guardar).

**Resultado:** código a los **9 ms** y las 25 fotos guardadas en **4,2 s**, sin errores. El total también bajó un poco porque ya no hay una foto que espere sola antes de que arranquen las demás.

## Bugs encontrados por los tests

Los tests instrumentados nuevos de `BatchImporter` corren en el teléfono con archivos reales. Varios fallaron de forma intermitente; para diagnosticarlos se repitieron entre 5 y 20 veces seguidas y se leyó logcat de cada corrida.

### 1. Carrera al crear la carpeta `media/`

- **Cómo apareció:** el test `firstItemFailing_letsTheNextOneCreateTheCode` guardaba 1 foto en lugar de 2. En logcat, `IOException: Cannot create …/files/media`.
- **Causa:** si `media/` todavía no existe, las 3 importaciones simultáneas intentan crearla. `mkdirs()` devuelve `false` en las que pierden la carrera (la carpeta ya existía) y el código lo trataba como error.
- **Impacto:** en una instalación nueva, cargar varias fotos como primera acción habría perdido fotos.
- **Corrección:** fallar solo si, después de intentar crearla, la carpeta sigue sin existir (`mkdirs() || isDirectory`), en los tres lugares donde se crean carpetas (`7f58d6f`).

### 2. Fila en la base sin su archivo al cancelar (garantía 1)

- **Cómo apareció:** el test de cancelación fallaba 2 de cada 3 veces: había una fila cuyo archivo no existía.
- **Causa:** una operación de Room puede **confirmar la transacción y aun así lanzar la cancelación** al volver al código que la llamó (garantía de cancelación inmediata de las corrutinas). El código interpretaba esa cancelación como "no se guardó" y borraba el archivo.
- **Impacto:** violaba la garantía 1 (nunca una fila sin archivo). El mismo patrón existía, latente, en los caminos ya existentes de cámara, compartir y restauración.
- **Corrección:** un archivo importado solo se borra si **ninguna fila lo referencia** (`LinkRepository.discardUnreferenced`, con la consulta nueva `LinkDao.isFileReferenced`). Queda correcto por construcción, sin depender de cuándo llega la cancelación, y protege todos los caminos (`7f58d6f`).

### 3. Carga "en curso" para siempre

- **Cómo apareció:** el test de cancelación se colgaba esperando que la carga terminara (timeout de 30 s) en 2 de 15 corridas.
- **Causa:** la primera escritura de la anotación en preferencias estaba **antes** del `try` del trabajo en segundo plano. Si la cancelación llegaba justo ahí, no se ejecutaba el `finally`: el estado quedaba "en curso" para siempre y la anotación nunca se borraba.
- **Impacto:** la tarjeta de progreso no desaparecía y, al reiniciar, la Home avisaba una carga cortada que no lo estaba.
- **Corrección:** mover esa escritura dentro del `try` (`642dcc0`). Después: 20 de 20 corridas bien.

### 4. Falso aviso de "carga cortada"

- **Cómo apareció:** 1 de 10 corridas encontraba la anotación todavía presente después de terminar la carga.
- **Causa:** al terminar, se sacaba el estado "en curso" **antes** de borrar la anotación. En ese instante la Home veía una carga anotada que no estaba en curso.
- **Impacto:** la Home podía mostrar por un momento un aviso falso de "carga cortada".
- **Corrección:** invertir el orden: primero se borra la anotación y después el estado (`642dcc0`).

### Caso conocido, no corregido a propósito

Si la cancelación llega justo cuando una importación ya escribió su archivo pero antes de devolverlo, el archivo queda sin fila. No hay forma limpia de evitarlo sin impedir que la cancelación frene copias grandes. Lo resuelve el **barrido de huérfanos** que corre al iniciar la app (borra archivos sin fila con más de 1 hora). El test lo verifica simulando ese barrido.

## Verificación

- **Unit tests:** 42 en verde.
- **Instrumentados:** 77 en verde en el Pixel 8, incluidos los nuevos:
  - `BatchImporterTest`: orden de llegada, código antes que sus elementos, elementos que fallan, ninguno importable, agregar a un código existente, cancelar (sin filas sin archivo y sin huérfanos tras el barrido).
  - `AttachmentStoreTest`: WebP y tamaño máximo, WebP de origen sin pisarse, foto grande rotada escalada sin deformarse.
  - `LinkRepositoryTest`: código vacío, choque de códigos vacíos, código vacío incluido en el respaldo.
- **Estabilidad:** el test de cancelación pasó 20 de 20 veces seguidas, y `BatchImporterTest` completo, 5 de 5.
- **En el teléfono (prueba en vivo):** 25 fotos reales de la galería, código a los 9 ms, todo guardado en 4,2 s, sin errores ni crashes en logcat.

## Qué salió bien

- **Medir antes de decidir.** La idea intuitiva (comprimir en un ZIP) habría ahorrado 0,25 %. La prueba de formatos con fotos reales nos llevó a −79 %.
- **Usar fotos reales del caso de uso** (cuadernos, pizarrones) y comparar la legibilidad con recortes ampliados, no solo el tamaño.
- **Tests en el dispositivo real** con archivos reales: encontraron 4 bugs de concurrencia y cancelación que no se ven en una prueba manual.
- **Repetir los tests intermitentes** hasta reproducir la falla, en lugar de reintentar hasta que pasen.

## Qué se puede mejorar

- **El agente descartó HEIC en silencio en la primera ronda:** se probó solo con 2 fotos de calibración y no se incluyó en la prueba con las 25 fotos reales sin avisar. Al revisarlo en equipo se detectó la omisión y se corrió la batería completa. Lección: documentar todas las variantes probadas, también las descartadas, con sus números.
- **El benchmark de velocidad usó imágenes sintéticas** (peor caso para WebP). Sirvió para comparar alternativas, pero los tiempos absolutos vinieron de nuestra prueba real con las 25 fotos.
- **El patrón "borrar el archivo si la operación falló"** estaba en el código desde la fase 1 sin contemplar que una cancelación puede llegar después de confirmar. Revisar este patrón en cualquier código nuevo que combine archivos y base.

## Pendientes

- **Compartir desde otra app:** sigue copiando y re-codificando todo antes de elegir el destino. Siguiente paso: copiar crudo (~40 ms por foto) y re-codificar en segundo plano después de elegir.
- **Videos:** donde está el mayor ahorro de espacio. Requiere definir la dependencia (Media3 Transformer) y si ofrecemos una opción en ajustes.
- **Matriz de casos difíciles:** probar una foto HEIC real y un video de más de 1 GB.
- **Fotos viejas en JPEG:** no se recomprimen.
