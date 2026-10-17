# Rediseño de Selección y Creación de Workspaces (Modal Bottom Sheet) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sustituir el menú desplegable y diálogo emergente de workspaces por un Modal Bottom Sheet moderno con tarjetas modulares, conteo de ideas/planes en vivo y formulario de creación inline con borde discontinuo.

**Architecture:** Se introduce el modelo `WorkspaceSummary` en la capa de dominio. `WorkspaceRepository` calcula los conteos de tareas pendientes, en curso y planes en disco bajo serialización con `Mutex`. `WorkspaceViewModel` expone de forma reactiva `workspaceSummaries` y gestiona la apertura del sheet y la creación/conmutación de espacios con validación de nombres. En la capa de presentación, `WorkspaceChip` en `TopAppBar` abre `WorkspaceSelectionBottomSheet` con animaciones fluidas de Jetpack Compose Material 3.

**Tech Stack:** Kotlin 2.0, Jetpack Compose Material 3 (`ModalBottomSheet`, `Surface`, `LazyColumn`), Kotlin Coroutines & StateFlow, JUnit 4 & Turbine para pruebas unitarias.

**Spec:** [`docs/superpowers/specs/2026-10-17-workspace-bottom-sheet-redesign.md`](file:///C:/Users/Geko/orca/workspaces/G0Plan/driftfish/docs/superpowers/specs/2026-10-17-workspace-bottom-sheet-redesign.md)

## Global Constraints

- Material 3 ModalBottomSheet con esquinas redondeadas y pull handle estándar.
- No utilizar AlertDialogs emergentes para la creación de workspaces; la creación debe ser inline dentro del bottom sheet.
- Validación de nombres: rechazar cadenas vacías o solo espacios, nombres duplicados (case-insensitive) y caracteres prohibidos en sistemas de archivos (`\ / : * ? " < > |`).
- Compatibilidad hacia atrás: todas las pruebas unitarias existentes (161+ tests) deben continuar pasando al 100%.

## Review Focus

- Intento de creación con nombre duplicado con diferente capitalización (ej. "Default" vs "default"): el sistema debe rechazarlo con mensaje de error inline y mantener abierto el formulario.
- Intento de creación con caracteres no válidos de sistema de archivos (ej. "proyecto/nuevo"): debe advertir y prevenir la creación sin crashear.
- Apertura del sheet con workspaces que no tienen carpeta `planes/` o archivo `TODO.md` vacío: debe reportar conteos en 0 sin lanzar `NullPointerException` ni excepciones de I/O.
- Conmutación rápida entre workspaces: debe actualizar el estado activo, refrescar `TODO.md` y `CONTEXT.md` del nuevo espacio y cerrar el Bottom Sheet suavemente.
- Gestión de teclado virtual (IME): el formulario inline expandido debe respetar `imePadding` para no quedar tapado por el teclado.

---

### Task 1: Modelo `WorkspaceSummary` y Cálculo de Métricas en `WorkspaceRepository` (TDD)

**Files:**
- Create: `app/src/main/java/com/goplan/app/domain/model/WorkspaceSummary.kt`
- Modify: `app/src/main/java/com/goplan/app/data/repository/WorkspaceRepository.kt:80-91`
- Test: `app/src/test/java/com/goplan/app/data/repository/WorkspaceRepositoryTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class WorkspaceSummary(
      val workspace: Workspace,
      val isActive: Boolean,
      val pendingIdeasCount: Int,
      val inProgressIdeasCount: Int,
      val plansCount: Int
  )

  fun WorkspaceRepository.getWorkspaceSummary(workspace: Workspace, isActive: Boolean): WorkspaceSummary
  fun WorkspaceRepository.getAllWorkspaceSummaries(activeWorkspaceId: String?): List<WorkspaceSummary>
  ```

- [ ] **Step 1: Write the failing tests in `WorkspaceRepositoryTest.kt`**

```kotlin
@Test
fun testGetWorkspaceSummary_calculatesCorrectCounts() {
    val ws = repository.createWorkspace("metrics-test")
    val todoDoc = TodoDocument(
        ideas = listOf(
            Idea(id = "1", title = "P1", status = IdeaStatus.PENDING),
            Idea(id = "2", title = "P2", status = IdeaStatus.PENDING),
            Idea(id = "3", title = "IP1", status = IdeaStatus.IN_PROGRESS),
            Idea(id = "4", title = "D1", status = IdeaStatus.COMPLETED)
        )
    )
    repository.saveTodoDocument(ws, todoDoc)
    repository.savePlanFile(ws, "plan_1.md", "# Plan 1")
    repository.savePlanFile(ws, "plan_2.md", "# Plan 2")

    val summary = repository.getWorkspaceSummary(ws, isActive = true)

    assertEquals(ws.id, summary.workspace.id)
    assertTrue(summary.isActive)
    assertEquals(2, summary.pendingIdeasCount)
    assertEquals(1, summary.inProgressIdeasCount)
    assertEquals(2, summary.plansCount)
}

@Test
fun testGetAllWorkspaceSummaries_marksActiveCorrectly() {
    val ws1 = repository.createWorkspace("ws-alpha")
    val ws2 = repository.createWorkspace("ws-beta")

    val summaries = repository.getAllWorkspaceSummaries(activeWorkspaceId = ws1.id)

    assertEquals(2, summaries.size)
    val s1 = summaries.first { it.workspace.id == ws1.id }
    val s2 = summaries.first { it.workspace.id == ws2.id }
    assertTrue(s1.isActive)
    assertFalse(s2.isActive)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.goplan.app.data.repository.WorkspaceRepositoryTest.testGetWorkspaceSummary_calculatesCorrectCounts"`
Expected: FAIL (compilation error, `WorkspaceSummary` does not exist).

- [ ] **Step 3: Implement `WorkspaceSummary.kt` and calculation functions in `WorkspaceRepository.kt`**

1. Create `app/src/main/java/com/goplan/app/domain/model/WorkspaceSummary.kt` with fields `workspace`, `isActive`, `pendingIdeasCount`, `inProgressIdeasCount`, `plansCount`.
2. Implement in `WorkspaceRepository.kt`:
   - `fun getWorkspaceSummary(workspace: Workspace, isActive: Boolean): WorkspaceSummary`
   - `fun getAllWorkspaceSummaries(activeWorkspaceId: String?): List<WorkspaceSummary>`

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.goplan.app.data.repository.WorkspaceRepositoryTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/goplan/app/domain/model/WorkspaceSummary.kt
git add app/src/main/java/com/goplan/app/data/repository/WorkspaceRepository.kt
git add app/src/test/java/com/goplan/app/data/repository/WorkspaceRepositoryTest.kt
git commit -m "feat(workspace): add WorkspaceSummary model and repository calculation methods"
```

---

### Task 2: Estado y Acciones en `WorkspaceViewModel` (TDD)

**Files:**
- Modify: `app/src/main/java/com/goplan/app/ui/workspace/WorkspaceViewModel.kt:70-130,420-470`
- Test: `app/src/test/java/com/goplan/app/ui/workspace/WorkspaceViewModelTest.kt`

**Interfaces:**
- Consumes: `WorkspaceRepository.getAllWorkspaceSummaries(activeWorkspaceId)`
- Produces:
  ```kotlin
  val showWorkspaceSheet: StateFlow<Boolean>
  val workspaceSummaries: StateFlow<List<WorkspaceSummary>>

  fun openWorkspaceSheet()
  fun closeWorkspaceSheet()
  fun loadWorkspaceSummaries()
  fun createAndSwitchWorkspace(name: String): Result<Workspace>
  ```

- [ ] **Step 1: Write failing tests in `WorkspaceViewModelTest.kt`**

```kotlin
@Test
fun testOpenAndCloseWorkspaceSheet() = runTest {
    val viewModel = createViewModel()
    assertFalse(viewModel.showWorkspaceSheet.value)

    viewModel.openWorkspaceSheet()
    assertTrue(viewModel.showWorkspaceSheet.value)

    viewModel.closeWorkspaceSheet()
    assertFalse(viewModel.showWorkspaceSheet.value)
}

@Test
fun testCreateAndSwitchWorkspace_success_switchesAndRefreshes() = runTest {
    val viewModel = createViewModel()
    
    val result = viewModel.createAndSwitchWorkspace("Project Delta")
    
    assertTrue(result.isSuccess)
    val created = result.getOrNull()!!
    assertEquals("Project Delta", created.name)
    assertEquals(created.id, viewModel.uiState.value.activeWorkspace?.id)
    assertFalse(viewModel.showWorkspaceSheet.value)
}

@Test
fun testCreateAndSwitchWorkspace_duplicateName_fails() = runTest {
    val viewModel = createViewModel()
    viewModel.createAndSwitchWorkspace("Existing")
    
    val duplicateResult = viewModel.createAndSwitchWorkspace("existing")
    
    assertTrue(duplicateResult.isFailure)
    assertTrue(duplicateResult.exceptionOrNull()?.message?.contains("Ya existe") == true)
}

@Test
fun testCreateAndSwitchWorkspace_invalidChars_fails() = runTest {
    val viewModel = createViewModel()
    
    val result = viewModel.createAndSwitchWorkspace("bad/name?*")
    
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("inválidos") == true)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.goplan.app.ui.workspace.WorkspaceViewModelTest.testOpenAndCloseWorkspaceSheet"`
Expected: FAIL (unresolved reference `showWorkspaceSheet`).

- [ ] **Step 3: Implement state and functions in `WorkspaceViewModel.kt`**

1. Add `_showWorkspaceSheet = MutableStateFlow(false)` and `showWorkspaceSheet: StateFlow<Boolean>`.
2. Add `_workspaceSummaries = MutableStateFlow<List<WorkspaceSummary>>(emptyList())` and `workspaceSummaries: StateFlow<List<WorkspaceSummary>>`.
3. Implement `openWorkspaceSheet()`, `closeWorkspaceSheet()`, and `loadWorkspaceSummaries()`.
4. Implement `createAndSwitchWorkspace(name: String): Result<Workspace>`:
   - Check blank: `Result.failure(IllegalArgumentException("El nombre no puede estar vacío"))`.
   - Check regex `[\\/:*?"<>|]`: `Result.failure(IllegalArgumentException("El nombre contiene caracteres inválidos"))`.
   - Check duplicate against `repository.getWorkspaces()`: `Result.failure(IllegalArgumentException("Ya existe un espacio con ese nombre"))`.
   - Create workspace, switch to it, refresh summaries, and close sheet.
5. In `init`, trigger `loadWorkspaceSummaries()` whenever workspaces or active workspace change.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.goplan.app.ui.workspace.WorkspaceViewModelTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/goplan/app/ui/workspace/WorkspaceViewModel.kt
git add app/src/test/java/com/goplan/app/ui/workspace/WorkspaceViewModelTest.kt
git commit -m "feat(workspace): add sheet state, summaries flow, and createAndSwitchWorkspace in ViewModel"
```

---

### Task 3: Componentes UI: `WorkspaceChip` y `WorkspaceSelectionBottomSheet` (Compose UI)

**Files:**
- Create: `app/src/main/java/com/goplan/app/ui/workspace/WorkspaceChip.kt`
- Create: `app/src/main/java/com/goplan/app/ui/workspace/WorkspaceSelectionBottomSheet.kt`
- Modify: `app/src/main/java/com/goplan/app/ui/workspace/WorkspaceScreen.kt:240-310,500-530`

**Interfaces:**
- Consumes: `WorkspaceSummary`, `WorkspaceViewModel.workspaceSummaries`, `WorkspaceViewModel.showWorkspaceSheet`
- Produces:
  ```kotlin
  @Composable
  fun WorkspaceChip(
      workspaceName: String,
      onClick: () -> Unit,
      modifier: Modifier = Modifier
  )

  @Composable
  fun WorkspaceSelectionBottomSheet(
      summaries: List<WorkspaceSummary>,
      onSelectWorkspace: (Workspace) -> Unit,
      onCreateWorkspace: (String) -> Result<Workspace>,
      onDismiss: () -> Unit,
      modifier: Modifier = Modifier
  )
  ```

- [ ] **Step 1: Implement `WorkspaceChip.kt`**

Build a modern rounded Pill Surface with folder icon, workspace name (singleLine, ellipsis), and downward chevron icon.

- [ ] **Step 2: Implement `WorkspaceSelectionBottomSheet.kt`**

1. ModalBottomSheet with `rememberModalBottomSheetState(skipPartiallyExpanded = true)` and `WindowInsets.ime`.
2. Header: "Espacios de Trabajo" + badge con número total de espacios.
3. `LazyColumn`:
   - `ActiveWorkspaceCard`: Borde violeta/azul, gradiente sutil, badge "ACTIVO", nombre grande, chips de métricas (`● X Pendientes`, `● Y En progreso`, `● Z Planes`) y checkmark.
   - `InactiveWorkspaceCard`: Tarjeta táctil con avatar de carpeta, nombre, conteo resumido y botón "Abrir" (o tap general) para conmutar.
   - `CreateWorkspaceCard`:
     - Modo reposo: borde discontinuo (*dashed stroke* con `PathEffect.dashPathEffect`), icono `+` y texto *"Crear nuevo espacio de trabajo"*.
     - Modo expandido: `OutlinedTextField` con placeholder, teclado con autofocus, texto de error inline, botón "Cancelar" y botón primario "Crear Espacio".
     - Al crear, llama `onCreateWorkspace(name)`. Si devuelve error, actualiza el mensaje de error inline sin cerrar el sheet.

- [ ] **Step 3: Integrate into `WorkspaceScreen.kt`**

1. Replace the old title `Row(clickable) { Text(...) + DropdownMenu(...) }` with `WorkspaceChip(workspaceName = uiState.activeWorkspace?.name ?: "GoPlan", onClick = { viewModel.openWorkspaceSheet() })`.
2. Remove the old `showCreateWorkspaceDialog` and its `AlertDialog`.
3. In `WorkspaceScreen`, observe `showWorkspaceSheet` and render `WorkspaceSelectionBottomSheet`.

- [ ] **Step 4: Verify Compilation and Assemble Debug APK**

Run: `.\gradlew.bat testDebugUnitTest`
Run: `.\gradlew.bat assembleDebug`
Expected: 100% PASS, BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/goplan/app/ui/workspace/WorkspaceChip.kt
git add app/src/main/java/com/goplan/app/ui/workspace/WorkspaceSelectionBottomSheet.kt
git add app/src/main/java/com/goplan/app/ui/workspace/WorkspaceScreen.kt
git commit -m "feat(ui): add WorkspaceChip and modular WorkspaceSelectionBottomSheet with inline creation"
```

---

### Task 4: Verificación Integral, Despliegue Wi-Fi y Validación en Dispositivo

**Files:**
- Test verification across full test suite.
- Mobile update transmission via CLI.

- [ ] **Step 1: Run complete test suite**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: All 161+ tests pass with zero failures.

- [ ] **Step 2: Build final debug APK**

Run: `.\gradlew.bat assembleDebug`
Expected: `app/build/outputs/apk/debug/app-debug.apk` built cleanly.

- [ ] **Step 3: Push commits to remote branch**

Run: `git push origin angrojo/task-3`

- [ ] **Step 4: Deploy update to phone via Wi-Fi**

Run: `node tools/goplan-orca-plugin/bin/cli.js update`
Verify that the Android package installer opens on the phone.
