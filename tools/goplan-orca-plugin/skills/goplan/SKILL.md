---
name: goplan
description: >
  Use when interacting with the GoPlan mobile app: checking connectivity (`goplan_status`),
  fetching or updating project context (`goplan_get_context`, `goplan_save_context`),
  listing or filtering tasks and ideas (`goplan_get_todo`), sending new ideas with hashtags (`goplan_send_idea`),
  reading plans (`goplan_get_plan`), syncing and ingesting implementation plans into GoPlan (`goplan_save_plan`),
  or exporting workspace bundles (`goplan_export`). Use whenever the user mentions GoPlan, mobile sync,
  or sending tasks/plans to the phone.
license: MIT
---

# GoPlan Mobile Integration Skill

Esta skill proporciona herramientas para interactuar con la app móvil **GoPlan** a través de su servidor HTTP local y el protocolo MCP (Model Context Protocol).

## Cuándo Usar Esta Skill

Utiliza esta skill y sus herramientas nativas cuando:
1. El usuario pida comprobar la conexión con la app móvil GoPlan o consultar qué workspace está activo.
2. El usuario solicite traer o inspeccionar el `CONTEXT.md` definido en la app para guiar el desarrollo.
3. El usuario quiera actualizar el `CONTEXT.md` en el móvil con decisiones técnicas tomadas por el agente.
4. El usuario pida revisar las ideas o tareas pendientes (`TODO.md`) almacenadas en GoPlan.
5. El usuario quiera capturar o enviar una nueva idea, bug o requerimiento a la app móvil con etiquetas `#tag`.
6. El usuario o el agente hayan elaborado un plan de implementación y deseen sincronizarlo con GoPlan (`goplan_save_plan`) para que quede enlazado a la tarea y pase a `IN_PROGRESS`.
7. El usuario pida exportar o descargar el workspace consolidado en ZIP o Markdown.

---

## Herramientas Disponibles

### 1. `goplan_status`
Comprueba si el servidor móvil está activo, la versión de la app, su IP y el workspace activo.
- **Parámetros**:
  - `serverUrl` *(opcional)*: URL del servidor (ej. `http://192.168.1.50:8080`).

### 2. `goplan_get_context`
Obtiene el contenido de `CONTEXT.md` del workspace activo o especificado.
- **Parámetros**:
  - `workspaceId` *(opcional)*: ID del workspace. Si se omite, se resuelve automáticamente el activo.
  - `raw` *(opcional boolean)*: Devolver texto plano si es `true`.

### 3. `goplan_save_context`
Actualiza `CONTEXT.md` en el móvil.
- **Parámetros**:
  - `content` *(requerido)*: Contenido completo en Markdown a guardar.
  - `workspaceId` *(opcional)*: ID del workspace.

### 4. `goplan_get_todo`
Lista ideas y tareas de `TODO.md` estructuradas y formateadas.
- **Parámetros**:
  - `workspaceId` *(opcional)*
  - `status` *(opcional)*: `PENDING`, `IN_PROGRESS`, `COMPLETED`, `DISCARDED`.
  - `tag` *(opcional)*: Filtrar por etiqueta (ej. `#auth` o `auth`).
  - `groupBy` *(opcional)*: `status` (por defecto), `tag`, o `none`.

### 5. `goplan_send_idea`
Envía una nueva idea o tarea a la app móvil. Extrae automáticamente las etiquetas `#tag`.
- **Parámetros**:
  - `text` *(requerido)*: Texto de la idea, ej: `"Agregar soporte biométrico #auth #seguridad"`.
  - `workspaceId` *(opcional)*

### 6. `goplan_get_plan`
Lee el archivo Markdown de un plan específico en `planes/<nombre>.md`.
- **Parámetros**:
  - `fileName` *(requerido)*: Nombre del archivo (ej. `auth_plan.md` o `planes/auth_plan.md`).
  - `workspaceId` *(opcional)*

### 7. `goplan_save_plan`
Ingesta un plan en GoPlan (`POST /api/workspaces/{id}/plans`), lo vincula a la idea correspondiente y pasa su estado a `IN_PROGRESS`.
- **Parámetros**:
  - `ideaId` *(requerido)*: ID de la idea en TODO.md.
  - `fileName` *(opcional)*: Nombre del archivo a crear (ej. `plan_login.md`).
  - `content` *(opcional)*: Contenido del plan en Markdown.
  - `files` *(opcional array)*: Lista de `{ fileName, content }` para múltiples archivos.
  - `rawText` *(opcional)*: Texto sin procesar con planes delimitados.
  - `workspaceId` *(opcional)*
  - `updateStatusToInProgress` *(opcional, default true)*: Pasa el estado a `IN_PROGRESS`.

### 8. `goplan_export`
Descarga el bundle consolidado del workspace.
- **Parámetros**:
  - `workspaceId` *(opcional)*
  - `type` *(opcional)*: `'md'` o `'zip'` (por defecto `'md'`).
  - `outputPath` *(opcional)*: Ruta en disco donde guardar el archivo resultante.

---

## Flujos de Trabajo Típicos

### Flujo 1: Inicio de sesión de desarrollo
1. Ejecutar `goplan_status` para verificar que la app móvil está conectada.
2. Ejecutar `goplan_get_context` para cargar la arquitectura, reglas y directrices del proyecto en la memoria del agente.
3. Ejecutar `goplan_get_todo` para listar las tareas en progreso y pendientes.

### Flujo 2: Planificación y Ejecución de Tarea
1. El usuario o agente elige una idea pendiente de `goplan_get_todo` (ej. ID `IDEA-102`).
2. El agente genera el plan de acción detallado en Markdown.
3. El agente invoca `goplan_save_plan` pasando `ideaId: "IDEA-102"`, `fileName: "plan_implementacion_auth.md"` y `content: <markdown>`.
4. La app móvil almacena el plan en `planes/plan_implementacion_auth.md`, vincula el enlace en `TODO.md` y cambia el estado de `IDEA-102` a `IN_PROGRESS`.

### Flujo 3: Captura Rápida de Ideas
1. Durante una conversación el usuario menciona: *"Recuerda que luego debemos agregar exportación a CSV"*.
2. El agente llama a `goplan_send_idea` con `text: "Agregar exportación a formato CSV en reportes #export #datos"`.
3. La app móvil crea la tarea con las etiquetas adecuadas y la confirma inmediatamente.

---

## Solución de Problemas de Conexión

Si cualquier herramienta devuelve un error de conexión:
1. Verifica que la app GoPlan esté abierta y el switch **Servidor Local** esté activo.
2. Si la IP del teléfono cambió (por ejemplo por DHCP), actualiza la URL en el archivo `.goplanrc`:
   ```bash
   GOPLAN_SERVER_URL=http://192.168.1.XX:8080
   ```
3. Si estás conectado por cable USB o en emulador, redirige el puerto con ADB:
   ```bash
   adb forward tcp:8080 tcp:8080
   ```
