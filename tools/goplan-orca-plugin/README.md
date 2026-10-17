# 🐬 GoPlan Orca/Antigravity Plugin

Plugin oficial de sincronización bidireccional entre **Orca / Antigravity** y la aplicación móvil **GoPlan**. Permite a los agentes autónomos de IA y al usuario inspeccionar contextos, capturar ideas con etiquetas, leer tareas pendientes, e ingestar y sincronizar planes de acción directamente con el servidor HTTP local de la app móvil.

---

## ⚡ Instalación y Registro en 1 Paso

El plugin está diseñado con **cero dependencias externas requeridas** para su ejecución básica (utiliza las APIs nativas de Node.js 18+).

### Opción A. Registro Automático en Orca / Antigravity Plugins

1. Añade la configuración del servidor MCP en el archivo de configuración de tu agente o entorno (por ejemplo en `~/.gemini/antigravity-cli/mcp_config.json` o en la sección `mcpServers` de Orca):

```json
{
  "mcpServers": {
    "goplan": {
      "command": "node",
      "args": ["C:/Users/Geko/orca/workspaces/G0Plan/driftfish/tools/goplan-orca-plugin/bin/mcp-server.js"]
    }
  }
}
```

¡Listo! Todos los agentes y el chat tendrán acceso inmediato a las 8 herramientas MCP de GoPlan.

---

## ⚙️ Conexión y Configuración del Servidor Móvil

El plugin resuelve la URL del servidor y el workspace activo según el siguiente orden de precedencia:

1. **Parámetro explícito** en la llamada a la herramienta MCP o comando CLI.
2. **Variable de entorno**:
   ```bash
   export GOPLAN_SERVER_URL="http://192.168.1.50:8080"
   # En Windows PowerShell:
   $env:GOPLAN_SERVER_URL = "http://192.168.1.50:8080"
   ```
3. **Archivo de configuración `.goplanrc`** en el workspace actual o en el directorio home (`~/.goplanrc`):
   ```ini
   # .goplanrc
   GOPLAN_SERVER_URL=http://192.168.1.50:8080
   # Opcional: fija el workspace si no deseas detección automática
   # GOPLAN_WORKSPACE_ID=default
   ```
4. **Valor por defecto**: `http://127.0.0.1:8080`.

### 💡 Tip para conexión USB / Emulador (ADB)
Si tu teléfono está conectado por cable USB o estás usando un emulador Android, puedes redirigir el puerto ejecutando:
```bash
adb forward tcp:8080 tcp:8080
```
Y el plugin se comunicará directamente con `http://127.0.0.1:8080`.

---

## 🛠️ Herramientas Nativas MCP para Agentes

El plugin expone un servidor estándar MCP (Model Context Protocol, especificación `2024-11-05`) sobre stdio con las siguientes herramientas:

| Herramienta MCP | Descripción | Parámetros Principales |
|---|---|---|
| `goplan_status` | Comprueba estado del servidor, IP, puerto y workspace activo. | `serverUrl` (opcional) |
| `goplan_get_context` | Obtiene el contenido de `CONTEXT.md` del workspace activo. | `workspaceId` (opcional), `raw` (opcional boolean) |
| `goplan_save_context` | Actualiza `CONTEXT.md` en la app móvil. | `content` (requerido), `workspaceId` (opcional) |
| `goplan_get_todo` | Lista ideas y tareas activas agrupadas por estado o etiqueta. | `workspaceId`, `status`, `tag`, `groupBy` (`status`\|`tag`\|`none`) |
| `goplan_send_idea` | Envía una nueva idea o tarea a la app con soporte para `#tag`. | `text` (requerido), `workspaceId` (opcional) |
| `goplan_get_plan` | Lee el contenido de un plan específico en `planes/<nombre>.md`. | `fileName` (requerido), `workspaceId` (opcional) |
| `goplan_save_plan` | Ingesta un nuevo plan (`POST /api/workspaces/{id}/plans`), vinculándolo a su idea y pasando su estado a `IN_PROGRESS`. | `ideaId` (requerido), `fileName`, `content`, `files`, `rawText`, `workspaceId` |
| `goplan_export` | Descarga bundle Markdown o ZIP consolidado del workspace. | `workspaceId`, `type` (`md`\|`zip`), `outputPath` |

---

## 💬 Comandos Rápidos en Chat (Slash Commands) y Skill

El plugin define una skill (`skills/goplan/SKILL.md`) y comandos rápidos de barra diagonal (`commands/*.toml`):

- **`/goplan`**: Comando multipropósito para interactuar con la app móvil.
- **`/goplan-idea <texto #etiquetas>`**: Captura y envía inmediatamente una idea a GoPlan sin salir del chat.
  - *Ejemplo*: `/goplan-idea Añadir inicio de sesión con huella dactilar #auth #seguridad`
- **`/goplan-status`**: Muestra una tarjeta visual con el estado de conectividad, IP, workspace activo y conteo de tareas pendientes.
- **`/goplan-context`**: Descarga e inyecta el `CONTEXT.md` del móvil directamente en la ventana de contexto del chat para que el asistente conozca las reglas del proyecto.
- **`/goplan-sync`**: Sincroniza el plan generado por el agente con la app móvil y avanza la idea a `IN_PROGRESS`.

---

## 💻 Uso por Línea de Comandos (CLI)

El plugin incluye un ejecutable CLI directo en `bin/cli.js`:

```bash
# Comprobar estado de conexión
node tools/goplan-orca-plugin/bin/cli.js status

# Ver tareas agrupadas por estado
node tools/goplan-orca-plugin/bin/cli.js todo

# Filtrar tareas por etiqueta
node tools/goplan-orca-plugin/bin/cli.js todo --tag=frontend

# Enviar una idea rápida
node tools/goplan-orca-plugin/bin/cli.js idea "Integrar pagos con Stripe #pagos #mvp"

# Obtener CONTEXT.md
node tools/goplan-orca-plugin/bin/cli.js context

# Actualizar CONTEXT.md desde un fichero local
node tools/goplan-orca-plugin/bin/cli.js save-context ./NUEVO_CONTEXTO.md

# Ingestar y vincular un plan a una idea
node tools/goplan-orca-plugin/bin/cli.js save-plan --idea=IDEA_123 --file=plan_auth.md --path=./mi_plan.md

# Exportar bundle Markdown o ZIP
node tools/goplan-orca-plugin/bin/cli.js export --md
node tools/goplan-orca-plugin/bin/cli.js export --zip --out=./backup.zip

# Ver o guardar configuración en .goplanrc
node tools/goplan-orca-plugin/bin/cli.js config http://192.168.1.120:8080
```

---

## 🔍 Diagnóstico Automático de Errores

Si el servidor GoPlan no es alcanzable o la IP de tu teléfono ha cambiado por DHCP:
- El plugin detecta el fallo de red (`ECONNREFUSED`, `ETIMEDOUT`, `ENOTFOUND`) y genera un mensaje con pasos claros de solución.
- Verifica si el toggle del servidor en la app móvil está en verde.
- Muestra la IP actual configurada y cómo cambiarla en `.goplanrc` o variables de entorno.
- Sugiere comandos ADB si se está utilizando conexión por cable USB.

---

## 🧪 Pruebas Automatizadas

El plugin incluye una suite de pruebas automatizadas independiente con **100% de éxito**:

```bash
cd tools/goplan-orca-plugin
npm test
# O directamente:
node --test test/*.test.js
```

### Cobertura de la Suite de Pruebas:
- **`test/client.test.js`**: Cliente REST completo (`GET /api/status`, `GET/POST /api/workspaces`, `GET/PUT /context`, `GET/POST /ideas`, `GET/POST /plans`, `GET /export`, protección contra directory traversal, timeouts y errores diagnósticos).
- **`test/tools.test.js`**: Validación de las 8 herramientas MCP, esquemas de entrada, validación de obligatoriedad, y formateo de respuestas.
- **`test/mcp-server.test.js`**: Handshake MCP estándar (`initialize`, `notifications/initialized`, `ping`, `tools/list`, `tools/call`, mensajes de error y parsing).
- **`test/config.test.js`**: Precedencia de variables de entorno, lectura de archivos `.goplanrc` (JSON e INI), y persistencia.
- **`test/cli.test.js`**: Ejecución de comandos por consola, argumentos y validaciones.
- **`test/formatter.test.js`**: Renderizado Markdown para interfaces de chat y LLMs.
- **`test/protocol.test.js`**: Codificación, decodificación y framing de tramas JSON-RPC 2.0.

---

## 📄 Licencia

MIT © GoPlan Team
