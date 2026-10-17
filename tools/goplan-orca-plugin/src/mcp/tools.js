import fs from 'node:fs';
import path from 'node:path';
import { formatStatus, formatTodo, formatIdea, formatPlanResult } from '../utils/formatter.js';

/**
 * Definition of the 8 MCP tools for GoPlan
 */
export const TOOL_DEFINITIONS = [
  {
    name: 'goplan_status',
    description: 'Comprueba el estado de conexión del servidor móvil GoPlan, IP, puerto y workspace activo.',
    inputSchema: {
      type: 'object',
      properties: {
        serverUrl: {
          type: 'string',
          description: 'URL opcional del servidor GoPlan (por defecto se resuelve desde .goplanrc o GOPLAN_SERVER_URL)'
        }
      }
    }
  },
  {
    name: 'goplan_get_context',
    description: 'Obtiene el contenido de CONTEXT.md del workspace activo o de un workspace específico en GoPlan.',
    inputSchema: {
      type: 'object',
      properties: {
        workspaceId: {
          type: 'string',
          description: 'ID del workspace (opcional; si se omite, se usa el workspace activo en la app)'
        },
        raw: {
          type: 'boolean',
          description: 'Si es true, devuelve el markdown directamente sin estructura JSON'
        }
      }
    }
  },
  {
    name: 'goplan_save_context',
    description: 'Actualiza el archivo CONTEXT.md en el workspace de la app móvil GoPlan.',
    inputSchema: {
      type: 'object',
      properties: {
        content: {
          type: 'string',
          description: 'Nuevo contenido en Markdown para CONTEXT.md'
        },
        workspaceId: {
          type: 'string',
          description: 'ID del workspace (opcional; si se omite, se usa el workspace activo)'
        }
      },
      required: ['content']
    }
  },
  {
    name: 'goplan_get_todo',
    description: 'Lista las ideas y tareas activas en TODO.md de GoPlan, agrupadas por estado o etiquetas.',
    inputSchema: {
      type: 'object',
      properties: {
        workspaceId: {
          type: 'string',
          description: 'ID del workspace (opcional)'
        },
        status: {
          type: 'string',
          description: 'Filtrar por estado: PENDING, IN_PROGRESS, COMPLETED, DISCARDED'
        },
        tag: {
          type: 'string',
          description: 'Filtrar por etiqueta (ej. "frontend", "auth")'
        },
        groupBy: {
          type: 'string',
          enum: ['status', 'tag', 'none'],
          description: 'Modo de agrupación del resultado: status (por defecto), tag o none'
        }
      }
    }
  },
  {
    name: 'goplan_send_idea',
    description: 'Envía una nueva idea o tarea a la app móvil GoPlan con soporte para etiquetas #tag.',
    inputSchema: {
      type: 'object',
      properties: {
        text: {
          type: 'string',
          description: 'Texto de la idea o tarea (puede incluir hashtags como #urgente #api)'
        },
        workspaceId: {
          type: 'string',
          description: 'ID del workspace (opcional)'
        }
      },
      required: ['text']
    }
  },
  {
    name: 'goplan_get_plan',
    description: 'Lee el contenido de un plan específico guardado en planes/<nombre>.md en GoPlan.',
    inputSchema: {
      type: 'object',
      properties: {
        fileName: {
          type: 'string',
          description: 'Nombre del archivo del plan (ej. "autenticacion.md" o "planes/autenticacion.md")'
        },
        workspaceId: {
          type: 'string',
          description: 'ID del workspace (opcional)'
        }
      },
      required: ['fileName']
    }
  },
  {
    name: 'goplan_save_plan',
    description: 'Ingesta un nuevo plan o actualización en GoPlan (POST /api/workspaces/{id}/plans), vinculándolo a su idea y pasando su estado a IN_PROGRESS.',
    inputSchema: {
      type: 'object',
      properties: {
        ideaId: {
          type: 'string',
          description: 'ID de la idea en TODO.md a la que se vinculará el plan'
        },
        fileName: {
          type: 'string',
          description: 'Nombre del archivo del plan (ej. "plan_arquitectura.md")'
        },
        content: {
          type: 'string',
          description: 'Contenido Markdown del plan (si se especifica fileName)'
        },
        files: {
          type: 'array',
          description: 'Lista de archivos si se desea ingestar múltiples ficheros al mismo tiempo',
          items: {
            type: 'object',
            properties: {
              fileName: { type: 'string' },
              content: { type: 'string' }
            },
            required: ['fileName', 'content']
          }
        },
        rawText: {
          type: 'string',
          description: 'Texto sin procesar con planes delimitados en formato de plan de agente'
        },
        workspaceId: {
          type: 'string',
          description: 'ID del workspace (opcional)'
        },
        updateStatusToInProgress: {
          type: 'boolean',
          description: 'Cambiar automáticamente el estado de la idea a IN_PROGRESS (por defecto true)'
        }
      },
      required: ['ideaId']
    }
  },
  {
    name: 'goplan_export',
    description: 'Descarga el bundle Markdown o ZIP consolidado del workspace para el agente.',
    inputSchema: {
      type: 'object',
      properties: {
        workspaceId: {
          type: 'string',
          description: 'ID del workspace (opcional)'
        },
        type: {
          type: 'string',
          enum: ['md', 'zip'],
          description: 'Tipo de exportación: "md" para markdown unificado o "zip" para archivo comprimido (por defecto "md")'
        },
        outputPath: {
          type: 'string',
          description: 'Ruta local opcional en disco donde guardar el archivo descargado'
        }
      }
    }
  }
];

/**
 * Creates dispatch map for MCP tools bound to a GoPlanClient instance.
 * @param {import('../client.js').GoPlanClient} client
 * @returns {Record<string, (args: any) => Promise<{ content: Array<{ type: string, text: string }>, isError?: boolean }>>}
 */
export function createToolHandlers(client) {
  return {
    async goplan_status(args = {}) {
      const safeArgs = (args && typeof args === 'object' && !Array.isArray(args)) ? args : {};
      try {
        let activeClient = client;
        if (safeArgs.serverUrl && safeArgs.serverUrl !== client.serverUrl) {
          const { GoPlanClient } = await import('../client.js');
          activeClient = new GoPlanClient({
            ...(client.options || {}),
            serverUrl: safeArgs.serverUrl,
            fetchFn: client.fetchFn,
            timeoutMs: client.timeoutMs
          });
        }
        const status = await activeClient.getStatus();
        const text = formatStatus(status);
        return {
          content: [
            { type: 'text', text },
            { type: 'text', text: JSON.stringify(status, null, 2) }
          ]
        };
      } catch (err) {
        return {
          isError: true,
          content: [{ type: 'text', text: err.diagnostic || `Error comprobando estado: ${err.message}` }]
        };
      }
    },

    async goplan_get_context(args = {}) {
      const safeArgs = (args && typeof args === 'object' && !Array.isArray(args)) ? args : {};
      try {
        const result = await client.getContext(safeArgs.workspaceId, { raw: safeArgs.raw });
        const rawText = typeof result === 'string' ? result : (result?.content ?? '');
        const meta = (typeof result === 'object' && result?.workspaceId) ? `\n\n*(Workspace: \`${result.workspaceId}\`)*` : '';
        const text = rawText.trim() ? `${rawText}${meta}` : `*(El archivo CONTEXT.md está vacío)*${meta}`;
        return {
          content: [{ type: 'text', text }]
        };
      } catch (err) {
        return {
          isError: true,
          content: [{ type: 'text', text: err.diagnostic || `Error obteniendo CONTEXT.md: ${err.message}` }]
        };
      }
    },

    async goplan_save_context(args = {}) {
      const safeArgs = (args && typeof args === 'object' && !Array.isArray(args)) ? args : {};
      try {
        if (typeof safeArgs.content !== 'string') {
          throw new Error('El parámetro "content" es obligatorio');
        }
        const wsId = await client.resolveActiveWorkspace(safeArgs.workspaceId);
        const res = await client.saveContext(wsId, safeArgs.content);
        if (res && res.success === false) {
          return {
            isError: true,
            content: [{ type: 'text', text: `Error guardando CONTEXT.md: ${res.message || 'La app GoPlan rechazó la actualización.'}` }]
          };
        }
        return {
          content: [{
            type: 'text',
            text: `✅ CONTEXT.md actualizado correctamente en el workspace \`${wsId}\` (${safeArgs.content.length} caracteres guardados).`
          }]
        };
      } catch (err) {
        return {
          isError: true,
          content: [{ type: 'text', text: err.diagnostic || `Error guardando CONTEXT.md: ${err.message}` }]
        };
      }
    },

    async goplan_get_todo(args = {}) {
      const safeArgs = (args && typeof args === 'object' && !Array.isArray(args)) ? args : {};
      try {
        const wsId = await client.resolveActiveWorkspace(safeArgs.workspaceId);
        const todo = await client.getTodo(wsId);
        const formatted = formatTodo(todo, {
          groupBy: safeArgs.groupBy || 'status',
          statusFilter: safeArgs.status,
          tagFilter: safeArgs.tag
        });
        const header = `### 📋 Tareas e Ideas de GoPlan (Workspace: \`${wsId}\`)\n\n`;
        return {
          content: [
            { type: 'text', text: `${header}${formatted}` }
          ]
        };
      } catch (err) {
        return {
          isError: true,
          content: [{ type: 'text', text: err.diagnostic || `Error leyendo TODO.md: ${err.message}` }]
        };
      }
    },

    async goplan_send_idea(args = {}) {
      const safeArgs = (args && typeof args === 'object' && !Array.isArray(args)) ? args : {};
      try {
        if (typeof safeArgs.text !== 'string' || !safeArgs.text.trim()) {
          throw new Error('El parámetro "text" es obligatorio');
        }
        const wsId = await client.resolveActiveWorkspace(safeArgs.workspaceId);
        const idea = await client.createIdea(wsId, safeArgs.text);
        const formatted = formatIdea(idea);
        return {
          content: [
            { type: 'text', text: `${formatted}\n*(Workspace: \`${wsId}\`)*` }
          ]
        };
      } catch (err) {
        return {
          isError: true,
          content: [{ type: 'text', text: err.diagnostic || `Error enviando idea a GoPlan: ${err.message}` }]
        };
      }
    },

    async goplan_get_plan(args = {}) {
      const safeArgs = (args && typeof args === 'object' && !Array.isArray(args)) ? args : {};
      try {
        if (typeof safeArgs.fileName !== 'string' || !safeArgs.fileName.trim()) {
          throw new Error('El parámetro "fileName" es obligatorio');
        }
        const wsId = await client.resolveActiveWorkspace(safeArgs.workspaceId);
        const content = await client.getPlan(wsId, safeArgs.fileName);
        return {
          content: [
            { type: 'text', text: `### 📄 Plan: \`${safeArgs.fileName}\` (Workspace: \`${wsId}\`)\n\n${content}` }
          ]
        };
      } catch (err) {
        const fileLabel = safeArgs.fileName ? ` "${safeArgs.fileName}"` : '';
        return {
          isError: true,
          content: [{ type: 'text', text: err.diagnostic || `Error obteniendo plan${fileLabel}: ${err.message}` }]
        };
      }
    },

    async goplan_save_plan(args = {}) {
      const safeArgs = (args && typeof args === 'object' && !Array.isArray(args)) ? args : {};
      try {
        if (typeof safeArgs.ideaId !== 'string' || !safeArgs.ideaId.trim()) {
          throw new Error('El parámetro "ideaId" es obligatorio');
        }
        const wsId = await client.resolveActiveWorkspace(safeArgs.workspaceId);
        const result = await client.savePlan(wsId, {
          ideaId: safeArgs.ideaId,
          fileName: safeArgs.fileName,
          content: safeArgs.content,
          files: safeArgs.files,
          rawText: safeArgs.rawText,
          updateStatusToInProgress: safeArgs.updateStatusToInProgress !== false
        });
        const text = formatPlanResult(result, safeArgs.ideaId, wsId);
        if (result && result.success === false) {
          return {
            isError: true,
            content: [{ type: 'text', text }]
          };
        }
        return {
          content: [{ type: 'text', text }]
        };
      } catch (err) {
        return {
          isError: true,
          content: [{ type: 'text', text: err.diagnostic || `Error guardando plan en GoPlan: ${err.message}` }]
        };
      }
    },

    async goplan_export(args = {}) {
      const safeArgs = (args && typeof args === 'object' && !Array.isArray(args)) ? args : {};
      try {
        const wsId = await client.resolveActiveWorkspace(safeArgs.workspaceId);
        const exportType = (safeArgs.type || 'md').toLowerCase();
        const bundle = await client.exportWorkspace(wsId, { type: exportType, timeoutMs: safeArgs.timeoutMs });

        if (safeArgs.outputPath) {
          let resolvedPath = path.resolve(safeArgs.outputPath);
          const isDir = (fs.existsSync(resolvedPath) && fs.statSync(resolvedPath).isDirectory()) ||
                        safeArgs.outputPath.endsWith('/') || safeArgs.outputPath.endsWith('\\');
          if (isDir) {
            resolvedPath = path.join(resolvedPath, `${wsId}-export.${exportType}`);
          }
          fs.mkdirSync(path.dirname(resolvedPath), { recursive: true });
          if (bundle.type === 'zip') {
            fs.writeFileSync(resolvedPath, bundle.content);
          } else {
            fs.writeFileSync(resolvedPath, bundle.content, 'utf8');
          }
          return {
            content: [{
              type: 'text',
              text: `📦 Exportación (${exportType.toUpperCase()}) de workspace \`${wsId}\` guardada exitosamente en:\n\`${resolvedPath}\``
            }]
          };
        }

        if (bundle.type === 'md') {
          return {
            content: [{
              type: 'text',
              text: `### 📦 Workspace Export: \`${wsId}\` (Markdown Consolidado)\n\n${bundle.content}`
            }]
          };
        } else {
          return {
            content: [{
              type: 'text',
              text: `📦 Archivo ZIP generado para workspace \`${wsId}\` (${bundle.content.length} bytes). Para guardarlo en disco, especifica el parámetro "outputPath".`
            }]
          };
        }
      } catch (err) {
        return {
          isError: true,
          content: [{ type: 'text', text: err.diagnostic || `Error exportando workspace: ${err.message}` }]
        };
      }
    }
  };
}
