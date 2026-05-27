# PaperLink — Documento de Contexto para Desarrollo con IA

**Propósito**: Este documento contiene toda la información necesaria para que un agente o chat IA asista en la construcción de PaperLink desde cero. Incluye el problema que resuelve, el caso de uso real, las decisiones de arquitectura, el stack técnico, el MVP del día 1 y los principios de diseño que deben respetarse en todo momento.

---

## 1. Quién soy y mi stack

Soy un desarrollador Android nativo con experiencia real en producción. Mi stack principal es:

- **Lenguaje**: Kotlin
- **UI**: Jetpack Compose + Material Design 3
- **Arquitectura**: MVVM + Clean Architecture + Patrón Repositorio
- **Persistencia local**: Room / DataStore
- **Sin backend por ahora**: la app es 100% local, offline-first

Tengo experiencia publicando en Play Store (app MiniToolbox, 5,0/5 con 23 reseñas). Sé configurar Gradle, manejar dependencias, trabajar con coroutines y Flow. No necesito explicaciones básicas de Android.

---

## 2. El problema que resuelve PaperLink

### El dolor real (caso de uso concreto)

Soy estudiante universitario de Ciencias de la Computación. En clase, los profesores resuelven ejercicios complejos en el pizarrón (por ejemplo, diseñar una máquina de Turing). No puedo copiarlos a mano porque:

- Si copio mientras el profesor explica, pierdo el hilo de la explicación
- Si copio al final, el profesor ya borró el pizarrón y empezó otro ejercicio

La solución natural es sacar una foto. **El problema**: esa foto queda suelta en la galería del teléfono sin contexto. Semanas después, cuando estoy estudiando, no sé a qué clase pertenece, a qué ejercicio hace referencia, ni cómo encontrarla rápido.

### Lo que PaperLink resuelve

PaperLink crea un **vínculo entre el mundo físico (cuaderno) y el mundo digital (foto)**:

1. Saco la foto del pizarrón (con la cámara nativa del teléfono, que es más rápida)
2. Entro a PaperLink, selecciono la foto de la galería
3. La app genera un **código único de 4 caracteres** (ej: `HWQ2`)
4. Escribo ese código en mi cuaderno junto al apunte: `"Ejercicio 2 → HWQ2"`
5. Semanas después, cuando estoy estudiando, veo `HWQ2` en el cuaderno
6. Abro PaperLink, ingreso `HWQ2`, y la app abre exactamente esa foto

**La app no se usa bajo presión en el examen.** Se usa en dos momentos tranquilos: al asociar la foto justo después de clase, y al recuperarla mientras estudiás.

### Por qué otras apps no resuelven esto

- **Notion, Google Keep, OneNote**: organizan contenido digital, pero no saben que `HWQ2` está escrito en un cuaderno físico. No hay vínculo analógico-digital.
- **Google Fotos**: no permite asociar códigos cortos personalizados a fotos específicas para búsqueda instantánea.
- **Anki**: es para flashcards de memoria, no para vincular recursos visuales con apuntes en papel.

---

## 3. MVP — Día 1 (lo único que se construye ahora)

El MVP tiene exactamente dos flujos. Nada más.

### Flujo A: Asociar foto a código

1. Usuario abre la app
2. Toca "Nueva referencia"
3. Selecciona una foto de la galería del teléfono
4. La app genera automáticamente un código único de 4 caracteres alfanumérico (ej: `HWQ2`)
5. La app muestra el código en pantalla grande para que el usuario lo copie en el cuaderno
6. Opcionalmente el usuario agrega una nota de texto corta (ej: "Ejercicio 2 - Máquina de Turing")
7. Se guarda la referencia localmente

### Flujo B: Recuperar foto por código

1. Usuario abre la app
2. Hay un campo de búsqueda/entrada en la pantalla principal
3. Ingresa el código de 4 caracteres (ej: `HWQ2`)
4. La app muestra inmediatamente la foto vinculada a ese código
5. El usuario puede verla a pantalla completa, hacer zoom, etc.

### Lo que NO está en el MVP (se implementa después)

- Organización por materias o unidades temáticas
- Escaneo de cuaderno para reconocer códigos escritos
- Notas de voz vinculadas a recursos
- Sincronización en la nube o multiplataforma
- Búsqueda por texto o tags
- Exportación o backup
- Captura directa dentro de la app (sin usar cámara nativa)
- Códigos QR imprimibles

---

## 4. Decisiones técnicas del MVP

### Generación de códigos

- Código de **4 caracteres alfanuméricos** (letras mayúsculas + números, excluyendo caracteres ambiguos como `0`, `O`, `I`, `1` para evitar confusión al leerlos a mano)
- Debe ser **único** dentro de la base de datos local
- Generación aleatoria con verificación de colisión contra Room antes de asignar
- Ejemplo de caracteres válidos: `A-Z` (sin I, O) + `2-9` = alfabeto de ~30 caracteres → ~810.000 combinaciones posibles para 4 caracteres, más que suficiente para uso personal

### Persistencia

- **Room** para almacenar las referencias (código ↔ ruta de foto ↔ nota opcional ↔ timestamp)
- Las fotos **no se copian** a la carpeta de la app — se guarda la URI de la foto original de la galería
- Manejar correctamente los permisos de acceso a URI persistentes (`takePersistableUriPermission`) para que la referencia siga funcionando aunque el usuario mueva la foto

### Arquitectura

- MVVM estricto
- Repository para acceso a Room
- ViewModel expone StateFlow a la UI
- Jetpack Compose para toda la UI
- Material Design 3 para todos los componentes

### Permisos

- `READ_MEDIA_IMAGES` (Android 13+) o `READ_EXTERNAL_STORAGE` (Android 12 y anteriores)
- Manejo del selector de fotos moderno con `ActivityResultContracts.PickVisualMedia` (Photo Picker API) — preferido porque no requiere permiso en Android 13+

### Entrada del código

- El campo de búsqueda en pantalla principal debe ser el foco principal de la UI
- Autoconvertir a mayúsculas cualquier entrada
- Limitar a exactamente 4 caracteres
- Buscar en tiempo real mientras el usuario tipea (sin botón de "buscar")
- Si el código no existe, mostrar mensaje claro: "No se encontró ningún recurso con este código"

---

## 5. Principios de diseño que NO se negocian

Estos principios aplican a cada decisión de UI/UX durante todo el desarrollo:

1. **Máximo 5 toques para entregar valor**: desde que el usuario abre la app hasta que ve la foto tiene que haber como máximo 5 interacciones.

2. **Offline-first**: la app funciona 100% sin internet. No hay llamadas a red en el MVP.

3. **Velocidad sobre estética**: sin animaciones complejas, sin skeletons innecesarios, sin transiciones lentas. La respuesta a cualquier acción del usuario debe ser instantánea (<100ms percibidos).

4. **Una función, perfecta**: PaperLink hace una sola cosa — vincular fotos con códigos de 4 letras. No se agrega nada que diluya ese propósito.

5. **Sin bloatware**: sin onboarding de 5 pantallas, sin splash screen largo, sin tooltips no solicitados.

6. **UI de alta densidad informativa**: botones grandes, texto legible, código de 4 caracteres mostrado en tipografía monospace grande para facilitar la copia al cuaderno.

7. **Material Design 3**: usar `MaterialTheme.colorScheme` consistentemente, Dynamic Color si el dispositivo lo soporta.

---

## 6. Estructura de datos (Room)

```kotlin
@Entity(tableName = "references")
data class PaperReference(
    @PrimaryKey val code: String,        // "HWQ2" — clave primaria
    val photoUri: String,                // URI persistente de la foto
    val note: String? = null,            // Nota opcional del usuario
    val createdAt: Long,                 // timestamp en milisegundos
    val subject: String? = null          // Materia (para versión futura, nullable ahora)
)
```

---

## 7. Estructura de carpetas recomendada

```
app/
└── src/main/
    ├── java/com/joasasso/paperlink/
    │   ├── data/
    │   │   ├── local/
    │   │   │   ├── PaperReferenceDao.kt
    │   │   │   ├── PaperReferenceDatabase.kt
    │   │   │   └── PaperReference.kt (entity)
    │   │   └── repository/
    │   │       └── ReferenceRepository.kt
    │   ├── domain/
    │   │   └── GenerateCodeUseCase.kt
    │   ├── ui/
    │   │   ├── home/
    │   │   │   ├── HomeScreen.kt
    │   │   │   └── HomeViewModel.kt
    │   │   ├── add/
    │   │   │   ├── AddReferenceScreen.kt
    │   │   │   └── AddReferenceViewModel.kt
    │   │   ├── detail/
    │   │   │   ├── DetailScreen.kt
    │   │   │   └── DetailViewModel.kt
    │   │   └── theme/
    │   │       ├── Color.kt
    │   │       ├── Theme.kt
    │   │       └── Type.kt
    │   └── MainActivity.kt
    └── res/
        ├── values/strings.xml           // TODO: internacionalizar desde el día 1
        └── ...
```

---

## 8. Navegación

Tres pantallas para el MVP:

- **HomeScreen**: campo de búsqueda de código (foco principal) + botón flotante "Nueva referencia" + lista de referencias recientes (opcional en MVP)
- **AddReferenceScreen**: selector de foto → generación de código → mostrar código grande → campo de nota opcional → guardar
- **DetailScreen**: muestra la foto a pantalla completa + el código + la nota si existe

Usar **Navigation Compose** con rutas tipadas.

---

## 9. Internacionalización desde el día 1

Aunque el primer mercado es Argentina/hispanohablante, preparar la app para inglés desde el inicio:

- Todos los strings en `res/values/strings.xml` (español) y `res/values-en/strings.xml` (inglés)
- No hardcodear texto en el código
- Esto facilita el escalado futuro a mercados anglosajones sin deuda técnica

---

## 10. Nombre y branding

- **Nombre de la app**: PaperLink
- **Package name**: `com.joasasso.paperlink`
- **Concepto de marca**: el vínculo entre el papel (cuaderno físico) y el link digital
- **Ícono**: a definir, pero debe evocar papel + enlace/código. Simple, sin gradientes complejos.

---

## 11. Lo que el agente IA debe hacer primero

Al recibir este documento, el agente debe:

1. Crear la estructura del proyecto Android en Kotlin con Gradle (Kotlin DSL)
2. Configurar las dependencias necesarias en `build.gradle.kts`:
   - Room + KSP
   - Jetpack Compose + Material3
   - Navigation Compose
   - Coroutines + Flow
   - Hilt (opcional — si el desarrollador prefiere inyección manual por ahora, respetar esa decisión)
3. Implementar la entity `PaperReference` y el DAO
4. Implementar `GenerateCodeUseCase` con verificación de unicidad
5. Implementar `HomeScreen` con el campo de búsqueda como elemento principal
6. Implementar `AddReferenceScreen` con Photo Picker y generación de código
7. Implementar `DetailScreen` con visualización de foto a pantalla completa

Empezar siempre por la capa de datos y subir hacia la UI. No empezar por el diseño visual.

---

## 12. Preguntas que el agente NO debe hacer

El desarrollador ya tomó estas decisiones:

- **¿Kotlin o Java?** → Kotlin
- **¿XML o Compose?** → Jetpack Compose
- **¿Room o SQLite directo?** → Room
- **¿Firebase o local?** → Local, sin backend
- **¿Cuántos caracteres tiene el código?** → 4 caracteres alfanuméricos
- **¿Se copian las fotos a la app?** → No, se guarda la URI con permiso persistente
- **¿Hay backend en el MVP?** → No

Si hay dudas sobre algo no cubierto en este documento, preguntar antes de implementar.
