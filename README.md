# PaperLink

**El vínculo entre tu cuaderno de papel y tus archivos digitales.**

PaperLink es una app nativa de Android (Kotlin + Jetpack Compose) que resuelve un problema muy concreto para cualquier estudiante: sacás una foto del pizarrón, grabás un audio de la clase, guardás un PDF o un link de YouTube... y semanas después, cuando estás estudiando con tu cuaderno de papel en la mano, no tenés forma rápida de encontrar ese recurso digital que corresponde a un ejercicio puntual.

PaperLink genera un **código corto de 4 caracteres** para cada recurso que guardás. Ese código lo anotás a mano junto al apunte en el cuaderno. Cuando necesitás volver a ver ese recurso, abrís la app, tipeás el código y listo: la foto, el video, el audio, el PDF o el link aparece al instante.

## Por qué existe

Notion, Google Keep, OneNote o Google Fotos organizan contenido digital, pero ninguna de esas herramientas sabe que el código `HWQ2` está escrito a mano en la página 34 de tu cuaderno de Sistemas Operativos. Ese vínculo entre el mundo físico y el digital es exactamente lo que PaperLink construye, y es lo único que hace: no hay notas largas, no hay organización por materias, no hay sincronización en la nube. Una función, hecha bien.

## Cómo se usa

**Guardar un recurso**
1. Abrís PaperLink (o tocás el widget de pantalla de inicio, que va directo a la cámara).
2. Elegís qué tipo de contenido querés vincular: foto, video, audio, PDF, archivo, link web o una nota de texto escrita en el momento.
3. Seleccionás el archivo (selector nativo o cámara) o pegás la URL.
4. La app genera un código único de 4 caracteres y lo guarda.
5. Anotás ese código en el cuaderno, junto al ejercicio o apunte correspondiente.

**Recuperar un recurso**
1. Abrís la app. El campo de código ocupa el centro de la pantalla — es lo primero que ves.
2. Tipeás las 4 letras/números del cuaderno.
3. Apenas se completa el código, PaperLink busca la coincidencia y abre el recurso automáticamente, sin necesidad de tocar nada más.

## Estado actual

El proyecto ya superó ampliamente la etapa de esqueleto. Hoy funciona de punta a punta: se puede generar un código, vincularlo a cualquier tipo de contenido, guardarlo, buscarlo y abrirlo, con permisos y URIs manejados de forma robusta. El repositorio incluye un build de release (`app-release.aab`, versión `0.1.0`) generado con Proguard/R8 activado, y la app está a punto de publicarse en Google Play.

## Funcionalidades implementadas

**Seis tipos de contenido, un solo flujo**
IMAGE, VIDEO, AUDIO, PDF, WEB_LINK y FILE se guardan como una `content://` URI persistente (vía Storage Access Framework, sin copiar el archivo original). Además existe TEXT_NOTE: una nota de texto escrita nativamente dentro de la app, para cuando el recurso a vincular es una idea y no un archivo.

**Tres canales para capturar contenido**
- Selector de archivos del sistema (SAF) para elegir algo que ya existe en el dispositivo.
- Botón de cámara que dispara la cámara nativa y vincula la foto apenas se toma.
- Recepción de contenido compartido desde otras apps (`ACTION_SEND` para imágenes, videos y PDFs), para vincular sin salir de la app de origen.

Además, la Home detecta si sacaste una foto en los últimos 5 minutos con la cámara del sistema y te ofrece vincularla con un toque, mediante un banner deslizable que también se puede descartar.

**Códigos pensados para escribirse a mano**
El alfabeto excluye caracteres que se confunden al copiar a mano o a la vista (`I`, `O`, `0`, `1`), dejando 32 caracteres válidos y 4 posiciones: alrededor de un millón de combinaciones posibles, más que suficiente para uso personal. Cada código se verifica contra la base de datos antes de asignarse, con reintentos automáticos en caso de colisión.

**Pantalla principal "visual-first"**
El campo de entrada de código es grande, monoespaciado y ocupa el centro de la pantalla, con feedback visual de cursor y vibración cuando el código ingresado no existe. Debajo, una grilla muestra todos los recursos guardados como miniaturas con el código superpuesto; mantener presionada una tarjeta permite borrarla, con confirmación previa.

**Miniaturas inteligentes**
- Los PDF muestran como miniatura la primera página real del documento, gracias a un decoder propio integrado en Coil 3 (`PdfDecoder`) que renderiza la página con `PdfRenderer`.
- Los links de YouTube muestran automáticamente el thumbnail oficial del video, detectando el ID por regex sin necesidad de scraping ni llamadas a la API de YouTube.

**Visor de texto integrado**
Los recursos de tipo nota o archivos `.txt`/`.md` no se abren con una app externa: tienen su propia pantalla dentro de PaperLink, con modo lectura y modo edición, y guardado de cambios.

**Manejo robusto de URIs y permisos**
Cuando la fuente del contenido es la galería del sistema, se solicita permiso persistente para que el vínculo siga funcionando aunque el usuario mueva o reorganice sus fotos. Cuando la fuente es un proveedor de contenido efímero (por ejemplo, Google Fotos vía "compartir"), la app copia el archivo a almacenamiento interno automáticamente para no perder el recurso cuando ese proveedor deje de ofrecer la URI original.

**Widget de acceso directo**
Un widget de pantalla de inicio abre la cámara del sistema con un solo toque, para capturar el recurso sin pasar por el menú de la app.

**Onboarding e idiomas**
Un tutorial deslizable se muestra en el primer inicio (con opción de saltarlo u volver a verlo desde la Home), y toda la interfaz está preparada en español e inglés.

## Stack técnico

| Área | Tecnología |
|---|---|
| Lenguaje | Kotlin |
| UI | Jetpack Compose + Material Design 3 |
| Arquitectura | MVVM + Repository, inyección de dependencias manual (sin Hilt) |
| Persistencia | Room (datos) + DataStore Preferences (preferencias de usuario) |
| Navegación | Navigation Compose con rutas tipadas (`kotlinx.serialization`) |
| Imágenes/video | Coil 3, con decoder propio para miniaturas de PDF |
| Concurrencia | Coroutines + Flow |
| minSdk / target / compileSdk | 26 / 37 / 37 |

## Estructura del proyecto

```
app/src/main/java/com/joasasso/paperlink/
├── data/
│   ├── local/            # Entity PaperLink, ContentType, DAO y Room database
│   ├── preferences/      # DataStore: primer inicio, fotos descartadas del banner
│   └── repository/       # PaperLinkRepository: normaliza códigos, fuerza Dispatchers.IO
├── domain/
│   ├── CodeAlphabet.kt        # Alfabeto, validación y normalización de códigos
│   └── GenerateCodeUseCase.kt # Generación de código único con verificación de colisión
├── di/
│   └── AppContainer.kt   # Contenedor manual de dependencias
├── ui/
│   ├── screens/
│   │   ├── home/         # Pantalla principal: buscador + grilla + captura
│   │   ├── add/          # Selección de tipo de contenido y guardado
│   │   ├── onboarding/   # Tutorial de primer inicio
│   │   └── txt/          # Visor/editor de notas y archivos de texto
│   ├── components/       # Miniaturas, iconos por tipo de contenido
│   ├── navigation/       # Grafo de navegación con rutas tipadas
│   └── theme/            # Material 3, tipografía, colores
├── util/
│   ├── PdfDecoder.kt      # Decoder de Coil 3 para miniaturas de PDF
│   └── YouTubeUtils.kt    # Extracción de thumbnail de YouTube
└── widget/
    └── PaperLinkWidgetProvider.kt   # Widget de acceso directo a la cámara
```

## Modelo de datos

```kotlin
@Entity(tableName = "paper_links")
data class PaperLink(
    @PrimaryKey val code: String,             // "HWQ2"
    val contentType: ContentType,             // IMAGE | VIDEO | AUDIO | PDF | WEB_LINK | FILE | TEXT_NOTE
    val contentUri: String,                   // content://... o https://...
    val displayName: String? = null,          // reservado, aún no expuesto en la UI
    val note: String? = null,                 // reservado, aún no expuesto en la UI
    val createdAt: Long,
    val subject: String? = null               // reservado; la organización por materias fue descartada del alcance actual
)
```

```kotlin
object CodeAlphabet {
    const val ALLOWED_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 32 caracteres, sin I/O/0/1
    const val CODE_LENGTH = 4                                        // 32^4 ≈ 1.048.576 combinaciones
}
```

## Cómo compilar el proyecto

1. Cloná el repositorio y abrilo en Android Studio (versión reciente, compatible con Kotlin 2.x y el plugin de Compose Compiler).
2. Dejá que Android Studio sincronice Gradle; no requiere configuración adicional ni claves de API.
3. Corré el módulo `app` en un emulador o dispositivo con Android 8.0 (API 26) o superior.
4. Para generar un build de release firmado propio, vas a necesitar tu propio keystore; el `.aab` incluido en `app/release/` corresponde al build del autor.

## Principios de diseño

Estos criterios guían las decisiones de producto del proyecto:

- **Offline-first**: la app funciona 100% sin conexión; no hay llamadas a red en el flujo core.
- **Velocidad sobre estética**: la respuesta a cualquier acción del usuario debe sentirse instantánea, sin transiciones que agreguen fricción.
- **Una función, bien hecha**: vincular recursos con códigos cortos. Todo lo que no aporta directamente a eso queda fuera de alcance.
- **UI de alta densidad informativa**: botones grandes, código en tipografía monoespaciada grande para facilitar copiarlo a mano.

## Lo que quedó fuera (a propósito)

Algunas ideas del diseño original fueron evaluadas y descartadas conscientemente para no diluir el propósito de la app: la organización por materias/subjects y una pantalla de detalle independiente para cada recurso. El campo `subject` sigue en el modelo de datos por si se retoma más adelante, pero hoy no tiene ningún efecto en la UI.

Ideas que siguen abiertas como posibles próximos pasos, sin comprometerse a un orden: reconocimiento del código escrito a mano en el cuaderno (OCR), respaldo/exportación de la base de datos, y sincronización opcional entre dispositivos.

## Licencia

Este proyecto está bajo licencia [MIT](LICENSE): el código es completamente libre, se puede usar, copiar, modificar y distribuir sin restricciones, siempre citando la licencia original.
