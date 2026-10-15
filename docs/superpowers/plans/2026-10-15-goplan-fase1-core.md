# GoPlan (Fase 1: Core, Integración Gemini e Intercambio Local) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir la Fase 1 de GoPlan (driftfish): una aplicación nativa Android (Kotlin + Jetpack Compose) para capturar ideas ultrarrápidamente en Workspaces respaldados por Markdown plano, expandirlas con Gemini vía Android Intents con retorno automático en un toque (*loopback* inteligente), y empaquetarlas para agentes locales.

**Architecture:** Arquitectura limpia reactiva centrada en el sistema de archivos local (`context.getExternalFilesDir`). Parsers puros en JVM para `TODO.md` (no destructivo con preservación de encabezados/comentarios) y troceado multi-archivo de Gemini (`=== ARCHIVO: ... ===` con protección contra Path Traversal). Capa de UI en Jetpack Compose con Navigation Compose y `DataStore` para preferencias y caché temporal de retorno.

**Tech Stack:** Kotlin 2.0+, Jetpack Compose (Material 3), Jetpack Navigation Compose, Jetpack DataStore Preferences, Kotlinx Coroutines & Flow, AndroidX Core / AtomicFile, JUnit 5 / JUnit 4, Kotlinx Serialization / ZipOutputStream.

**Spec:** [docs/superpowers/specs/2026-10-15-goplan-core-gemini-intents-design.md](file:///C:/Users/Geko/orca/workspaces/G0Plan/driftfish/docs/superpowers/specs/2026-10-15-goplan-core-gemini-intents-design.md)

## Global Constraints

* **Plataforma:** Android Min SDK 26 (Android 8.0 Oreo), Target SDK 35 (Android 15), JVM Toolchain 17 / 21.
* **Almacenamiento Local:** `context.getExternalFilesDir(null)/GoPlan/` para acceso directo de alto rendimiento con `java.io.File` sin permisos especiales.
* **Cero APIs de IA de Pago:** Toda comunicación con Gemini se ejecuta mediante `Intent.ACTION_SEND` nativo de Android.
* **Integridad de Datos:** `TODO.md` debe preservar el 100% de encabezados, comentarios y saltos de línea al insertar o modificar ideas.
* **Seguridad de Archivos:** Prevención estricta de *Path Traversal* en el troceado de nombres de archivo de Gemini usando `File(rawName).name`.

## Review Focus

1. **Path traversal en nombres de archivo de Gemini:** Nombres alucinados como `../../etc/passwd` o `sub/plan.md` deben ser saneados estrictamente al nombre base `passwd.md` y `plan.md` dentro de `planes/`.
2. **Corrupción de `TODO.md` en fallos inesperados:** Las escrituras a disco deben ser atómicas (`.tmp` + rename atómico).
3. **Resistencia de `idea.id` ante ediciones externas:** La vinculación tras volver de Gemini debe usar el comentario `<!-- id:xxxx -->` si existe, o el slug del título limpio, evitando desajustes por cambios de número de línea.
4. **Respuestas de Gemini sin delimitadores:** El parser debe caer graciosamente al modo de archivo único (`planes/{slug-idea}.md`) sin perder contenido ni lanzar excepciones.
5. **Detección e importación de `CONTEXT.md` compartido desde PC:** Compartir un archivo `CONTEXT.md` o texto que inicie con `# Contexto` vía LocalSend debe activar la opción de sobrescribir el contexto del Workspace.

---

### Task 1: Scaffolding del Proyecto Android y Configuración Base

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/java/com/goplan/app/MainActivity.kt`
- Create: `app/src/main/java/com/goplan/app/GoPlanApplication.kt`

**Interfaces:**
- Produces: Estructura gradle estándar de Android con soporte para Jetpack Compose, Material 3, Navigation, DataStore, Coroutines y tests unitarios. `GoPlanApplication` sirve como contenedor de inyección de dependencias manual ligero (singletons para DataStore, Repositorio y FileManager).

- [ ] **Step 1: Crear `settings.gradle.kts` con repositorios Maven Central y Google**
- [ ] **Step 2: Crear `build.gradle.kts` raíz y `app/build.gradle.kts` con plugins de Android y Compose**
- [ ] **Step 3: Configurar dependencias en `app/build.gradle.kts`:**
  - `androidx.compose.material3:material3`
  - `androidx.navigation:navigation-compose`
  - `androidx.datastore:datastore-preferences`
  - `androidx.core:core-ktx`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android`
  - `testImplementation("junit:junit:4.13.2")`
  - `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")`
- [ ] **Step 4: Crear `AndroidManifest.xml` con `MainActivity` y tema Material 3**
- [ ] **Step 5: Crear esqueleto de `MainActivity.kt` con `ComponentActivity` y tema base**
- [ ] **Step 6: Verificar compilación mediante `./gradlew assembleDebug` o tareas de test**
- [ ] **Step 7: Commit:** `git commit -m "chore: scaffold Android project with Jetpack Compose and dependencies"`

---

### Task 2: Modelo de Dominio y Parser No Destructivo `TodoMarkdownParser` (TDD)

**Files:**
- Create: `app/src/main/java/com/goplan/app/domain/model/Idea.kt`
- Create: `app/src/main/java/com/goplan/app/domain/model/TodoDocument.kt`
- Create: `app/src/main/java/com/goplan/app/domain/parser/TodoMarkdownParser.kt`
- Test: `app/src/test/java/com/goplan/app/domain/parser/TodoMarkdownParserTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class IdeaStatus(val marker: String) { PENDING("[ ]"), IN_PROGRESS("[/]"), COMPLETED("[x]"), DISCARDED("[-]") }
  data class PlanLink(val label: String, val relativePath: String)
  data class Idea(val id: String, val status: IdeaStatus, val title: String, val tags: Set<String>, val plans: List<PlanLink>, val rawLine: String)
  sealed interface TodoLine {
      data class Header(val rawText: String, val level: Int) : TodoLine
      data class Task(val idea: Idea) : TodoLine
      data class Other(val rawText: String) : TodoLine
  }
  data class TodoDocument(val lines: List<TodoLine>)
  class TodoMarkdownParser {
      fun parse(content: String): TodoDocument
      fun serialize(document: TodoDocument): String
      fun updateIdeaStatus(document: TodoDocument, ideaId: String, newStatus: IdeaStatus): TodoDocument
      fun linkPlansToIdea(document: TodoDocument, ideaId: String, plans: List<PlanLink>): TodoDocument
      fun addIdea(document: TodoDocument, title: String, tags: Set<String>): TodoDocument
  }
  ```

- [ ] **Step 1: Escribir tests unitarios fallidos en `TodoMarkdownParserTest.kt`**
  - Test 1: Parseo de tareas con estados `[ ]`, `[/]`, `[x]`, `[-]`.
  - Test 2: Extracción de enlace simple `[Título](planes/p1.md)`.
  - Test 3: Extracción de multi-enlaces `([UI](planes/ui.md), [API](planes/api.md))`.
  - Test 4: Extracción de etiquetas `#auth #backend`.
  - Test 5: Extracción de ID explícito en comentario HTML `<!-- id:a1b2 -->` y fallback a slug.
  - Test 6: Preservación exacta de encabezados (`# Sprint`), líneas en blanco y notas al serializar.
- [ ] **Step 2: Ejecutar test unitario para verificar fallo**
- [ ] **Step 3: Implementar entidades en `domain/model/` y la lógica en `TodoMarkdownParser.kt`**
- [ ] **Step 4: Ejecutar tests unitarios y verificar que pasen al 100%**
- [ ] **Step 5: Commit:** `git commit -m "feat(domain): implement non-destructive TodoMarkdownParser with TDD"`

---

### Task 3: `GeminiPlanParser` con Protección de Path Traversal y Generador de Prompts (TDD)

**Files:**
- Create: `app/src/main/java/com/goplan/app/domain/parser/GeminiPlanParser.kt`
- Create: `app/src/main/java/com/goplan/app/domain/usecase/PrepareGeminiPromptUseCase.kt`
- Test: `app/src/test/java/com/goplan/app/domain/parser/GeminiPlanParserTest.kt`
- Test: `app/src/test/java/com/goplan/app/domain/usecase/PrepareGeminiPromptUseCaseTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class ParsedPlanFile(val fileName: String, val content: String)
  class GeminiPlanParser {
      fun parsePlans(rawText: String, fallbackIdeaTitle: String): List<ParsedPlanFile>
      fun sanitizeFileName(rawFileName: String): String
  }
  class PrepareGeminiPromptUseCase {
      operator fun invoke(contextContent: String, idea: Idea): String
  }
  ```

- [ ] **Step 1: Escribir tests unitarios fallidos en `GeminiPlanParserTest.kt`**
  - Test 1: Troceado de texto con múltiples bloques `=== ARCHIVO: ... ===`.
  - Test 2: Sanitización contra *Path Traversal*: `../../plan.md` -> `plan.md`.
  - Test 3: Normalización de nombres a kebab-case válido terminando en `.md`.
  - Test 4: Modo fallback sin delimitadores genera `{slug}.md` con el texto íntegro.
- [ ] **Step 2: Escribir tests unitarios para `PrepareGeminiPromptUseCaseTest.kt` validando inclusión de CONTEXT.md, idea, tags y regla de formato estricto**
- [ ] **Step 3: Implementar `GeminiPlanParser.kt` y `PrepareGeminiPromptUseCase.kt`**
- [ ] **Step 4: Ejecutar tests y verificar que pasen al 100%**
- [ ] **Step 5: Commit:** `git commit -m "feat(domain): implement GeminiPlanParser with path traversal defense and prompt generator"`

---

### Task 4: Motor de Archivos de Workspace y `WorkspaceRepository`

**Files:**
- Create: `app/src/main/java/com/goplan/app/data/storage/WorkspaceFileManager.kt`
- Create: `app/src/main/java/com/goplan/app/data/repository/WorkspaceRepository.kt`
- Test: `app/src/test/java/com/goplan/app/data/repository/WorkspaceRepositoryTest.kt`

**Interfaces:**
- Consumes: `TodoMarkdownParser`, `TodoDocument`, `Idea`
- Produces:
  ```kotlin
  data class Workspace(val id: String, val name: String, val rootDir: File)
  class WorkspaceRepository(private val fileManager: WorkspaceFileManager, private val parser: TodoMarkdownParser) {
      fun getWorkspaces(): List<Workspace>
      fun createWorkspace(name: String): Workspace
      fun getTodoDocument(workspace: Workspace): TodoDocument
      fun saveTodoDocument(workspace: Workspace, document: TodoDocument)
      fun getContextContent(workspace: Workspace): String
      fun saveContextContent(workspace: Workspace, content: String)
      fun savePlanFile(workspace: Workspace, fileName: String, content: String): File
      fun readPlanFile(workspace: Workspace, relativePath: String): String
  }
  ```

- [ ] **Step 1: Escribir tests unitarios en `WorkspaceRepositoryTest.kt` usando carpeta temporal JVM**
  - Test 1: Crear workspace inicializa `CONTEXT.md`, `TODO.md` y carpeta `planes/`.
  - Test 2: Guardado de `TODO.md` mediante escritura atómica.
  - Test 3: Lectura y guardado de archivos en `planes/`.
- [ ] **Step 2: Implementar `WorkspaceFileManager.kt` con escritura atómica (`.tmp` + rename)**
- [ ] **Step 3: Implementar `WorkspaceRepository.kt`**
- [ ] **Step 4: Ejecutar tests y verificar que pasen**
- [ ] **Step 5: Commit:** `git commit -m "feat(data): implement WorkspaceFileManager and WorkspaceRepository with atomic writes"`

---

### Task 5: Preferencias y Loopback Cache con Jetpack DataStore

**Files:**
- Create: `app/src/main/java/com/goplan/app/data/preferences/PreferencesManager.kt`
- Test: `app/src/test/java/com/goplan/app/data/preferences/PreferencesManagerTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class RecentExpansion(val workspaceId: String, val ideaId: String, val timestampMillis: Long)
  class PreferencesManager(private val dataStore: DataStore<Preferences>) {
      suspend fun saveActiveWorkspace(workspaceId: String)
      fun getActiveWorkspace(): Flow<String?>
      suspend fun recordRecentExpansion(workspaceId: String, ideaId: String)
      suspend fun getRecentExpansion(maxAgeMinutes: Long = 30): RecentExpansion?
      suspend fun clearRecentExpansion()
  }
  ```

- [ ] **Step 1: Escribir tests unitarios en `PreferencesManagerTest.kt` comprobando caducidad de 30 minutos**
- [ ] **Step 2: Implementar `PreferencesManager.kt` con DataStore**
- [ ] **Step 3: Ejecutar tests y verificar que pasen**
- [ ] **Step 4: Commit:** `git commit -m "feat(data): implement PreferencesManager with 30-min loopback cache"`

---

### Task 6: Módulo de Exportación para Agentes (`AgentExportManager`)

**Files:**
- Create: `app/src/main/java/com/goplan/app/domain/export/AgentExportManager.kt`
- Test: `app/src/test/java/com/goplan/app/domain/export/AgentExportManagerTest.kt`

**Interfaces:**
- Consumes: `Workspace`, `Idea`, `WorkspaceRepository`
- Produces:
  ```kotlin
  class AgentExportManager(private val repository: WorkspaceRepository) {
      fun createZipBundle(workspace: Workspace, ideas: List<Idea>, outputZipFile: File): File
      fun createUnifiedMarkdown(workspace: Workspace, ideas: List<Idea>): String
  }
  ```

- [ ] **Step 1: Escribir tests unitarios en `AgentExportManagerTest.kt` validando contenido del ZIP (`CONTEXT.md`, fragmento de `TODO.md`, planes)**
- [ ] **Step 2: Implementar empaquetado ZIP con `java.util.zip.ZipOutputStream`**
- [ ] **Step 3: Implementar generador de Markdown unificado**
- [ ] **Step 4: Ejecutar tests y verificar que pasen**
- [ ] **Step 5: Commit:** `git commit -m "feat(domain): implement AgentExportManager with ZIP and unified Markdown export"`

---

### Task 7: UI - `WorkspaceScreen`, Lista de Ideas y `QuickCaptureBar`

**Files:**
- Create: `app/src/main/java/com/goplan/app/ui/workspace/WorkspaceViewModel.kt`
- Create: `app/src/main/java/com/goplan/app/ui/workspace/WorkspaceScreen.kt`
- Create: `app/src/main/java/com/goplan/app/ui/workspace/components/QuickCaptureBar.kt`
- Create: `app/src/main/java/com/goplan/app/ui/workspace/components/IdeaItemRow.kt`
- Create: `app/src/main/java/com/goplan/app/ui/workspace/components/FilterChipsRow.kt`

**Interfaces:**
- Produces:
  - TopBar con selector de Workspace, botón `CONTEXT.md` y exportación.
  - Filtros de estado (`Pendientes`, `En progreso`, `Completadas`, `Descartadas`) y tags `#tag`.
  - `IdeaItemRow` con swipe gestual para transiciones rápidas de estado y badges para planes vinculados.
  - `QuickCaptureBar` fijada al fondo con sugerencias automáticas de tags `#`.

- [ ] **Step 1: Implementar `WorkspaceViewModel.kt` con gestión reactiva de estado (`WorkspaceUiState`)**
- [ ] **Step 2: Implementar `QuickCaptureBar.kt` con autocompletado de `#`**
- [ ] **Step 3: Implementar `FilterChipsRow.kt` y `IdeaItemRow.kt` con swipe gesture**
- [ ] **Step 4: Integrar en `WorkspaceScreen.kt` con `LazyColumn`**
- [ ] **Step 5: Verificar renderizado y navegación**
- [ ] **Step 6: Commit:** `git commit -m "feat(ui): implement WorkspaceScreen, IdeaItemRow, and QuickCaptureBar"`

---

### Task 8: UI - `PlanViewerScreen` y Herramienta "Dividir desde el Cursor"

**Files:**
- Create: `app/src/main/java/com/goplan/app/ui/plan/PlanViewerViewModel.kt`
- Create: `app/src/main/java/com/goplan/app/ui/plan/PlanViewerScreen.kt`
- Create: `app/src/main/java/com/goplan/app/ui/plan/components/PlanMarkdownViewer.kt`
- Create: `app/src/main/java/com/goplan/app/ui/plan/components/SplitPlanDialog.kt`

**Interfaces:**
- Produces:
  - Pestañas multi-archivo (`ScrollableTabRow`) para ideas con múltiples planes.
  - Toggle entre modo lectura estilizado y editor de texto plano con barra auxiliar.
  - Diálogo y acción "Dividir desde el cursor" que corta el texto, crea un nuevo archivo en `planes/` y vincula a la idea en `TODO.md`.

- [ ] **Step 1: Implementar `PlanViewerViewModel.kt` (lectura, guardado y partición de planes)**
- [ ] **Step 2: Implementar `PlanMarkdownViewer.kt` para lectura con Material 3 y edición en texto**
- [ ] **Step 3: Implementar `SplitPlanDialog.kt` para nombrar el nuevo archivo extraído**
- [ ] **Step 4: Integrar en `PlanViewerScreen.kt`**
- [ ] **Step 5: Commit:** `git commit -m "feat(ui): implement PlanViewerScreen with multi-tabs and split from cursor"`

---

### Task 9: UI - `ContextEditorScreen` e Importación desde PC

**Files:**
- Create: `app/src/main/java/com/goplan/app/ui/context/ContextEditorViewModel.kt`
- Create: `app/src/main/java/com/goplan/app/ui/context/ContextEditorScreen.kt`

**Interfaces:**
- Produces:
  - Editor de texto para `CONTEXT.md` con plantilla inicial de arquitectura/stack.
  - Botón de importar/pegar archivo Markdown externo (preparado para recibir `CONTEXT.md` generado con Orca desde el PC).

- [ ] **Step 1: Implementar `ContextEditorViewModel.kt`**
- [ ] **Step 2: Implementar `ContextEditorScreen.kt` con acciones de guardado e importación**
- [ ] **Step 3: Commit:** `git commit -m "feat(ui): implement ContextEditorScreen with import/overwrite support"`

---

### Task 10: Integración de Android Intents (Salida a Gemini, Recepción y `ImportPlanBottomSheet`)

**Files:**
- Create: `app/src/main/java/com/goplan/app/ui/intent/ImportPlanBottomSheet.kt`
- Create: `app/src/main/java/com/goplan/app/ui/intent/IntentHandler.kt`
- Modify: `app/src/main/AndroidManifest.xml` (registrar `intent-filter` para `ACTION_SEND` text/plain y `FileProvider`)
- Create: `app/src/main/res/xml/file_paths.xml` (rutas para `FileProvider`)
- Modify: `app/src/main/java/com/goplan/app/MainActivity.kt` (conectar intents entrantes y salientes)

**Interfaces:**
- Consumes: `GeminiPlanParser`, `WorkspaceRepository`, `PreferencesManager`, `AgentExportManager`
- Produces:
  - Expansión fluida: `ACTION_SEND` a Gemini con prompt estructurado y guardado de `RecentExpansion`.
  - Recepción de Gemini: modal `ImportPlanBottomSheet` con preselección en 1 toque.
  - Detección especial de `CONTEXT.md` compartido para actualización directa.
  - Exportación de paquetes ZIP mediante `FileProvider` hacia LocalSend / Quick Share.

- [ ] **Step 1: Configurar `FileProvider` y `res/xml/file_paths.xml` en `AndroidManifest.xml`**
- [ ] **Step 2: Declarar `android:launchMode="singleTop"` e `intent-filter` para `ACTION_SEND` (`text/plain`, `text/markdown`, `text/*`) en `MainActivity`**
- [ ] **Step 3: Implementar `IntentHandler.kt` con soporte dual: leer texto plano de `Intent.EXTRA_TEXT` o leer streams de archivos adjuntos desde `Intent.EXTRA_STREAM` vía `contentResolver.openInputStream(uri)` (para recibir `CONTEXT.md` desde LocalSend)**
- [ ] **Step 4: Implementar `ImportPlanBottomSheet.kt` con preselección inteligente y confirmación en un toque**
- [ ] **Step 5: Conectar navegación y ciclo de vida en `MainActivity.kt`, capturando intents tanto en `onCreate` como sobrescribiendo `onNewIntent(intent: Intent)`**
- [ ] **Step 6: Verificar el flujo completo de extremo a extremo**
- [ ] **Step 7: Commit:** `git commit -m "feat(intent): wire outbound Gemini intent and inbound ImportPlanBottomSheet with singleTop and stream support"`
