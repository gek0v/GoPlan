/**
 * GoPlan Server Connection Diagnostics & Error Reporting
 */

/**
 * Generates actionable diagnostic guidance when GoPlan server cannot be reached.
 * @param {string} url - Target URL attempted
 * @param {Error} error - Network or HTTP error
 * @returns {string} Detailed diagnostic string
 */
export function formatConnectionDiagnostic(url, error) {
  let parsedUrl;
  try {
    parsedUrl = new URL(url);
  } catch {
    parsedUrl = { hostname: 'unknown', port: '8080' };
  }

  const isLocalhost = ['127.0.0.1', 'localhost', '0.0.0.0'].includes(parsedUrl.hostname);
  const errorCode = error?.code || error?.cause?.code || error?.message || 'UNKNOWN_ERROR';

  return [
    `[GoPlan Connection Error] No se pudo conectar al servidor GoPlan en: ${url}`,
    `Causa detectada: ${errorCode}`,
    '',
    'Diagnóstico y Solución:',
    '1. ¿Está el servidor encendido en la app móvil?',
    '   - Abre la app GoPlan en tu teléfono Android.',
    '   - Asegúrate de que el toggle "Servidor Local" esté activado (color verde).',
    '',
    '2. ¿Ha cambiado la dirección IP de tu teléfono?',
    `   - La IP actual configurada es "${parsedUrl.hostname}".`,
    '   - Revisa la IP mostrada en la pantalla del servidor de GoPlan (ej. "http://192.168.1.XX:8080").',
    '   - Actualiza tu configuración mediante una de estas opciones:',
    '     * Archivo local: escribe la URL en `.goplanrc` (ej. `GOPLAN_SERVER_URL=http://192.168.1.XX:8080`)',
    '     * Variable de entorno: `export GOPLAN_SERVER_URL="http://192.168.1.XX:8080"` (Linux/Mac) o `$env:GOPLAN_SERVER_URL="http://192.168.1.XX:8080"` (PowerShell)',
    '     * CLI: `node tools/goplan-orca-plugin/bin/cli.js config http://192.168.1.XX:8080`',
    '',
    isLocalhost
      ? '3. Conexión por cable USB / ADB (si usas localhost / 127.0.0.1):'
      : '3. Conexión Wi-Fi:',
    isLocalhost
      ? '   - Ejecuta: `adb forward tcp:8080 tcp:8080` para redirigir el puerto desde el dispositivo Android.'
      : '   - Asegúrate de que tu ordenador y tu móvil estén conectados a la misma red Wi-Fi y no haya aislamiento de clientes (AP isolation).'
  ].join('\n');
}

/**
 * Diagnostic message when server is reachable but no active workspace is resolved.
 * @param {string} url - Server URL
 * @returns {string}
 */
export function formatNoActiveWorkspaceDiagnostic(url) {
  return [
    `[GoPlan Workspace Warning] Servidor conectado en ${url}, pero no hay un workspace activo seleccionado en la app.`,
    'Acciones posibles:',
    '1. Abre GoPlan en el móvil y selecciona o crea un workspace para activarlo.',
    '2. O especifica el workspaceId manualmente en el comando o llamada a la herramienta MCP (ej. workspaceId: "default").',
    '3. O define GOPLAN_WORKSPACE_ID en tu archivo `.goplanrc` o variable de entorno.'
  ].join('\n');
}
