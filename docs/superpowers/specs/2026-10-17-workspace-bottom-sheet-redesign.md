# Especificación de Diseño: Rediseño de Selección y Creación de Workspaces (Modal Bottom Sheet)

**Fecha:** 17 de octubre de 2026  
**Estado:** Aprobado para Planificación  
**Ruta:** `docs/superpowers/specs/2026-10-17-workspace-bottom-sheet-redesign.md`

---

## 1. Contexto y Objetivos

### 1.1 Problema Actual
En la versión actual de GoPlan (driftfish), el cambio de workspace y la creación de nuevos espacios se realiza a través de un `DropdownMenu` anclado a la barra superior (`TopAppBar`), el cual abre un `AlertDialog` básico con un campo de texto para crear espacios:
- **Ergonomía móvil deficiente:** Los menús desplegables en la cabecera son difíciles de alcanzar con el pulgar en pantallas grandes y no ofrecen affordance táctil claro.
- **Falta de visibilidad y contexto:** No se muestran estadísticas ni métricas del contenido de cada workspace (ideas pendientes, en curso o planes asociados).
- **Creación fragmentada:** La creación requiere múltiples toques (abrir menú -> pulsar crear -> escribir en diálogo emergente modal -> confirmar).

### 1.2 Objetivo
Sustituir el selector y diálogo actual por una experiencia móvil de primer nivel inspirada en herramientas modernas (Linear, Notion, Slack):
1. **Acceso intuitivo:** Un chip interactivo en la barra superior que abre un `ModalBottomSheet` de Material 3 con esquinas redondeadas y pull handle.
2. **Tarjetas modulares de espacios:** Lista desplazable con tarjetas bien diferenciadas para cada workspace, destacando el espacio activo y mostrando métricas en tiempo real (`X pendientes`, `Y en progreso`, `Z planes`).
3. **Creación fluida integrada:** Tarjeta dedicada con borde discontinuo (*dashed border*) que se expande directamente dentro del Bottom Sheet con validación en vivo, sin recurrir a ventanas emergentes intrusivas.
4. **Activación instantánea:** Al seleccionar o crear un workspace, se actualiza el estado activo de inmediato y se cierra el sheet suavemente.

---

## 2. Diseño de Experiencia de Usuario (UX) e Interfaz (UI)

### 2.1 Selector en TopAppBar (`WorkspaceChip`)
- **Ubicación:** Título principal de la `TopAppBar` en `WorkspaceScreen`.
- **Apariencia:**
  - Componente `Surface` redondeado (forma *Pill* / chip) con fondo sutil (`MaterialTheme.colorScheme.surfaceContainerHigh`).
  - Icono de carpeta (`Icons.Default.Folder` o avatar temático) a la izquierda.
  - Nombre del workspace en negrita (`style = MaterialTheme.typography.titleMedium`), con `maxLines = 1` y `TextOverflow.Ellipsis`.
  - Icono indicador chevron hacia abajo (`Icons.Default.KeyboardArrowDown` o `ArrowDropDown`).
- **Comportamiento:** Al pulsarlo, dispara `onOpenWorkspaceSheet()`, abriendo el `WorkspaceSelectionBottomSheet`.

### 2.2 Panel Inferior (`WorkspaceSelectionBottomSheet`)
- **Componente base:** `ModalBottomSheet` de Material 3 con `rememberModalBottomSheetState(skipPartiallyExpanded = true)`.
- **Estructura del contenido:**
  1. **Cabecera:**
     - Título: *"Espacios de Trabajo"*.
     - Contador de espacios totales (ej. *"3 espacios"*).
  2. **Lista de Tarjetas (`LazyColumn`):**
     - **Tarjeta de Espacio Activo:**
       - Borde prominente o gradiente de fondo en color primario/secundario.
       - Badge visible con texto *"ACTIVO"*.
       - Nombre del espacio en tipografía destacada.
       - Desglose de métricas con chips/pills compactos:
         - `● X Pendientes` (color de advertencia/naranja).
         - `● Y En progreso` (color de acento/azul).
         - `● Z Planes` (color terciario o neutral).
       - Icono de checkmark (`Icons.Default.CheckCircle` o `Check`).
     - **Tarjetas de Espacios Inactivos:**
       - Fondo `surfaceContainerLow` con borde sutil.
       - Nombre del espacio y resumen textual de tareas (ej. *"1 tarea pendiente"*).
       - Área táctil completa clickeable (o botón *"Abrir"*). Al pulsarlo:
         - Cambia el workspace activo (`onSwitchWorkspace(ws)`).
         - Cierra el Bottom Sheet.
  3. **Tarjeta de Creación Rápida (`CreateWorkspaceCard`):**
     - **Estado Reposo:**
       - Borde discontinuo (*dashed stroke*) con acento de color.
       - Icono `+` y texto *"Crear nuevo espacio de trabajo"*.
       - Al pulsar, conmuta el estado local `isCreating = true`.
     - **Estado Expandido (Formulario Inline):**
       - Campo de texto `OutlinedTextField` con `singleLine = true`, placeholder *"Nombre del nuevo espacio"*, autofocus en teclado y botón de limpiar.
       - Texto de ayuda / error inline bajo el campo.
       - Fila de acciones:
         - Botón de texto *"Cancelar"* (vuelve al estado reposo y limpia el campo).
         - Botón primario *"Crear Espacio"* (habilitado solo cuando el nombre es válido).
       - Al pulsar *"Crear"*:
         - Invoca `onCreateWorkspace(name)`.
         - Selecciona automáticamente el nuevo workspace.
         - Cierra el Bottom Sheet.

---

## 3. Arquitectura y Modelo de Datos

### 3.1 Modelo de Resumen (`WorkspaceSummary`)
Ubicación: `app/src/main/java/com/goplan/app/domain/model/WorkspaceSummary.kt`
```kotlin
package com.goplan.app.domain.model

data class WorkspaceSummary(
    val workspace: Workspace,
    val isActive: Boolean,
    val pendingIdeasCount: Int,
    val inProgressIdeasCount: Int,
    val plansCount: Int
)
```

### 3.2 Repositorio (`WorkspaceRepository`)
Se añade una función suspendida o de consulta eficiente para calcular el resumen de un workspace:
```kotlin
fun getWorkspaceSummary(workspace: Workspace, isActive: Boolean): WorkspaceSummary {
    val doc = getTodoDocument(workspace)
    val pending = doc.ideas.count { it.status == IdeaStatus.PENDING }
    val inProgress = doc.ideas.count { it.status == IdeaStatus.IN_PROGRESS }
    val planesDir = File(workspace.rootDir, "planes")
    val plans = planesDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.size ?: 0
    return WorkspaceSummary(
        workspace = workspace,
        isActive = isActive,
        pendingIdeasCount = pending,
        inProgressIdeasCount = inProgress,
        plansCount = plans
    )
}
```

### 3.3 Estado y Reactividad en `WorkspaceViewModel`
- **Nuevo estado expuesto:**
  ```kotlin
  private val _workspaceSummaries = MutableStateFlow<List<WorkspaceSummary>>(emptyList())
  val workspaceSummaries: StateFlow<List<WorkspaceSummary>> = _workspaceSummaries.asStateFlow()

  private val _showWorkspaceSheet = MutableStateFlow(false)
  val showWorkspaceSheet: StateFlow<Boolean> = _showWorkspaceSheet.asStateFlow()
  ```
- **Recálculo asíncrono:**
  - Al abrir el Bottom Sheet o al recibir eventos en `workspaceChangesFlow`, se ejecuta un cálculo en `Dispatchers.IO` para actualizar `_workspaceSummaries`.
- **Creación y conmutación:**
  ```kotlin
  fun createAndSwitchWorkspace(name: String) {
      val trimmed = name.trim()
      if (trimmed.isBlank()) return
      val created = workspaceRepository.createWorkspace(trimmed)
      switchWorkspace(created)
      closeWorkspaceSheet()
  }
  ```

---

## 4. Validación y Casos Borde

1. **Validación de Nombres:**
   - No puede estar vacío ni contener solo espacios en blanco.
   - Longitud máxima recomendada: 50 caracteres.
   - No puede contener caracteres prohibidos por el sistema de archivos: `\ / : * ? " < > |`.
   - Comparación insensible a mayúsculas/minúsculas contra la lista actual de workspaces (`workspaces.any { it.name.equals(name, ignoreCase = true) }`). Si ya existe, muestra *"Ya existe un espacio con ese nombre"*.
2. **Persistencia y Reactividad:**
   - La creación actualiza inmediatamente la lista de workspaces locales y emite a través de `workspaceChangesFlow`.
   - La sincronización con Google Drive (si está habilitada) sincronizará el nuevo workspace en la siguiente sincronización global sin intervención adicional.
3. **Manejo de Teclado (IME):**
   - El `ModalBottomSheet` gestiona `imePadding()` para que el teclado virtual no tape el campo de creación ni los botones de acción.

---

## 5. Estrategia de Pruebas y Verificación

1. **Pruebas Unitarias en `WorkspaceViewModelTest`:**
   - `testLoadWorkspaceSummaries_calculatesCorrectCountsForPendingInProgressAndPlans`: Valida que los conteos de ideas y planes coincidan exactamente con los archivos del workspace.
   - `testCreateAndSwitchWorkspace_createsWorkspaceAndSwitchesActive`: Valida que la creación asigna el nuevo workspace como activo y actualiza los resúmenes.
   - `testWorkspaceNameValidation_rejectsDuplicatesAndInvalidNames`: Valida la detección de duplicados y nombres vacíos.
2. **Pruebas de Componentes Compose:**
   - Comprobación de renderizado de `WorkspaceChip`, tarjetas de `WorkspaceSelectionBottomSheet` y estado expandido de creación.
3. **Verificación de Build y Regresión:**
   - Ejecución de `.\gradlew.bat testDebugUnitTest` garantizando 100% de éxito en todos los tests.
   - Compilación exitosa de APK con `.\gradlew.bat assembleDebug`.
