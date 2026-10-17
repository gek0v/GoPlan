# GoPlan Fase 2 (Servidor Local Ktor y Sincronización Google Drive) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar un servidor HTTP embebido local (Ktor CIO) para comunicar herramientas y agentes de PC directamente con GoPlan vía Wi-Fi, junto con un motor de sincronización bidireccional en la nube con Google Drive.

**Architecture:** Servidor REST Ktor embebido en corrutinas acotado al ciclo de vida en primer plano de la app, con prevención de suspensión de pantalla (`keepScreenOn`). Repositorio central serializado mediante `Mutex` con emisión reactiva por `SharedFlow` para refrescar la UI al instante ante escrituras externas. Motor de sincronización desacoplado con Google Drive (`DriveScopes.DRIVE`), detección de cambios mediante SHA-256 y fusión no destructiva de tareas.

**Tech Stack:** Kotlin 2.0.21, Android SDK 35 (minSdk 26), Jetpack Compose (Material 3), Ktor Server 3.0.0 (CIO, ContentNegotiation, Serialization JSON, CORS, StatusPages), `ktor-server-test-host`, Google Play Services Auth 21.2.0, Google Drive API v3 (`2.6.0` / `v3-rev20240530-2.0.0`), Jetpack DataStore Preferences, JUnit 4, Kotlinx Coroutines Test.

**Spec:** `docs/superpowers/specs/2026-10-15-goplan-fase2-server-and-drive-design.md`

## Global Constraints

- Android minSdk 26, targetSdk 35, Java 17 compatibility.
- Ktor Server se ejecuta estrictamente mientras la app está abierta en pantalla (sin `ForegroundService`).
- La UI mantiene la pantalla encendida (`keepScreenOn = true`) mediante un `DisposableEffect` en Compose mientras el servidor esté activo.
- Todas las operaciones de lectura/escritura en `WorkspaceRepository` se serializan con un `kotlinx.coroutines.sync.Mutex` interno y emiten en `workspaceChangesFlow: SharedFlow<String>`.
- `WorkspaceViewModel` observa `workspaceChangesFlow` para refrescar el estado reactivamente cuando Ktor guarde datos.
- Sincronización de Google Drive utiliza `DriveScopes.DRIVE` para permitir lectura/escritura bidireccional de archivos creados desde PC.
- Si las credenciales de Google Cloud OAuth no están configuradas o el usuario no inicia sesión, GoPlan y Ktor funcionan al 100% de manera autónoma sin errores bloqueantes.
- En `app/build.gradle.kts`, `packaging.resources.excludes` descarta `META-INF/DEPENDENCIES`, `META-INF/INDEX.LIST`, `META-INF/io.netty.*`, etc.
- Todas las pruebas de rutas HTTP de Ktor se ejecutan en JVM puro usando `ktor-server-test-host` sin emuladores.

## Review Focus

1. **Condición de carrera entre Ktor y UI:** Comprobar que una escritura simultánea en `TODO.md` vía `POST /api/workspaces/{id}/plans` no corrompa el archivo mientras el usuario interactúa en la pantalla móvil.
2. **Refresco reactivo en pantalla:** Verificar que tras un `POST` o `PATCH` de Ktor, la UI en Compose recargue la lista de tareas automáticamente sin requerir interacción manual.
3. **Suspensión de pantalla mientras el PC envía peticiones:** Asegurar que `keepScreenOn` se active cuando el servidor está encendido y se desactive cuando se pausa o se sale de la pantalla.
4. **Desacoplamiento si Google Cloud OAuth falla o falta configuración:** Comprobar que si el usuario no tiene configurado Google Drive o cancela el login, la app no lance excepciones no capturadas ni degrade las funciones locales.
5. **Path Traversal en peticiones HTTP:** Verificar que peticiones con `fileName = "../../../secret.txt"` en rutas de planes sean neutralizadas a nombres kebab-case seguros terminados en `.md`.

---

### Task 1: Concurrencia y Reactividad en WorkspaceRepository (Mutex + SharedFlow) (TDD)

**Files:**
- Modify: `app/src/main/java/com/goplan/app/data/repository/WorkspaceRepository.kt`
- Modify: `app/src/main/java/com/goplan/app/ui/workspace/WorkspaceViewModel.kt`
- Test: `app/src/test/java/com/goplan/app/data/repository/WorkspaceRepositoryTest.kt`
- Test: `app/src/test/java/com/goplan/app/ui/workspace/WorkspaceViewModelTest.kt`

**Interfaces:**
- Produces in `WorkspaceRepository`:
  - `val workspaceChangesFlow: SharedFlow<String>`
  - `suspend fun emitWorkspaceChange(workspaceId: String)`
  - Internal `val fileMutex: Mutex = Mutex()` protegiendo `saveTodoDocument`, `saveContextContent`, `savePlanFile`, `readPlanFile`, `getTodoDocument`, `getContextContent`.
- Consumes in `WorkspaceViewModel`:
  - Observa `workspaceRepository.workspaceChangesFlow` y si el `workspaceId` coincide con el activo, recarga las ideas automáticamente con `loadWorkspace(activeWorkspace)`.

- [ ] **Step 1: Write the failing tests in WorkspaceRepositoryTest and WorkspaceViewModelTest**

En `WorkspaceRepositoryTest.kt`:
```kotlin
@Test
fun testWorkspaceChangesFlowEmitsOnSave() = runTest {
    val tempDir = Files.createTempDirectory("goplan_repo_test").toFile()
    val fileManager = WorkspaceFileManager(tempDir)
    val parser = TodoMarkdownParser()
    val repository = WorkspaceRepository(fileManager, parser)
    val workspace = repository.createWorkspace("Reactivo")

    val emittedList = mutableListOf<String>()
    val job = launch {
        repository.workspaceChangesFlow.collect { emittedList.add(it) }
    }

    repository.saveContextContent(workspace, "# Nuevo Contexto")
    assertEquals(listOf("Reactivo"), emittedList)

    job.cancel()
    tempDir.deleteRecursively()
}
```

En `WorkspaceViewModelTest.kt`:
```kotlin
@Test
fun testViewModelRefreshesWhenWorkspaceChangesFlowEmits() = runTest {
    // Verificar que al emitirse el id del workspace activo, el estado se actualiza automáticamente
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.goplan.app.data.repository.WorkspaceRepositoryTest.testWorkspaceChangesFlowEmitsOnSave"`
Expected: FAIL (unresolved reference `workspaceChangesFlow`).

- [ ] **Step 3: Implement Mutex and workspaceChangesFlow in WorkspaceRepository and WorkspaceViewModel**

En `WorkspaceRepository.kt`:
- Declarar `private val _workspaceChangesFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)` y `val workspaceChangesFlow: SharedFlow<String> = _workspaceChangesFlow.asSharedFlow()`.
- Declarar `private val fileMutex = Mutex()`.
- Envolver escrituras y lecturas críticas con `fileMutex.withLock { ... }`.
- Emitir `_workspaceChangesFlow.tryEmit(workspace.id)` tras guardar `TODO.md`, `CONTEXT.md` o planes.

En `WorkspaceViewModel.kt`:
- En `init`, lanzar corrutina en `viewModelScope` que recolecte `repository.workspaceChangesFlow` y si `it == _uiState.value.activeWorkspace?.id`, recargue el workspace.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: 100% PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/goplan/app/data/repository/WorkspaceRepository.kt app/src/main/java/com/goplan/app/ui/workspace/WorkspaceViewModel.kt app/src/test/java/com/goplan/app/data/repository/WorkspaceRepositoryTest.kt app/src/test/java/com/goplan/app/ui/workspace/WorkspaceViewModelTest.kt
git commit -m "feat(repository): add Mutex serialization and reactive workspaceChangesFlow"
```

---

### Task 2: Configuración de Dependencias Ktor y Exclusiones de Empaquetado

**Files:**
- Modify: `build.gradle.kts`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Agrega plugins: `org.jetbrains.kotlin.plugin.serialization` versión `2.0.21`.
- Agrega dependencias en `app/build.gradle.kts`:
  - `io.ktor:ktor-server-core:3.0.0`
  - `io.ktor:ktor-server-cio:3.0.0`
  - `io.ktor:ktor-server-content-negotiation:3.0.0`
  - `io.ktor:ktor-serialization-kotlinx-json:3.0.0`
  - `io.ktor:ktor-server-cors:3.0.0`
  - `io.ktor:ktor-server-status-pages:3.0.0`
  - `testImplementation("io.ktor:ktor-server-test-host:3.0.0")`
- Configura `packaging.resources.excludes` en `app/build.gradle.kts`.

- [ ] **Step 1: Update build.gradle.kts and app/build.gradle.kts**

En `build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
```

En `app/build.gradle.kts`:
- Aplicar `id("org.jetbrains.kotlin.plugin.serialization")`.
- Configurar bloque `packaging`:
```kotlin
packaging {
    resources {
        excludes += listOf(
            "META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }
}
```
- Agregar dependencias de Ktor y kotlinx-serialization-json.

- [ ] **Step 2: Verify compilation and tests**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: BUILD SUCCESSFUL (dependencias resueltas y empaquetado verificado).

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts app/build.gradle.kts
git commit -m "build: add Ktor server dependencies and packaging resource exclusions"
```

---

### Task 3: DTOs y Rutas REST del Servidor Ktor (TDD con ktor-server-test-host)

**Files:**
- Create: `app/src/main/java/com/goplan/app/server/dto/ServerDtos.kt`
- Create: `app/src/main/java/com/goplan/app/server/routes/ServerRoutes.kt`
- Create: `app/src/main/java/com/goplan/app/server/KtorModule.kt`
- Test: `app/src/test/java/com/goplan/app/server/KtorServerRoutesTest.kt`

**Interfaces:**
- Produces:
  - DTOs serializables con `@Serializable`:
    - `ServerStatusResponse(val status: String, val app: String, val version: String, val activeWorkspace: String?, val ip: String?, val port: Int)`
    - `WorkspaceDto(val id: String, val name: String, val stats: WorkspaceStatsDto)`
    - `WorkspaceStatsDto(val total: Int, val pending: Int, val inProgress: Int, val completed: Int, val discarded: Int)`
    - `ContextDto(val workspaceId: String, val content: String)`
    - `TodoResponseDto(val rawMarkdown: String, val ideas: List<IdeaDto>)`
    - `IdeaDto(val id: String, val title: String, val status: String, val tags: List<String>, val plans: List<PlanLinkDto>)`
    - `PlanLinkDto(val label: String, val relativePath: String)`
    - `CreateWorkspaceRequest(val name: String)`
    - `CreateIdeaRequest(val text: String)`
    - `UpdateIdeaRequest(val status: String? = null, val title: String? = null, val tags: List<String>? = null)`
    - `IngestPlanRequest(val ideaId: String, val files: List<PlanFileDto>? = null, val rawText: String? = null)`
    - `PlanFileDto(val fileName: String, val content: String)`
    - `ApiResponse(val success: Boolean, val message: String? = null)`
  - `fun Application.configureServer(repository: WorkspaceRepository, preferencesManager: PreferencesManager, getIpAddress: () -> String)`

- [ ] **Step 1: Write failing integration test using testApplication**

En `app/src/test/java/com/goplan/app/server/KtorServerRoutesTest.kt`:
```kotlin
class KtorServerRoutesTest {
    @Test
    fun testGetStatusReturns200AndJson() = testApplication {
        // Setup temporary repository and configureServer
        // client.get("/api/status") -> assert 200 OK and online status
    }

    @Test
    fun testWorkspaceAndTodoLifecycle() = testApplication {
        // client.post("/api/workspaces") -> create workspace
        // client.get("/api/workspaces") -> list workspaces
        // client.post("/api/workspaces/Alpha/ideas") -> add idea
        // client.get("/api/workspaces/Alpha/todo") -> assert idea present
        // client.patch("/api/workspaces/Alpha/ideas/{id}") -> complete idea
        // client.delete("/api/workspaces/Alpha/ideas/{id}") -> delete idea
    }

    @Test
    fun testIngestPlanAndPathTraversalDefense() = testApplication {
        // client.post("/api/workspaces/Alpha/plans") with files
        // assert plan created in planes/ and TODO.md updated
        // client.get("/api/workspaces/Alpha/plans/plan.md") -> returns markdown
        // client.get("/api/workspaces/Alpha/plans/../../secret.txt") -> returns 400 Bad Request
    }

    @Test
    fun testExportZipAndMarkdown() = testApplication {
        // client.get("/api/workspaces/Alpha/export?type=zip") -> returns zip binary
        // client.get("/api/workspaces/Alpha/export?type=md") -> returns unified text
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.goplan.app.server.KtorServerRoutesTest"`
Expected: FAIL (classes not found).

- [ ] **Step 3: Implement ServerDtos, ServerRoutes, and KtorModule**

- Crear `ServerDtos.kt` con `@Serializable` de `kotlinx.serialization`.
- Crear `ServerRoutes.kt` con todas las rutas detalladas en la especificación (`/api/status`, `/api/workspaces`, `/api/workspaces/{id}/context`, `/api/workspaces/{id}/todo`, `/api/workspaces/{id}/ideas`, `/api/workspaces/{id}/plans`, `/api/workspaces/{id}/export`).
- Crear `KtorModule.kt` con:
  - `install(ContentNegotiation) { json(...) }`
  - `install(CORS) { anyHost(); allowHeader(...); allowMethod(...) }`
  - `install(StatusPages) { exception<Throwable> { call, cause -> call.respond(HttpStatusCode.InternalServerError, ApiResponse(false, cause.message)) } }`
  - Inyección de rutas.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.goplan.app.server.KtorServerRoutesTest"`
Expected: 100% PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/goplan/app/server/ app/src/test/java/com/goplan/app/server/
git commit -m "feat(server): implement REST DTOs and routes with ktor-server-test-host verification"
```

---

### Task 4: Motor KtorServerManager y Utilidades de Red (NetworkUtils)

**Files:**
- Create: `app/src/main/java/com/goplan/app/server/NetworkUtils.kt`
- Create: `app/src/main/java/com/goplan/app/server/KtorServerManager.kt`
- Modify: `app/src/main/java/com/goplan/app/GoPlanApplication.kt`
- Test: `app/src/test/java/com/goplan/app/server/NetworkUtilsTest.kt`
- Test: `app/src/test/java/com/goplan/app/server/KtorServerManagerTest.kt`

**Interfaces:**
- Produces:
  - `object NetworkUtils`:
    - `fun getLocalIpv4Address(): String?`: Recorre `NetworkInterface.getNetworkInterfaces()` buscando interfaces no loopback activas con IPv4 local (ej. `192.168.x.x` o `10.x.x.x`).
  - `class KtorServerManager(val repository: WorkspaceRepository, val preferencesManager: PreferencesManager)`:
    - `val isRunning: StateFlow<Boolean>`
    - `val serverUrl: StateFlow<String?>`
    - `fun start(port: Int = 8080): Result<String>`: Arranca `embeddedServer(CIO, port = port, host = "0.0.0.0")`. Si el puerto falla, intenta fallback `8081`.
    - `fun stop()`: Detiene el engine con grace period limpio de 500ms.
- Modifies `GoPlanApplication`:
  - Expone lazy singleton: `val ktorServerManager: KtorServerManager by lazy { KtorServerManager(workspaceRepository, preferencesManager) }`.

- [ ] **Step 1: Write unit tests for NetworkUtils and KtorServerManager**

En `KtorServerManagerTest.kt`:
- Verificar transición de estados `isRunning` (falso al inicio, verdadero al llamar `start()`, falso al llamar `stop()`).
- Verificar que `serverUrl` expone la URL con el puerto asignado.

- [ ] **Step 2: Run tests to verify failure**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.goplan.app.server.KtorServerManagerTest"`
Expected: FAIL.

- [ ] **Step 3: Implement NetworkUtils, KtorServerManager, and GoPlanApplication wiring**

- Implementar `NetworkUtils` con búsqueda de direcciones `Inet4Address` no loopback.
- Implementar `KtorServerManager` gestionando la instancia de `CIOApplicationEngine` en una corrutina controlada.
- Añadir `ktorServerManager` en `GoPlanApplication`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.goplan.app.server.KtorServerManagerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/goplan/app/server/ app/src/main/java/com/goplan/app/GoPlanApplication.kt app/src/test/java/com/goplan/app/server/
git commit -m "feat(server): implement KtorServerManager and NetworkUtils with lifecycle controls"
```

---

### Task 5: UI - Control del Servidor, Diálogo de Conexión PC y keepScreenOn

**Files:**
- Create: `app/src/main/java/com/goplan/app/ui/server/ServerConnectionDialog.kt`
- Modify: `app/src/main/java/com/goplan/app/ui/workspace/WorkspaceScreen.kt`
- Modify: `app/src/main/java/com/goplan/app/ui/workspace/WorkspaceViewModel.kt`
- Modify: `app/src/main/java/com/goplan/app/MainActivity.kt`

**Interfaces:**
- Produces:
  - `ServerConnectionDialog(serverUrl: String?, isRunning: Boolean, onToggleServer: (Boolean) -> Unit, onDismiss: () -> Unit)`
- Modifies:
  - `WorkspaceScreen.kt`: Añade icono/botón de red en `TopAppBar`. Si el servidor está activo, icono verde destacado; si inactivo, icono apagado. Muestra `ServerConnectionDialog` al pulsar.
  - Implementa `DisposableEffect(isServerRunning)`:
    ```kotlin
    val view = LocalView.current
    DisposableEffect(isServerRunning) {
        view.keepScreenOn = isServerRunning
        onDispose {
            view.keepScreenOn = false
        }
    }
    ```
  - `MainActivity.kt`: En `onStart`, arranca el servidor si la preferencia del usuario está habilitada; en `onStop`, detiene el servidor para garantizar que solo corre cuando la app está abierta en pantalla.

- [ ] **Step 1: Implement ServerConnectionDialog and ViewModel integration**

- En `WorkspaceViewModel`: expone `isServerRunning: StateFlow<Boolean>` y `serverUrl: StateFlow<String?>` delegando en `ktorServerManager`. Añade `fun toggleServer(enable: Boolean)`.
- Diseñar `ServerConnectionDialog.kt` con Material 3:
  - Muestra dirección IP:puerto destacada (ej. `http://192.168.1.45:8080`).
  - Botón "Copiar URL" al portapapeles con feedback instantáneo.
  - Switch para encender/apagar el servidor.
  - Nota informativa indicando: "La pantalla se mantendrá encendida mientras el servidor esté activo para facilitar la comunicación con tu PC."

- [ ] **Step 2: Connect keepScreenOn and TopAppBar in WorkspaceScreen**

- Añadir acción `IconButton(onClick = { showServerDialog = true })` en `TopAppBar` de `WorkspaceScreen`.
- Añadir el `DisposableEffect` con `LocalView.current.keepScreenOn = isServerRunning`.

- [ ] **Step 3: Connect MainActivity lifecycle**

- En `MainActivity.onStart()`: llamar a `app.ktorServerManager.start()` (si está configurado como activo por defecto).
- En `MainActivity.onStop()`: llamar a `app.ktorServerManager.stop()`.

- [ ] **Step 4: Verify with unit tests and assembleDebug**

Run: `.\gradlew.bat testDebugUnitTest` y `.\gradlew.bat assembleDebug`
Expected: 100% PASS, BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/goplan/app/ui/server/ app/src/main/java/com/goplan/app/ui/workspace/ app/src/main/java/com/goplan/app/MainActivity.kt
git commit -m "feat(ui): add server connection dialog, top bar status, and keepScreenOn effect"
```

---

### Task 6: Dependencias de Google Play Services Auth y Google Drive API v3

**Files:**
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Agrega dependencias:
  - `implementation("com.google.android.gms:play-services-auth:21.2.0")`
  - `implementation("com.google.api-client:google-api-client-android:2.6.0")`
  - `implementation("com.google.apis:google-api-services-drive:v3-rev20240530-2.0.0")`
- Asegura que `packaging.resources.excludes` resuelva todas las posibles colisiones de clases o recursos.

- [ ] **Step 1: Add dependencies to app/build.gradle.kts**

- Incluir las 3 dependencias de Google Auth y Drive v3.

- [ ] **Step 2: Verify compilation**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL (sin colisiones de recursos duplicados ni errores Dex).

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: add Google Play Services Auth and Google Drive API v3 dependencies"
```

---

### Task 7: GoogleDriveAuthManager Desacoplado con Alcance DRIVE (TDD)

**Files:**
- Create: `app/src/main/java/com/goplan/app/data/sync/GoogleDriveAuthManager.kt`
- Modify: `app/src/main/java/com/goplan/app/GoPlanApplication.kt`
- Test: `app/src/test/java/com/goplan/app/data/sync/GoogleDriveAuthManagerTest.kt`

**Interfaces:**
- Produces:
  - `class GoogleDriveAuthManager(val context: Context)`:
    - `val signedInAccount: StateFlow<GoogleSignInAccount?>`
    - `fun getSignInIntent(): Intent`: Genera intent con `GoogleSignInOptions.Builder` solicitando `Scope(DriveScopes.DRIVE)` y `requestEmail()`.
    - `fun handleSignInResult(data: Intent?): Result<GoogleSignInAccount>`
    - `fun getDriveService(account: GoogleSignInAccount): Drive`
    - `fun signOut(onComplete: () -> Unit)`
    - `fun isConfigured(): Boolean`: Manejo limpio para no colapsar si Google Cloud Console no está configurado.

- [ ] **Step 1: Write unit tests for GoogleDriveAuthManager**

En `GoogleDriveAuthManagerTest.kt`:
- Verificar que el estado inicial de cuenta sea `null`.
- Verificar manejo defensivo cuando `data` del intent es inválido o nulo (devuelve `Result.failure` descriptivo en lugar de lanzar excepción).

- [ ] **Step 2: Run test to verify failure**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.goplan.app.data.sync.GoogleDriveAuthManagerTest"`
Expected: FAIL.

- [ ] **Step 3: Implement GoogleDriveAuthManager and wire in GoPlanApplication**

- Implementar `GoogleDriveAuthManager` con `GoogleSignIn.getClient`.
- Usar `Scope(DriveScopes.DRIVE)`.
- Instanciar `Drive.Builder(AndroidHttp.newCompatibleTransport(), GsonFactory.getDefaultInstance(), credential)` con `setApplicationName("GoPlan")`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.goplan.app.data.sync.GoogleDriveAuthManagerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/goplan/app/data/sync/GoogleDriveAuthManager.kt app/src/main/java/com/goplan/app/GoPlanApplication.kt app/src/test/java/com/goplan/app/data/sync/GoogleDriveAuthManagerTest.kt
git commit -m "feat(sync): implement decoupled GoogleDriveAuthManager with DriveScopes.DRIVE"
```

---

### Task 8: Motor de Sincronización GoogleDriveSyncEngine y Resolución de Conflictos (TDD)

**Files:**
- Create: `app/src/main/java/com/goplan/app/data/sync/model/SyncModels.kt`
- Create: `app/src/main/java/com/goplan/app/data/sync/GoogleDriveSyncEngine.kt`
- Test: `app/src/test/java/com/goplan/app/data/sync/GoogleDriveSyncEngineTest.kt`

**Interfaces:**
- Produces:
  - `data class SyncState(val lastSyncMillis: Long, val fileHashes: Map<String, String>)`
  - `sealed class SyncResult { object Success : SyncResult(); data class Error(val message: String) : SyncResult() }`
  - `class GoogleDriveSyncEngine(val repository: WorkspaceRepository, val authManager: GoogleDriveAuthManager)`:
    - `fun computeSha256(content: String): String`
    - `fun mergeTodoDocuments(local: TodoDocument, remote: TodoDocument): TodoDocument`: Fusión no destructiva uniendo ideas por ID o título, priorizando estados avanzados (`COMPLETED` > `IN_PROGRESS` > `PENDING`), y combinando planes únicos.
    - `suspend fun syncWorkspace(workspace: Workspace): SyncResult`

- [ ] **Step 1: Write unit tests for SHA-256 calculation and non-destructive TODO.md merge**

En `GoogleDriveSyncEngineTest.kt`:
```kotlin
@Test
fun testComputeSha256IsConsistent() {
    val hash1 = GoogleDriveSyncEngine.computeSha256("Hola Mundo")
    val hash2 = GoogleDriveSyncEngine.computeSha256("Hola Mundo")
    assertEquals(hash1, hash2)
}

@Test
fun testMergeTodoDocumentsPrefersAdvancedStatusAndCombinesPlans() {
    val localDoc = parser.parseTodoContent("- [/] [Idea 1](planes/plan1.md) #tag1")
    val remoteDoc = parser.parseTodoContent("- [x] [Idea 1](planes/plan2.md) #tag1 #remote\n- [ ] Idea 2")
    
    val merged = engine.mergeTodoDocuments(localDoc, remoteDoc)
    
    // Assert Idea 1 has status COMPLETED [x], both plan1 and plan2, and all tags
    // Assert Idea 2 is preserved
}
```

- [ ] **Step 2: Run tests to verify failure**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.goplan.app.data.sync.GoogleDriveSyncEngineTest"`
Expected: FAIL.

- [ ] **Step 3: Implement SyncModels and GoogleDriveSyncEngine**

- Implementar `computeSha256(content: String)` con `MessageDigest.getInstance("SHA-256")`.
- Implementar `mergeTodoDocuments`: une listas de `Idea`, combina planes (`distinctBy { it.relativePath }`), une etiquetas (`local.tags + remote.tags`), y selecciona el estado de mayor prioridad ordinal.
- Implementar `syncWorkspace(workspace)`:
  - Localiza o crea carpeta remota `GoPlan/<Workspace>/` en Drive.
  - Compara hashes de `CONTEXT.md`, `TODO.md` y `planes/*.md`.
  - Sube archivos locales nuevos o modificados.
  - Descarga archivos remotos nuevos o modificados con escritura atómica.
  - En caso de conflicto en planes o `CONTEXT.md`, crea `<name>.conflict-<timestamp>.md` local y descarga la versión remota.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.goplan.app.data.sync.GoogleDriveSyncEngineTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/goplan/app/data/sync/ app/src/test/java/com/goplan/app/data/sync/
git commit -m "feat(sync): implement GoogleDriveSyncEngine with SHA-256 diffing and non-destructive TODO merge"
```

---

### Task 9: UI de Sincronización en WorkspaceScreen y Verificación Final

**Files:**
- Create: `app/src/main/java/com/goplan/app/ui/sync/GoogleDriveSyncDialog.kt`
- Modify: `app/src/main/java/com/goplan/app/ui/workspace/WorkspaceScreen.kt`
- Modify: `app/src/main/java/com/goplan/app/ui/workspace/WorkspaceViewModel.kt`
- Modify: `app/src/main/java/com/goplan/app/MainActivity.kt`

**Interfaces:**
- Produces:
  - `GoogleDriveSyncDialog(account: GoogleSignInAccount?, isSyncing: Boolean, lastSyncRelativeTime: String?, onSignIn: () -> Unit, onSignOut: () -> Unit, onSyncNow: () -> Unit, onDismiss: () -> Unit)`
- Modifies:
  - `WorkspaceViewModel`: Expone `isSyncing: StateFlow<Boolean>`, `lastSyncTimestamp: StateFlow<Long?>`, `syncError: StateFlow<String?>`, y `fun syncActiveWorkspace()`.
  - `WorkspaceScreen`: Añade botón de nube (`Icons.Default.CloudSync` o `CloudDone`) en `TopAppBar` que rota cuando `isSyncing` está activo y abre el diálogo.
  - `MainActivity`: Registra `rememberLauncherForActivityResult(StartActivityForResult)` para el flujo de inicio de sesión de Google Play Services.

- [ ] **Step 1: Implement GoogleDriveSyncDialog composable**

- Diseñar diálogo con Material 3:
  - Si no hay cuenta vinculada: Botón "Iniciar sesión con Google Drive", con texto explicativo que aclara que requiere habilitar la API en Google Cloud Console.
  - Si hay cuenta vinculada: Muestra avatar/correo del usuario, última sincronización ("Hace 5 minutos"), botón destacado "Sincronizar ahora" y botón "Cerrar sesión".

- [ ] **Step 2: Connect Sync UI in WorkspaceScreen and WorkspaceViewModel**

- En `WorkspaceViewModel`: añadir llamadas a `syncEngine.syncWorkspace(activeWorkspace)` en `Dispatchers.IO`.
- En `WorkspaceScreen`: integrar icono en `TopAppBar` con diálogo `GoogleDriveSyncDialog`.

- [ ] **Step 3: Connect Google Sign-In launcher in MainActivity**

- Manejar el resultado de `GoogleSignIn.getSignedInAccountFromIntent(intent)` en `MainActivity` y notificar a `GoogleDriveAuthManager`.

- [ ] **Step 4: Execute full test suite and assemble APK**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: 100% PASS (todos los tests de Fase 1 y Fase 2 en verde).
Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL (APK generado en `app/build/outputs/apk/debug/app-debug.apk`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/goplan/app/ui/sync/ app/src/main/java/com/goplan/app/ui/workspace/ app/src/main/java/com/goplan/app/MainActivity.kt
git commit -m "feat(ui): add Google Drive sync dialog, cloud action in top bar, and sign-in launcher"
```
