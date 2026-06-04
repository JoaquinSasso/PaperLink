# PaperLink

Fase 2.1 — capa de datos refactorizada para soportar contenido multimedia genérico.

## Estado actual

**Construido en Fase 1:**
- Esqueleto Gradle (Kotlin 2.4.0, Compose Compiler plugin, KSP, Room, Navigation, Coil 3)
- `MainActivity` con placeholder de Compose
- `PaperLinkApp` como Application class
- minSdk 26 / targetSdk 35 / compileSdk 35

**Construido en Fase 2 / 2.1:**
- `data/local/ContentType.kt` — enum con IMAGE, VIDEO, AUDIO, PDF, WEB_LINK, FILE + `TypeConverter` para Room
- `data/local/PaperLink.kt` — entity con `code` (PK), `contentType`, `contentUri`, `displayName`, `note`, `createdAt`, `subject`
- `data/local/PaperLinkDao.kt` — DAO con insert (ABORT), delete, getByCode, observeByCode, existsByCode, getRecent, count
- `data/local/PaperLinkDatabase.kt` — Room database v1 con `@TypeConverters(ContentTypeConverter::class)`
- `data/repository/PaperLinkRepository.kt` — repository que normaliza códigos a mayúsculas y fuerza Dispatchers.IO
- `di/AppContainer.kt` — contenedor manual con `paperLinkRepository`
- `PaperLinkApp.kt` — inicializa el container en `onCreate`

## Modelo de datos

```kotlin
@Entity(tableName = "paper_links")
data class PaperLink(
    @PrimaryKey val code: String,             // "HWQ2"
    val contentType: ContentType,             // IMAGE | VIDEO | AUDIO | PDF | WEB_LINK | FILE
    val contentUri: String,                   // content://... o https://...
    val displayName: String? = null,          // autocompletable
    val note: String? = null,
    val createdAt: Long,
    val subject: String? = null
)
```

## Cómo verificar que Fase 2.1 está OK

1. Sincronizar Gradle en Android Studio.
2. Hacer Build → Make Project. Debe compilar sin errores.
3. Correr la app: sigue mostrando "PaperLink — Fase 1 OK".
4. Verificar que aparece `app/schemas/com.joasasso.paperlink.data.local.PaperLinkDatabase/1.json` tras el primer build.

## Próxima fase

Fase 3: lógica de dominio.
- `GenerateCodeUseCase` con verificación de unicidad y alfabeto seguro (32 caracteres).
- Validación de formato de código de entrada (longitud, alfabeto válido).
- Constantes del alfabeto en un único lugar (`domain/CodeAlphabet.kt`).
