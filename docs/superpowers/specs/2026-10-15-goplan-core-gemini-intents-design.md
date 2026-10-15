# Especificación de Diseño Técnico: GoPlan (Fase 1 - Core, Integración Gemini e Intercambio Local)

**Fecha:** 2026-10-15  
**Estado:** Aprobado para Planificación  
**Autor:** Antigravity & Geko  
**Alcance:** Fase 1 — Núcleo Funcional, Almacenamiento Markdown, Integración con Gemini vía Android Intents y Exportación Local para Agentes.

---

## 1. Visión y Objetivos del Proyecto

GoPlan (driftfish) es una aplicación móvil nativa para Android concebida para desarrolladores que trabajan con agentes de inteligencia artificial en entornos locales. Su propósito fundamental es:
1. **Captura ultrarrápida (< 2 segundos):** Registro ágil de ideas de software clasificadas en espacios de trabajo (*Workspaces*).
2. **Expansión asistida por IA sin cuotas de API:** Delegación de la elaboración técnica a la app oficial de Gemini mediante el sistema nativo de `Intent.ACTION_SEND` de Android, aprovechando modelos avanzados y Gems personalizados sin costes de facturación.
3. **Recepción automatizada y troceado multi-archivo:** Ingesta de la respuesta generada por Gemini mediante un filtro de intención entrante (`ACTION_SEND`), troceando el texto en especificaciones modulares (`planes/*.md`) y vinculándolas a `TODO.md`.
4. **Transferencia ágil hacia el PC:** Empaquetado instantáneo de contexto y planes en formatos consumibles por agentes locales (`.zip` / `.md`) a través de LocalSend, Quick Share u otras herramientas de red local.
5. **Fuente única de la verdad en texto plano:** Almacenamiento *offline-first* en archivos Markdown puros, legibles y editables externamente mediante Obsidian, VS Code o terminales.

---

## 2. Arquitectura de Almacenamiento y Estructura de Archivos

### 2.1. Directorio Base en Android
El sistema utilizará el almacenamiento específico de la aplicación en disco externo accesible mediante la API estándar de archivos de Java/Kotlin:
`context.getExternalFilesDir(null)/GoPlan/`

Esta ubicación garantiza:
* Acceso directo de alto rendimiento con `java.io.File` / `java.nio`.
* Cero fricción de permisos de tiempo de ejecución (no requiere `MANAGE_EXTERNAL_STORAGE` ni árboles SAF `DocumentFile` en Android 10 a 15).
* Accesibilidad cuando el dispositivo se conecta por USB al PC en modo transferencia de archivos (MTP).

> [!NOTE] Comportamiento de Scoped Storage (Android 11+)
> En Android 11+, la ruta `Android/data/<package_name>/files/` está restringida para otras aplicaciones instaladas en el propio móvil (como Obsidian para Android o gestores de archivos estándar). Sin embargo, **es 100% accesible vía USB conectando el teléfono al PC**, y para la app GoPlan. Para la Fase 1, la transferencia con el entorno local de desarrollo se realiza vía USB o mediante el módulo de exportación rápida (LocalSend, Quick Share, ZIP). La integración opcional con Obsidian móvil mediante Storage Access Framework (SAF / `DocumentFile`) se deja abierta para una fase posterior.

### 2.2. Jerarquía de Carpetas por Workspace
```text
GoPlan/
├── Proyecto_Alpha/
│   ├── CONTEXT.md
│   ├── TODO.md
│   └── planes/
│       ├── oauth-google.md
│       ├── pestana-concurso-ui.md
│       └── pestana-concurso-api.md
└── Proyecto_Beta/
    ├── CONTEXT.md
    ├── TODO.md
    └── planes/
```

### 2.3. Contenido de los Archivos Base
1. **`CONTEXT.md`**: Define las reglas del proyecto (stack tecnológico, pautas de arquitectura, convenciones de nomenclatura, bibliotecas). Si no existe al inicializar un Workspace, se crea con una plantilla por defecto editable.
2. **`TODO.md`**: Documento Markdown con el índice de ideas y estado. Soporta títulos de sección, notas intermedias y sintaxis de tareas compatible con Obsidian y editores estándar.
3. **Carpeta `planes/`**: Directorio donde se almacenan los documentos técnicos detallados generados a partir de las ideas.

---

## 3. Modelo de Dominio y Motor de Parseo (`TODO.md`)

### 3.1. Sintaxis de Estados de Tareas
Cada línea de tarea en `TODO.md` inicia con un marcador de estado:
* `- [ ]`: **Pendiente** (`IdeaStatus.PENDING`)
* `- [/]`: **En progreso** (`IdeaStatus.IN_PROGRESS`)
* `- [x]`: **Completado** (`IdeaStatus.COMPLETED`)
* `- [-]`: **Descartado** (`IdeaStatus.DISCARDED`)

### 3.2. Formatos de Ideas y Enlaces
1. **Idea Simple (Solo texto y etiquetas):**
   ```markdown
   - [ ] Implementar autenticación OAuth Google #auth #backend
   ```
2. **Idea Compleja (Un único archivo de plan):**
   ```markdown
   - [/] [Implementar autenticación OAuth Google](planes/oauth-google.md) #auth #backend
   ```
3. **Idea Compleja Multi-archivo (Múltiples archivos relacionados):**
   ```markdown
   - [ ] Crear módulo de concursos ([UI](planes/concurso-ui.md), [API](planes/concurso-api.md)) #feature #ui
   ```

### 3.3. Entidades de Dominio (Kotlin)
```kotlin
enum class IdeaStatus(val marker: String) {
    PENDING("[ ]"),
    IN_PROGRESS("[/]"),
    COMPLETED("[x]"),
    DISCARDED("[-]")
}

data class PlanLink(
    val label: String,
    val relativePath: String
)

data class Idea(
    val id: String,
    val status: IdeaStatus,
    val title: String,
    val tags: Set<String>,
    val plans: List<PlanLink>,
    val rawLine: String
)

sealed interface TodoLine {
    data class Header(val rawText: String, val level: Int) : TodoLine
    data class Task(val idea: Idea) : TodoLine
    data class Other(val rawText: String) : TodoLine
}

data class TodoDocument(
    val lines: List<TodoLine>
)
```

#### Estrategia de Identificadores Estables (`idea.id`)
Para evitar que la *Loopback Cache* falle si el usuario reordena o edita `TODO.md` mientras Gemini procesa el plan:
1. **Detección prioritaria de ID explícito:** El parser inspecciona si la línea contiene un identificador incrustado como comentario HTML al final: `<!-- id:([a-zA-Z0-9_-]+) -->`. Si existe, se usa como `idea.id`.
2. **Inyección en expansión:** Al pulsar "Expandir con Gemini", si la tarea aún no posee un `<!-- id:... -->`, la app genera un UUID corto (ej. `a1b2c3d4`), lo escribe al final de la línea en `TODO.md` y lo registra en la caché.
3. **Fallback por Slug de Título:** Si no existe comentario HTML, el `id` se deriva limpiando el título sin etiquetas ni enlaces: `cleanTitle.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-")`. Esto asegura idempotencia y estabilidad tanto en memoria como tras reescrituras externas.

### 3.4. Parser No Destructivo (`TodoMarkdownParser`)
* **Lectura:**
  * Detecta tareas mediante la expresión regular:
    `^(\s*-\s*\[([ x/\-])\])\s+(.*)$`
  * Extrae enlaces Markdown estándar `\[(.*?)\]\((.*?)\)` tanto en formato de título enlazado como en listas de sub-enlaces parentizadas.
  * Extrae etiquetas mediante `#([a-zA-Z0-9_\-]+)`.
  * Cualquier línea que no coincida con el patrón de tarea se conserva intacta como `TodoLine.Header` (si inicia con `#`) o `TodoLine.Other` (líneas vacías, comentarios, notas en bloque).
* **Serialización y Escritura Atómica:**
  * Al actualizar o insertar ideas, se reconstruye el texto preservando exactamente los encabezados, notas y comentarios en sus posiciones originales.
  * La escritura se ejecuta mediante sustitución atómica (archivo temporal `.tmp` y reemplazo atómico) para prevenir corrupción de datos ante interrupciones.

---

## 4. Flujos de Integración con Gemini (Android Intents)

### 4.1. Flujo Saliente: "Expandir Idea con Gemini"
1. El usuario pulsa la acción de expandir en una idea del Workspace.
2. La app genera en memoria el prompt estructurado:
   ```text
   # CONTEXTO DEL PROYECTO
   {Contenido de CONTEXT.md}

   # IDEA A DESARROLLAR
   - {idea.title}
   Etiquetas: {idea.tags.joinToString(", ")}

   # FORMATO DE SALIDA ESTRICTO
   Transforma esta idea en un plan técnico detallado, modular y accionable para un agente de código.
   Si el plan requiere dividirse en varios archivos, separa cada archivo usando estrictamente la línea:
   === ARCHIVO: nombre-archivo-en-kebab-case.md ===

   Si cabe en un solo archivo, inicia con:
   === ARCHIVO: {slug-de-la-idea}.md ===
   ```
3. **Memoria de Retorno (*Loopback Cache*):** Se almacena temporalmente en `DataStore` la tupla:
   `RecentExpansion(workspaceName, ideaId, timestamp = System.currentTimeMillis())`.
4. **Disparo de Intent:** Lanza `Intent.ACTION_SEND` con MIME `text/plain` y el prompt en `Intent.EXTRA_TEXT` mediante `Intent.createChooser()`.

### 4.2. Flujo Entrante: "Recibir Plan desde Gemini"
1. `AndroidManifest.xml` expone un `intent-filter` en la actividad principal:
   ```xml
   <intent-filter>
       <action android:name="android.intent.action.SEND" />
       <category android:name="android.intent.category.DEFAULT" />
       <data android:mimeType="text/plain" />
   </intent-filter>
   ```
2. **Extracción y Parseo (`GeminiPlanParser`):**
   * Busca bloques delimitados por:
     `=== ARCHIVO:\s*([^\n\r=]+?\.md)\s*===\s*\n?`
   * **Sanitización de Seguridad (Prevención de *Path Traversal*):**
     * Para cada coincidencia, se extrae estrictamente el nombre base usando `java.io.File(rawName.trim()).name`.
     * Se eliminan prefijos de subdirectorios, barras diagonales (`/`, `\`) o secuencias de escape (`../`, `..\`).
     * Se normaliza a kebab-case seguro `[a-z0-9_-]+\.md`. Cualquier intento de escribir fuera de `planes/` queda neutralizado.
   * Si detecta delimitadores: Trocea el texto en una lista de pares `(fileName, content)`.
   * Si no detecta delimitadores (Modo Manual): Asigna el contenido completo a un único archivo con nombre derivado del título de la idea (`{slug-idea}.md`).
3. **Resolución de Destino en UI (`ImportPlanBottomSheet`):**
   * **Detección Especial de `CONTEXT.md`:** Si el texto entrante inicia con `# Contexto` o el archivo compartido se llama `CONTEXT.md`, la UI ofrece como acción prioritaria **"Actualizar CONTEXT.md del Workspace"**, permitiendo transferir fácilmente las reglas del proyecto generadas en PC (con Orca/IDE) hacia GoPlan vía LocalSend/Quick Share.
   * Si la caché de expansión reciente tiene menos de 30 minutos, preselecciona automáticamente el Workspace y la Idea de origen.
   * Muestra la vista previa de los archivos generados con sus nombres normalizados.
   * Permite al usuario confirmar la vinculación con un toque o reasignar a otra idea / crear una nueva idea.
4. **Persistencia:**
   * Escribe cada archivo generado en la carpeta `planes/`.
   * Actualiza la línea de la idea en `TODO.md` con los nuevos enlaces a `planes/` y cambia automáticamente su estado a `IN_PROGRESS` (`- [/]`).

---

## 5. Experiencia de Usuario y UI en Jetpack Compose

### 5.1. Pantalla Principal (`WorkspaceScreen`)
* **TopAppBar:**
  * Selector desplegable de Workspace con creación rápida.
  * Botón de acceso al editor de `CONTEXT.md`.
  * Botón de acción para **Exportar para Agente**.
* **Filtros Rápidos (Chips Horizontales):**
  * Chips de estado: `Pendientes`, `En progreso`, `Completadas`, `Descartadas` (ocultas por defecto).
  * Chips de etiquetas `#tag`: Calculados reactivamente a partir del `TODO.md` activo.
* **Lista de Ideas (`LazyColumn`):**
  * **Interacción por gestos (Swipe):**
    * Swipe hacia la derecha: Avanza cíclicamente el estado (`Pendiente` -> `En progreso` -> `Completada`).
    * Swipe hacia la izquierda: Abre diálogo rápido para marcar como `Descartada` o editar.
  * **Ítems con Planes Vinculados:**
    * Muestran chips interactivos con los nombres de los planes vinculados (ej. `[UI]`, `[API]`).
    * Tap en el ítem navega directamente al visor de planes.
  * **Ítems Simples:**
    * Tap despliega menú de acciones rápidas (*Expandir con Gemini*, *Crear plan manual*, *Editar texto*).
* **Barra de Entrada Rápida (*QuickCaptureBar*):**
  * Posicionada de forma fija en la parte inferior sobre el teclado virtual.
  * Detección reactiva del carácter `#` para desplegar sugerencias de tags existentes en el proyecto.
  * Botón de envío que inserta la nueva idea en `TODO.md` en menos de 2 segundos.

### 5.2. Visor y Editor de Planes (`PlanViewerScreen`)
* **Pestañas Multi-archivo (`ScrollableTabRow`):** Permite alternar entre los distintos archivos de plan vinculados a la tarea actual.
* **Conmutador Lectura / Edición:**
  * Modo Lectura: Renderizado Markdown estilizado con Material 3.
  * Modo Edición: Editor de texto enriquecido con barra de atajos sobre el teclado (`#`, `- [ ]`, ```` ````).
* **Herramienta "Dividir desde el cursor" (`SplitFromCursor`):**
  * En modo edición, el usuario posiciona el cursor y pulsa el botón de división.
  * El texto desde el cursor hacia el final se extrae hacia un nuevo archivo `.md` (solicitando nombre o autogenerándolo).
  * Se guarda el nuevo archivo en `planes/` y se actualiza el `TODO.md` añadiendo el nuevo enlace a la idea.

### 5.3. Editor de Contexto (`ContextEditorScreen`)
* Editor de texto plano para `CONTEXT.md` con botones de guardado y plantilla de inicio rápido para proyectos nuevos.
* **Importar / Sobrescribir desde PC:**
  * Botón en la barra superior para *"Importar archivo Markdown"* (mediante selector de archivos o pegado rápido).
  * Admite recibir archivos compartidos desde el PC vía LocalSend / Quick Share para actualizar el contexto del proyecto de forma directa sin teclear en el móvil.

---

## 6. Módulo de Exportación y Transferencia a Agentes

### 6.1. Empaquetado de Datos (`AgentExportManager`)
El usuario puede seleccionar una idea individual, un conjunto de ideas o el proyecto completo para exportar:
1. **Paquete ZIP (`ExportFormat.ZIP`):**
   * Genera un archivo `.zip` en `cacheDir/exports/`.
   * Estructura del ZIP:
     * `CONTEXT.md`
     * `TODO_EXTRACT.md` (fragmento del `TODO.md` con las tareas seleccionadas).
     * `planes/*.md` correspondientes a las tareas exportadas.
2. **Markdown Unificado (`ExportFormat.SINGLE_MARKDOWN`):**
   * Concatena en un único archivo Markdown el contexto del proyecto y los planes seleccionados, delimitados claramente con encabezados para consumo directo por agentes LLM.

### 6.2. Envío a Red Local vía `ACTION_SEND`
* Generación de URI segura `content://` mediante `androidx.core.content.FileProvider`.
* Apertura del diálogo de compartir nativo de Android permitiendo el envío directo a:
  * **LocalSend** (transferencia directa a PC sin internet).
  * **Quick Share / Nearby Share** (transferencia nativa a Windows / ChromeOS).
  * Clientes de mensajería o almacenamiento compartido.

---

## 7. Persistencia, Resiliencia y Casos Límite

### 7.1. Casos Límite y Mitigaciones
| Escenario | Mitigación Técnica |
|-----------|--------------------|
| Líneas en `TODO.md` con sintaxis no estándar o notas libres | El parser las clasifica como `TodoLine.Other` y las preserva de forma idéntica sin alterar su formato ni posición. |
| Caída de energía o cierre súbito durante escritura | Uso de `AtomicFile` con escritura previa en `.tmp` y renombrado atómico en el sistema de archivos. |
| Enlaces a archivos inexistentes en `planes/` | La UI muestra el chip del plan con indicador de advertencia y ofrece crear el archivo en blanco con un toque. |
| Texto recibido desde Gemini sin etiquetas `=== ARCHIVO: ===` | Modo fallback automático: almacena todo el texto en `planes/{slug-idea}.md` sin arrojar error. |
| Colisión de nombres al crear o recibir archivos de plan | Detección de colisión y generación de sufijo incremental (`nombre-1.md`). |

### 7.2. Almacenamiento de Preferencias (`DataStore`)
* Claves administradas:
  * `KEY_ACTIVE_WORKSPACE`: Slug del Workspace en uso.
  * `KEY_RECENT_EXPANSION_WORKSPACE`: Workspace de la última idea compartida con Gemini.
  * `KEY_RECENT_EXPANSION_IDEA_ID`: ID de la última idea compartida.
  * `KEY_RECENT_EXPANSION_TIMESTAMP`: Marca temporal de la última idea compartida (válida por 30 minutos).

---

## 8. Estrategia de Testing y Verificación (TDD)

### 8.1. Tests Unitarios en JVM (Sin Emulador)
Los componentes críticos no dependen del framework de Android y se prueban de forma aislada a alta velocidad:
1. **`TodoMarkdownParserTest`:**
   * Parseo de tareas simples, tareas con 1 enlace y tareas con múltiples enlaces.
   * Extracción correcta de etiquetas `#tag`.
   * Preservación exacta de encabezados `#`, comentarios y líneas en blanco tras la serialización.
   * Actualización de estado de una idea específica sin mutar el resto del documento.
2. **`GeminiPlanParserTest`:**
   * Troceado de texto con múltiples bloques `=== ARCHIVO: nombre.md ===`.
   * Normalización de nombres de archivo a kebab-case.
   * Modo fallback cuando no hay delimitadores presentes.
   * Comportamiento con bloques de texto antes del primer delimitador.
3. **`AgentExportManagerTest`:**
   * Generación correcta de archivos ZIP con sus entradas (`CONTEXT.md`, extracto de `TODO.md`, `planes/*.md`).
   * Generación de archivo Markdown unificado.
4. **`WorkspaceRepositoryTest`:**
   * Operaciones de lectura y escritura en carpetas temporales de JVM (`Files.createTempDirectory`).

### 8.2. Tests de Interfaz y Navegación (Compose Testing)
* Renderizado de la lista de ideas y filtrado por chips de estado y etiquetas.
* Comportamiento de la barra de entrada rápida y agregación inmediata de ideas.
* Despliegue y preselección del modal de recepción al simular la entrada de un Intent.
