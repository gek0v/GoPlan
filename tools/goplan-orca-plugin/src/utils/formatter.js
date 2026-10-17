/**
 * Formatting utilities for GoPlan MCP tools, CLI, and Slash Commands
 */

/**
 * Normalizes a list of tags:
 * Splits adjoining tags (e.g. 'tag1#tag2' -> ['tag1', 'tag2']),
 * strips leading '#', and removes empty or null entries.
 * @param {Array<string>} tags
 * @returns {string[]}
 */
export function normalizeTags(tags) {
  if (!Array.isArray(tags)) return [];
  const result = [];
  for (const t of tags) {
    if (t === null || t === undefined) continue;
    const str = String(t).trim();
    if (!str) continue;
    const parts = str.split('#').map(p => p.trim()).filter(Boolean);
    result.push(...parts);
  }
  return result;
}

/**
 * Format server status into a clean summary table/card.
 * @param {object} [status] - Response from GET /api/status
 * @returns {string}
 */
export function formatStatus(status) {
  const safe = (status && typeof status === 'object') ? status : {};
  const isOnline = safe.status === 'online';
  const icon = isOnline ? '🟢' : '🔴';
  const activeWs = safe.activeWorkspace ? `\`${safe.activeWorkspace}\`` : '*(Ninguno seleccionado)*';

  return [
    `### ${icon} GoPlan Server Status`,
    `- **Estado**: ${safe.status ? String(safe.status).toUpperCase() : 'OFFLINE'}`,
    `- **App**: ${safe.app || 'GoPlan'} (v${safe.version || '1.0.0'})`,
    `- **IP Servidor**: \`${safe.ip || 'desconocida'}\``,
    `- **Puerto**: \`${safe.port || 8080}\``,
    `- **Workspace Activo**: ${activeWs}`
  ].join('\n');
}

/**
 * Format todo/ideas list into categorized markdown.
 * @param {object} [todoData] - Response from GET /api/workspaces/{id}/todo
 * @param {object} [options]
 * @param {string} [options.groupBy='status'] - 'status' | 'tag' | 'none'
 * @param {string} [options.statusFilter] - e.g. 'PENDING'
 * @param {string} [options.tagFilter] - e.g. 'frontend'
 * @returns {string}
 */
export function formatTodo(todoData, options = {}) {
  const safeData = (todoData && typeof todoData === 'object') ? todoData : {};
  let ideas = Array.isArray(safeData.ideas)
    ? safeData.ideas
    : (Array.isArray(safeData) ? safeData : []);

  if (options.statusFilter) {
    const f = String(options.statusFilter).trim().toUpperCase();
    ideas = ideas.filter(i => (i?.status || 'PENDING').toString().toUpperCase() === f);
  }

  if (options.tagFilter) {
    const tf = String(options.tagFilter).trim().toLowerCase().replace(/^#/, '');
    ideas = ideas.filter(i => {
      const tags = normalizeTags(i?.tags).map(t => t.toLowerCase());
      return tags.includes(tf);
    });
  }

  if (ideas.length === 0) {
    return '*(No hay ideas o tareas que coincidan con los criterios)*';
  }

  const groupBy = options.groupBy || 'status';

  if (groupBy === 'status') {
    const order = ['IN_PROGRESS', 'PENDING', 'COMPLETED', 'DISCARDED'];
    const titles = {
      IN_PROGRESS: '⏳ En Progreso (`IN_PROGRESS`)',
      PENDING: '📝 Pendientes (`PENDING`)',
      COMPLETED: '✅ Completadas (`COMPLETED`)',
      DISCARDED: '🗑️ Descartadas (`DISCARDED`)'
    };

    const sections = [];
    for (const st of order) {
      const group = ideas.filter(i => (i?.status || 'PENDING').toString().toUpperCase() === st);
      if (group.length > 0) {
        sections.push(`#### ${titles[st] || st}`);
        for (const item of group) {
          sections.push(renderIdeaLine(item));
        }
      }
    }

    // Remaining if any custom status
    const remaining = ideas.filter(i => !order.includes((i?.status || 'PENDING').toString().toUpperCase()));
    if (remaining.length > 0) {
      sections.push('#### Otros Estados');
      for (const item of remaining) {
        sections.push(renderIdeaLine(item));
      }
    }

    return sections.join('\n');
  } else if (groupBy === 'tag') {
    const tagMap = new Map();
    const untagged = [];

    for (const item of ideas) {
      const itemTags = normalizeTags(item?.tags);
      if (itemTags.length === 0) {
        untagged.push(item);
      } else {
        for (const tag of itemTags) {
          const list = tagMap.get(tag) || [];
          list.push(item);
          tagMap.set(tag, list);
        }
      }
    }

    const sections = [];
    for (const [tag, group] of tagMap.entries()) {
      sections.push(`#### Etiqueta #${tag}`);
      for (const item of group) {
        sections.push(renderIdeaLine(item));
      }
    }
    if (untagged.length > 0) {
      sections.push('#### Sin Etiquetas');
      for (const item of untagged) {
        sections.push(renderIdeaLine(item));
      }
    }
    return sections.join('\n');
  } else {
    return ideas.map(renderIdeaLine).join('\n');
  }
}

/**
 * Render a single idea line with tags and linked plans.
 * @param {object} item
 * @returns {string}
 */
export function renderIdeaLine(item) {
  if (!item || typeof item !== 'object') {
    return '- [?] *(Tarea inválida)*';
  }

  const tags = normalizeTags(item.tags);
  const tagsStr = tags.length > 0
    ? ' ' + tags.map(t => `#${t}`).join(' ')
    : '';

  const rawPlans = Array.isArray(item.plans) ? item.plans : [];
  const plansStr = rawPlans.length > 0
    ? ` *(Planes: ${rawPlans.map(p => {
        if (!p) return '';
        if (typeof p === 'string') return `[${p}](${p})`;
        const label = p.label || p.relativePath || 'Plan';
        const rel = p.relativePath || p.label || '';
        return `[${label}](${rel})`;
      }).filter(Boolean).join(', ')})*`
    : '';

  const statusMarker = {
    PENDING: '[ ]',
    IN_PROGRESS: '[-]',
    COMPLETED: '[x]',
    DISCARDED: '[~]'
  }[(item.status || 'PENDING').toString().toUpperCase()] || '[?]';

  const title = (item.title && typeof item.title === 'string' && item.title.trim())
    ? item.title.trim()
    : '*(sin título)*';

  const idPart = (item.id && typeof item.id === 'string' && item.id.trim())
    ? `**\`${item.id.trim()}\`**: `
    : '';

  return `- ${statusMarker} ${idPart}${title}${tagsStr}${plansStr}`;
}

/**
 * Format created or updated idea response.
 * @param {object} idea
 * @returns {string}
 */
export function formatIdea(idea) {
  if (!idea || typeof idea !== 'object') {
    return '*(Idea no disponible)*';
  }

  const tags = normalizeTags(idea.tags);
  const tagsFormatted = tags.length ? tags.map(t => `#${t}`).join(' ') : '*(sin etiquetas)*';

  const rawPlans = Array.isArray(idea.plans) ? idea.plans : [];
  const plans = rawPlans.length
    ? rawPlans.map(p => typeof p === 'string' ? p : (p?.relativePath || p?.label || 'Plan')).join(', ')
    : '*(ninguno)*';

  const id = idea.id ? `\`${idea.id}\`` : '*(sin id)*';
  const title = (idea.title && typeof idea.title === 'string' && idea.title.trim())
    ? idea.title.trim()
    : '*(sin título)*';
  const status = idea.status ? `\`${idea.status}\`` : '`PENDING`';

  return [
    `✅ **Idea Guardada en GoPlan**`,
    `- **ID**: ${id}`,
    `- **Título**: ${title}`,
    `- **Estado**: ${status}`,
    `- **Etiquetas**: ${tagsFormatted}`,
    `- **Planes asociados**: ${plans}`
  ].join('\n');
}

/**
 * Format plan ingest result.
 * @param {object} result - Ingestion response
 * @param {string} ideaId - Target idea id
 * @param {string} [workspaceId] - Workspace id
 * @returns {string}
 */
export function formatPlanResult(result, ideaId, workspaceId) {
  const safeResult = result || {};
  if (safeResult.success === false) {
    return [
      `❌ **Error al Sincronizar el Plan en GoPlan**`,
      `- **Idea**: \`${ideaId || 'desconocida'}\``,
      safeResult.message ? `- **Motivo**: ${safeResult.message}` : ''
    ].filter(Boolean).join('\n');
  }

  const rawFiles = Array.isArray(safeResult.createdFiles) ? safeResult.createdFiles : [];
  const files = rawFiles.length > 0
    ? rawFiles.map(f => {
        const clean = String(f).replace(/^planes[\\/]/i, '');
        return `\`planes/${clean}\``;
      }).join(', ')
    : '*(ninguno)*';

  const wsPart = workspaceId ? ` en workspace \`${workspaceId}\`` : '';

  return [
    `🚀 **Plan Sincronizado e Ingestado Correctamente**${wsPart}`,
    `- **Idea Vinculada**: \`${ideaId || 'desconocida'}\``,
    `- **Nuevo Estado**: \`IN_PROGRESS\``,
    `- **Archivos Creados/Actualizados**: ${files}`,
    safeResult.message ? `- **Detalle**: ${safeResult.message}` : ''
  ].filter(Boolean).join('\n');
}
