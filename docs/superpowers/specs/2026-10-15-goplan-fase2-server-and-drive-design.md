# Especificación de Diseño Técnico: GoPlan (Fase 2 - Servidor Local HTTP y Sincronización Google Drive)

**Fecha:** 2026-10-15  
**Estado:** Propuesto para Revisión  
**Autor:** Antigravity & Geko  
**Alcance:** Fase 2 — Servidor HTTP Embebido (Ktor) para comunicación directa PC-Móvil en red local Wi-Fi, y Sincronización Bidireccional en la Nube con Google Drive.

---

## 1. Visión y Objetivos de la Fase 2

En la Fase 1 se consolidó la captura ultrarrápida, el almacenamiento Markdown no destructivo (`TODO.md`, `CONTEXT.md`, `planes/*.md`), la integración nativa con Gemini vía Android Intents (`ACTION_SEND`) y la exportación de paquetes ZIP hacia el PC vía LocalSend / Quick Share.

La **Fase 2** elimina la fricción de transferencias manuales de archivos entre el móvil y el ordenador de desarrollo mediante dos pilares:
1. **Servidor HTTP Embebido Local (Ktor Android):**
   - Transforma el dispositivo móvil en un nodo de desarrollo accesible vía Wi-Fi local.
   - Permite a los agentes de software en el PC (Orca, Antigravity, extensiones de VS Code, terminales CLI, scripts Python/curl) consultar el contexto, leer tareas, crear planes de arquitectura directamente en el teléfono y actualizar estados sin intervención manual.
   - **Ciclo de vida acotado y seguro:** El servidor se ejecuta **únicamente mientras GoPlan está abierta en pantalla**, evitando consumo de batería en segundo plano y eliminando la necesidad de Foreground Services intrusivos con notificaciones fijas.
2. **Sincronización Bidireccional con Google Drive:**
   - Proporciona respaldo continuo en la nube para los espacios de trabajo.
   - Permite recuperar workspaces en nuevos dispositivos y mantener la consistencia entre múltiples terminales.
   - Estrategia *Offline-First*: la aplicación siempre opera de forma instantánea sobre el disco local (`getExternalFilesDir`) y sincroniza de forma no bloqueante bajo demanda ("Sincronizar ahora") o al iniciar.

---

## 2. Componente 1: Servidor HTTP Embebido Local (Ktor)

### 2.1. Arquitectura y Dependencias
Se utilizará **Ktor Server** con el motor **CIO** (*Coroutine I/O*), el motor oficial de Jetpack/Kotlin de alto rendimiento y bajo consumo de memoria diseñado específicamente para entornos basados en corrutinas:

* `io.ktor:ktor-server-core:3.0.0`
* `io.ktor:ktor-server-cio:3.0.0`
* `io.ktor:ktor-server-content-negotiation:3.0.0`
* `io.ktor:ktor-serialization-kotlinx-json:3.0.0`
* `io.ktor:ktor-server-cors:3.0.0` (habilita peticiones cruzadas desde herramientas web en el navegador del PC)
* `io.ktor:ktor-server-status-pages:3.0.0` (captura global de excepciones y respuestas JSON estandarizadas)

### 2.2. Ciclo de Vida y Detección de Red
* **Regla de Ejecución:** El servidor arranca en el ciclo de vida visible de `MainActivity` (`onStart` / `onResume` o gestionado mediante un estado en Compose) y se detiene limpiamente al salir (`onStop` / `onPause`).
* **Descubrimiento de IP:** Utilidad `NetworkUtils.getLocalWifiIpAddress(context)` que inspecciona las interfaces de red activas (`wlan0`, etc.) y obtiene la IPv4 local (ej. `192.168.1.45`).
* **Puerto:** Puerto por defecto `8080`. Si el puerto estuviese ocupado, fallback automático a `8081` o configurable.
* **Seguridad en Red Local:**
  - Por defecto, el servidor se vincula a `0.0.0.0` para escuchar en la subred local Wi-Fi.
  - Cabecera opcional `X-GoPlan-Token`: configurable en ajustes (por defecto deshabilitado para máxima conveniencia, o habilitable con un PIN de 4 dígitos para redes públicas o compartidas).

### 2.3. Especificación de Endpoints REST

Todas las respuestas exitosas de datos utilizan `application/json; charset=utf-8` a excepción de las descargas de archivos y texto plano según se detalla.

#### 2.3.1. Estado y Diagnóstico
* **`GET /api/status`**
  - **Propósito:** Health check para que los scripts del PC verifiquen si GoPlan está en línea.
  - **Respuesta (200 OK):**
    ```json
    {
      "status": "online",
      "app": "GoPlan",
      "version": "1.0.0",
      "activeWorkspace": "Proyecto_Alpha",
      "ip": "192.168.1.45",
      "port": 8080
    }
    ```

#### 2.3.2. Workspaces
* **`GET /api/workspaces`**
  - **Propósito:** Lista todos los workspaces disponibles y sus estadísticas de ideas.
  - **Respuesta (200 OK):**
    ```json
    [
      {
        "id": "Proyecto_Alpha",
        "name": "Proyecto Alpha",
        "stats": {
          "total": 12,
          "pending": 5,
          "inProgress": 3,
          "completed": 4,
          "discarded": 0
        }
      }
    ]
    ```
* **`POST /api/workspaces`**
  - **Propósito:** Crear un nuevo espacio de trabajo desde el PC.
  - **Payload:** `{ "name": "Nuevo_Proyecto" }`
  - **Respuesta (201 Created):** `{ "id": "Nuevo_Proyecto", "name": "Nuevo_Proyecto" }`

#### 2.3.3. Contexto del Proyecto (`CONTEXT.md`)
* **`GET /api/workspaces/{id}/context`**
  - **Propósito:** Obtener las reglas y directrices técnicas del proyecto para alimentar el prompt del agente en el PC.
  - **Parámetro opcional:** `?format=raw` (devuelve `text/markdown`), o JSON por defecto:
    ```json
    {
      "workspaceId": "Proyecto_Alpha",
      "content": "# CONTEXTO DEL PROYECTO\n..."
    }
    ```
* **`PUT /api/workspaces/{id}/context`**
  - **Propósito:** Actualizar `CONTEXT.md` directamente desde el PC (ej. al actualizar dependencias o arquitectura).
  - **Payload:** `{ "content": "# CONTEXTO ACTUALIZADO\n..." }`
  - **Respuesta (200 OK):** `{ "success": true }`

#### 2.3.4. Lista de Tareas e Ideas (`TODO.md`)
* **`GET /api/workspaces/{id}/todo`**
  - **Propósito:** Obtener la lista completa de ideas estructuradas y el contenido bruto del archivo.
  - **Respuesta (200 OK):**
    ```json
    {
      "rawMarkdown": "- [ ] Implementar OAuth #auth\n...",
      "ideas": [
        {
          "id": "implementar-oauth",
          "title": "Implementar OAuth",
          "status": "PENDING",
          "tags": ["auth"],
          "plans": [
            { "label": "UI", "relativePath": "planes/oauth-ui.md" }
          ]
        }
      ]
    }
    ```
* **`POST /api/workspaces/{id}/ideas`**
  - **Propósito:** Añadir una nueva tarea o idea desde la consola del PC o un script de agente.
  - **Payload:** `{ "text": "Auditar endpoints con OWASP ZAP #seguridad #qa" }`
  - **Respuesta (201 Created):** Idea parseada y añadida al final de `TODO.md`.

* **`PATCH /api/workspaces/{id}/ideas/{ideaId}`**
  - **Propósito:** Modificar el estado (`PENDING`, `IN_PROGRESS`, `COMPLETED`, `DISCARDED`), título o etiquetas desde el PC.
  - **Payload:** `{ "status": "COMPLETED" }` o `{ "title": "Nuevo título", "tags": ["tag1"] }`
  - **Respuesta (200 OK):** `{ "success": true, "idea": { ... } }`

* **`DELETE /api/workspaces/{id}/ideas/{ideaId}`**
  - **Propósito:** Eliminar definitivamente la idea y sus archivos de plan asociados.
  - **Respuesta (200 OK):** `{ "success": true }`

#### 2.3.5. Ingesta y Consulta de Planes (`planes/*.md`)
* **`POST /api/workspaces/{id}/plans`**
  - **Propósito:** Endpoint clave para agentes en el PC. Permite a un agente local (ej. Antigravity/Orca) enviar un plan técnico ya generado hacia GoPlan para que aparezca vinculado a la idea en el móvil.
  - **Modo A (Multi-archivo estructurado):**
    ```json
    {
      "ideaId": "implementar-oauth",
      "files": [
        { "fileName": "oauth-backend.md", "content": "# Plan Backend..." },
        { "fileName": "oauth-frontend.md", "content": "# Plan Frontend..." }
      ]
    }
    ```
  - **Modo B (Texto con delimitadores Gemini):**
    ```json
    {
      "ideaId": "implementar-oauth",
      "rawText": "=== ARCHIVO: oauth-plan.md ===\n# Contenido..."
    }
    ```
  - **Comportamiento:** Guarda los archivos en `planes/`, vincula los enlaces en `TODO.md` mediante `TodoMarkdownParser.linkPlansToIdea`, y transiciona el estado de la idea a `IN_PROGRESS`.
  - **Respuesta (201 Created):** `{ "success": true, "createdFiles": ["oauth-backend.md", ...] }`

* **`GET /api/workspaces/{id}/plans/{fileName}`**
  - **Propósito:** Leer el contenido de un plan específico en Markdown.
  - **Respuesta:** `text/markdown; charset=utf-8` con protección estricta contra Path Traversal.

#### 2.3.6. Exportación y Descarga Directa
* **`GET /api/workspaces/{id}/export?type=zip`**
  - **Propósito:** Descarga directa del bundle `.zip` con `CONTEXT.md`, `TODO_EXTRACT.md` y todos los planes.
  - **Headers:** `Content-Type: application/zip`, `Content-Disposition: attachment; filename="{id}-export.zip"`
* **`GET /api/workspaces/{id}/export?type=md`**
  - **Propósito:** Descarga directa del Markdown unificado.

---

## 3. Componente 2: Sincronización con Google Drive

### 3.1. Arquitectura de Autenticación y Permisos
* **Bibliotecas Google Play Services:**
  - `com.google.android.gms:play-services-auth:21.2.0` (Gestión de credenciales OAuth2 y selector de cuenta Google en Android).
  - `com.google.api-client:google-api-client-android:2.6.0`
  - `com.google.apis:google-api-services-drive:v3-rev20240530-2.0.0`
* **Alcance de Permisos (OAuth Scopes):**
  - `DriveScopes.DRIVE_FILE`: Otorga acceso únicamente a los archivos y carpetas creados o abiertos por la propia aplicación GoPlan. No requiere acceso completo a todo el Google Drive del usuario, minimizando alertas de seguridad y manteniendo estricta privacidad.
* **Estructura Remota en Google Drive:**
  - Carpeta raíz: `GoPlan/` (creada en la raíz del Drive del usuario, visible y accesible por él desde la interfaz web o de escritorio de Google Drive).
  - Subcarpetas idénticas a la estructura local:
    ```text
    GoPlan/
    ├── Proyecto_Alpha/
    │   ├── CONTEXT.md
    │   ├── TODO.md
    │   └── planes/
    │       ├── plan-1.md
    │       └── plan-2.md
    ```

### 3.2. Motor de Sincronización (`GoogleDriveSyncEngine`)
1. **Detección de Cambios mediante Hashes y Metadatos:**
   - La aplicación mantiene un archivo local de metadatos `.sync_state.json` (o almacén en `DataStore`) por workspace con:
     - `remoteFileId`: ID único del archivo en Drive.
     - `localSha256`: Hash SHA-256 del archivo local en el momento de la última sincronización exitosa.
     - `remoteModifiedTime`: Timestamp de última modificación reportado por Drive.
2. **Matriz de Decisión de Sincronización:**
   - **Local modificado, Remoto sin cambios:** Se sube la versión local a Google Drive (`files.update`).
   - **Remoto modificado, Local sin cambios:** Se descarga la versión de Drive y se escribe atómicamente en disco local (`writeAtomic`).
   - **Ambos sin cambios:** Sin acción.
   - **Conflicto (Ambos modificados concurrentemente):**
     - Para `TODO.md`: Se realiza una fusión conservadora basada en las entidades de `Idea`:
       - Se preservan todas las ideas (unión de IDs).
       - En caso de colisión de una misma idea modificada en ambos lados, se prioriza el estado más avanzado (`COMPLETED` > `IN_PROGRESS` > `PENDING`) y se concatenan los planes.
     - Para `CONTEXT.md` y archivos de `planes/*.md`: Se renombra el archivo local a `<nombre>.conflict-<timestamp>.md` para no perder ninguna modificación del usuario, y se descarga el archivo remoto como versión principal.
3. **Manejo de Red y Modo Desconectado:**
   - La sincronización se ejecuta en segundo plano mediante `Dispatchers.IO` y corrutinas.
   - Si no hay conexión Wi-Fi/datos móviles o el token expira, la aplicación continúa funcionando normalmente con los datos locales sin bloquear la interfaz. Un icono de estado en la barra superior refleja: *Sincronizado*, *Sincronizando...*, o *Sin conexión*.

---

## 4. Integración en la Capa de Presentación (UI / Jetpack Compose)

### 4.1. Indicador y Control del Servidor Local
* En la barra superior (`WorkspaceScreen`), se añade un botón o chip con el icono de red/PC (`Icons.Default.Wifi` o `Icons.Default.Lan`):
  - **Estado Activo:** Muestra un chip verde o diálogo informativo:  
    `Conexión PC: http://192.168.1.45:8080`
    - Botón para copiar la URL al portapapeles.
    - Código QR opcional para escaneo rápido con la cámara del portátil/móvil si fuera necesario.
    - Interruptor para pausar/reanudar el servidor manualmente si el usuario lo desea.
  - **Estado Inactivo (Fuera de Wi-Fi o app en segundo plano):** El chip cambia a estado gris / inactivo.

### 4.2. Control de Sincronización con Google Drive
* En el menú superior o selector de workspaces:
  - Botón "Sincronizar con Google Drive":
    - Si no ha iniciado sesión: Abre el selector de cuenta de Google Play Services.
    - Si ha iniciado sesión: Muestra la cuenta activa (ej. `usuario@gmail.com`), última sincronización ("Hace 2 min") y el botón "Sincronizar ahora".
    - Animación de rotación en el icono de sincronización mientras el proceso está activo.

---

## 5. Pruebas y Estrategia de Verificación (TDD)

1. **Pruebas Unitarias del Servidor Ktor (JVM Puro):**
   - Utilizar el motor de pruebas oficial `io.ktor:ktor-server-test-host:3.0.0`.
   - Permite levantar el servidor en memoria en milisegundos sin necesidad de sockets de red reales ni emuladores Android.
   - Cobertura completa de cada ruta:
     - `GET /api/status`: verifica estructura JSON.
     - `GET /api/workspaces`: verifica listado de carpetas y estadísticas.
     - `GET/PUT /api/workspaces/{id}/context`: verifica lectura y escritura atómica.
     - `GET/POST/PATCH/DELETE /api/workspaces/{id}/ideas`: verifica manipulación de tareas en `TODO.md`.
     - `POST /api/workspaces/{id}/plans`: verifica creación de archivos y vinculación en `TODO.md`.
     - `GET /api/workspaces/{id}/export`: verifica descarga de ZIP válido.
2. **Pruebas del Motor de Sincronización:**
   - Pruebas unitarias de cálculo de hash SHA-256 y detección de diferencias.
   - Pruebas de resolución de conflictos en `TODO.md` (fusión de listas de ideas).
   - Mock de cliente Drive para verificar llamadas `files.create`, `files.update`, `files.get`.
3. **Pruebas de Integración y Compilación:**
   - `./gradlew.bat testDebugUnitTest` pasando al 100%.
   - `./gradlew.bat assembleDebug` generando el APK compilado para instalación real.

---

## 6. Plan de Entrega e Hitos

1. **Hito 2.1 — Motor Ktor Server y Rutas REST (TDD)**
   - Configuración de dependencias Gradle de Ktor CIO y Serialization.
   - Creación de `KtorServerManager` y definición de todas las rutas REST.
   - Suite de tests unitarios con `ktor-server-test-host`.
2. **Hito 2.2 — Integración de Red y UI del Servidor en Android**
   - Implementación de `NetworkUtils` para detección de IP Wi-Fi local.
   - Conexión del ciclo de vida del servidor con `MainActivity` (solo activo en primer plano).
   - Componente UI de estado y diálogo de conexión PC en `WorkspaceScreen`.
3. **Hito 2.3 — Integración con Google Drive**
   - Configuración de dependencias Google Play Services Auth y Drive API v3.
   - Implementación de `GoogleDriveAuthManager` y `GoogleDriveSyncEngine`.
   - Pruebas unitarias de sincronización y resolución de conflictos.
4. **Hito 2.4 — UI de Sincronización y Verificación Final**
   - Botón y diálogo de sincronización en `WorkspaceScreen`.
   - Verificación end-to-end con tests JVM y generación de APK `app-debug.apk`.
