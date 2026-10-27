# Configurar Google Cloud para el respaldo en Google Drive

El respaldo en Drive usa la cuenta de Google del usuario, sin servidor propio. Para que Google le dé a la app un permiso de acceso a Drive, el proyecto tiene que estar registrado en Google Cloud. Esto se hace **una sola vez** (más un paso extra antes de publicar en Play).

## Qué usa la app (para entender qué hay que configurar)

- Autorización con `Identity.getAuthorizationClient()` (`data/backup/DriveAuthManager.kt`), pidiendo **solo** el scope `https://www.googleapis.com/auth/drive.appdata`: la carpeta oculta de la app en el Drive del usuario. Es un scope **no sensible**: no requiere la verificación de seguridad de Google.
- Llama a la API REST de Drive v3 directamente (`data/backup/DriveAppDataClient.kt`) con el token que devuelve esa autorización.
- No pide acceso offline ni un token para un servidor: **no hace falta un cliente "Aplicación web"**, solo clientes **Android**.
- Google identifica a la app por **nombre de paquete + huella SHA-1 del certificado de firma**. Por eso hace falta un cliente Android por cada combinación:

| Build | Paquete | Certificado |
| --- | --- | --- |
| Debug | `com.joasasso.paperlink.debug` | Keystore de debug de cada PC donde se compile |
| Release (instalado a mano / bundletool) | `com.joasasso.paperlink` | Tu keystore de subida (upload key) |
| Release (instalado desde Google Play) | `com.joasasso.paperlink` | Clave de firma de apps de Play (Play App Signing) |

## Pasos

### 1. Proyecto y API de Drive

1. Entrá a [console.cloud.google.com](https://console.cloud.google.com) con tu cuenta de Google y creá un proyecto (por ejemplo, "PaperLink") o elegí el que ya tengas.
2. Andá a **APIs y servicios → Biblioteca**, buscá **Google Drive API** y tocá **Habilitar**.

### 2. Pantalla de consentimiento (Google Auth Platform)

En **APIs y servicios → Pantalla de consentimiento de OAuth** (en la consola nueva se llama **Google Auth Platform**):

1. **Información de la marca (Branding):** nombre de la app ("PaperLink"), correo de asistencia y correo de contacto del desarrollador. No subas logo por ahora: agregar logo dispara la verificación de marca.
2. **Público (Audience):** tipo de usuario **Externo**.
   - Mientras esté en estado **Prueba (Testing)**, solo pueden autorizar las cuentas que agregues en **Usuarios de prueba** (hasta 100). Agregá la tuya y la de cada tester.
   - **Importante:** en estado de prueba, Google vence las autorizaciones a los **7 días**. En la app eso se ve como el aviso "Se revocó o venció el permiso de Google Drive" en la Home. Es esperable mientras la app esté en prueba; desaparece al pasar a producción (paso 5).
3. **Acceso a los datos (Data Access):** **Agregar o quitar permisos** → buscá `.../auth/drive.appdata` ("Ver, crear y borrar sus propios datos de configuración en Google Drive") y agregalo. Tiene que aparecer en la sección de permisos **no sensibles**. No agregues otros permisos de Drive.

### 3. SHA-1 del certificado

**Debug:** cada PC tiene su propio keystore de debug (`~/.android/debug.keystore`; en Windows, `C:\Users\<usuario>\.android\debug.keystore`). Para ver su SHA-1:

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

O, desde la raíz del repo, `./gradlew signingReport` (muestra la SHA-1 de cada variante).

> En la PC donde se hizo la reescritura, la SHA-1 de debug es `97:B6:2E:81:DF:87:21:CD:27:CB:09:F5:87:AC:8A:03:79:62:C3:E8`. Si compilás en otra PC, esa tiene otra y necesita su propio cliente Android de debug.

**Release (upload key):** con tu keystore de subida (el que usás en *Build → Generate Signed Bundle*):

```bash
keytool -list -v -keystore <ruta-a-tu-keystore.jks> -alias <tu-alias>
```

**Release desde Google Play:** si usás Play App Signing (lo normal), Play vuelve a firmar la app con **su** clave. Su SHA-1 está en **Play Console → tu app → Prueba y lanzamiento → Configuración → Integridad de la app → Firma de apps** ("Certificado de clave de firma de apps").

### 4. Clientes OAuth de tipo Android

En **Google Auth Platform → Clientes** (o **APIs y servicios → Credenciales → Crear credenciales → ID de cliente de OAuth**), creá un cliente de tipo **Android** por cada fila de la tabla de arriba:

- **Debug:** paquete `com.joasasso.paperlink.debug` + SHA-1 de debug.
- **Release (upload key):** paquete `com.joasasso.paperlink` + SHA-1 de tu keystore de subida.
- **Release (Play):** paquete `com.joasasso.paperlink` + SHA-1 de firma de apps de Play (cuando subas la app a Play).

No hace falta copiar ningún ID de cliente al código: Google lo resuelve por paquete + SHA-1. Los cambios pueden tardar unos minutos en aplicarse.

### 5. Antes de publicar

1. En **Público (Audience)**, tocá **Publicar app** para pasar de *Prueba* a *En producción*. Con solo `drive.appdata` (no sensible) y sin logo, no hace falta la verificación de Google.
2. Confirmá que existe el cliente Android con la SHA-1 de **Play App Signing**: sin él, la versión instalada desde Play no puede autorizar aunque la de debug funcione.
3. Probá desde una instalación de Play (prueba interna): activar el respaldo, respaldar y restaurar.

## Cómo verificar que funciona

1. Instalá la app (debug) en el teléfono con una cuenta agregada como usuario de prueba.
2. En la app: pantalla **Respaldo** → sección **Google Drive** → activá **Respaldo automático diario (Wi‑Fi)**. Tiene que aparecer la pantalla de permiso de Google pidiendo acceso a "sus propios datos de configuración en Google Drive".
3. Aceptá: el primer respaldo corre enseguida y la pantalla muestra la fecha y el tamaño del último respaldo.
4. En [drive.google.com](https://drive.google.com) → **Configuración → Administrar aplicaciones** tiene que aparecer **PaperLink** con "Datos de app ocultos". Desde ahí el usuario puede borrar el respaldo o desconectar la app.

## Problemas comunes

| Síntoma | Causa probable | Qué hacer |
| --- | --- | --- |
| La autorización falla enseguida con error **10 (`DEVELOPER_ERROR`)** en logcat (`adb logcat -s PaperLink`) | No hay cliente Android para ese **paquete + SHA-1** (por ejemplo, debug sin `.debug`, o compilaste en otra PC) | Revisá el paquete exacto y la SHA-1 con `./gradlew signingReport`, y creá el cliente que falte |
| "Acceso bloqueado: la app no completó el proceso de verificación" o `access_denied` | La app está en *Prueba* y la cuenta no es usuario de prueba | Agregá la cuenta en **Público → Usuarios de prueba** |
| Funciona en debug pero no desde Play | Falta el cliente con la SHA-1 de **Play App Signing** | Paso 4, tercer cliente |
| Cada ~7 días pide volver a autorizar | App en estado *Prueba* | Esperable; se corrige publicando (paso 5) |
| La app pide volver a autorizar aunque la cuenta está bien y la consola de Drive dice "API not enabled" | La **Google Drive API** no está habilitada: Drive responde 403 y la app lo trata como falta de permiso | Paso 1.2 |
