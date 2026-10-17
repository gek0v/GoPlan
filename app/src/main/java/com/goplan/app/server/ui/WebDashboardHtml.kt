package com.goplan.app.server.ui

fun getWebDashboardHtml(): String = """
<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>GoPlan - Panel de Control Local</title>
  <style>
    :root {
      --bg: #121418;
      --surface: #1b1f24;
      --surface-hover: #242930;
      --card-bg: #21262d;
      --primary: #4e88e8;
      --primary-hover: #3b74d4;
      --text: #e6edf3;
      --text-muted: #8b949e;
      --border: #30363d;
      --success: #238636;
      --warning: #d29922;
      --purple: #8957e5;
      --danger: #da3633;
      --font: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background: var(--bg);
      color: var(--text);
      font-family: var(--font);
      line-height: 1.5;
      padding: 24px 16px;
    }
    .container { max-width: 980px; margin: 0 auto; }
    header {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 16px;
      padding-bottom: 20px;
      border-bottom: 1px solid var(--border);
      margin-bottom: 24px;
    }
    .logo-area { display: flex; align-items: center; gap: 12px; }
    .logo-badge {
      background: linear-gradient(135deg, #1f6feb, #8957e5);
      color: white;
      font-weight: 800;
      font-size: 1.25rem;
      padding: 6px 12px;
      border-radius: 8px;
      letter-spacing: 0.5px;
    }
    .status-pill {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      border-radius: 16px;
      font-size: 0.8rem;
      font-weight: 600;
      background: rgba(35, 134, 54, 0.2);
      color: #3fb950;
      border: 1px solid rgba(63, 185, 80, 0.3);
    }
    .status-dot {
      width: 8px;
      height: 8px;
      background: #3fb950;
      border-radius: 50%;
      box-shadow: 0 0 8px #3fb950;
    }
    .header-controls { display: flex; align-items: center; gap: 10px; }
    select, input[type="text"], textarea {
      background: var(--surface);
      border: 1px solid var(--border);
      color: var(--text);
      padding: 8px 12px;
      border-radius: 6px;
      font-size: 0.9rem;
      outline: none;
      font-family: inherit;
    }
    select:focus, input[type="text"]:focus, textarea:focus {
      border-color: var(--primary);
      box-shadow: 0 0 0 2px rgba(78, 136, 232, 0.2);
    }
    button {
      background: var(--primary);
      color: white;
      border: none;
      padding: 8px 14px;
      border-radius: 6px;
      font-size: 0.9rem;
      font-weight: 600;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      transition: background 0.15s;
    }
    button:hover { background: var(--primary-hover); }
    button.secondary {
      background: var(--surface-hover);
      border: 1px solid var(--border);
      color: var(--text);
    }
    button.secondary:hover { background: var(--card-bg); }
    
    .stats-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
      gap: 12px;
      margin-bottom: 24px;
    }
    .stat-card {
      background: var(--surface);
      border: 1px solid var(--border);
      padding: 12px 16px;
      border-radius: 8px;
      text-align: center;
    }
    .stat-number { font-size: 1.6rem; font-weight: 700; }
    .stat-label { font-size: 0.75rem; color: var(--text-muted); text-transform: uppercase; }

    .card {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 20px;
      margin-bottom: 24px;
    }
    .card-title {
      font-size: 1.1rem;
      font-weight: 600;
      margin-bottom: 14px;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .capture-form { display: flex; gap: 8px; }
    .capture-form input { flex: 1; }

    .tabs {
      display: flex;
      gap: 8px;
      border-bottom: 1px solid var(--border);
      margin-bottom: 16px;
    }
    .tab {
      padding: 8px 16px;
      cursor: pointer;
      font-weight: 600;
      font-size: 0.9rem;
      color: var(--text-muted);
      border-bottom: 2px solid transparent;
    }
    .tab.active {
      color: var(--primary);
      border-bottom-color: var(--primary);
    }

    .todo-list { list-style: none; display: flex; flex-direction: column; gap: 8px; }
    .todo-item {
      background: var(--card-bg);
      border: 1px solid var(--border);
      padding: 10px 14px;
      border-radius: 6px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
    }
    .todo-item.completed { opacity: 0.65; }
    .todo-item.completed .todo-title { text-decoration: line-through; }
    .todo-left { display: flex; align-items: center; gap: 10px; flex: 1; }
    .status-badge {
      font-size: 0.7rem;
      padding: 2px 6px;
      border-radius: 4px;
      font-weight: 600;
      font-family: monospace;
    }
    .badge-pending { background: rgba(210, 153, 34, 0.2); color: #e3b341; border: 1px solid rgba(227, 179, 65, 0.3); }
    .badge-in_progress { background: rgba(78, 136, 232, 0.2); color: #58a6ff; border: 1px solid rgba(88, 166, 255, 0.3); }
    .badge-completed { background: rgba(35, 134, 54, 0.2); color: #3fb950; border: 1px solid rgba(63, 185, 80, 0.3); }
    .badge-discarded { background: rgba(218, 54, 51, 0.2); color: #f85149; border: 1px solid rgba(248, 81, 73, 0.3); }

    .tag-badge {
      background: rgba(137, 87, 229, 0.15);
      color: #bc8cff;
      border: 1px solid rgba(188, 140, 255, 0.25);
      padding: 2px 6px;
      border-radius: 4px;
      font-size: 0.75rem;
    }
    .plan-btn {
      background: var(--surface);
      border: 1px solid var(--border);
      color: #58a6ff;
      font-size: 0.75rem;
      padding: 4px 8px;
      border-radius: 4px;
      text-decoration: none;
      cursor: pointer;
    }
    .plan-btn:hover { background: var(--surface-hover); }

    .modal-overlay {
      display: none;
      position: fixed;
      inset: 0;
      background: rgba(0, 0, 0, 0.75);
      z-index: 100;
      align-items: center;
      justify-content: center;
      padding: 20px;
    }
    .modal-overlay.active { display: flex; }
    .modal-content {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 10px;
      max-width: 800px;
      width: 100%;
      max-height: 85vh;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
    .modal-header {
      padding: 14px 20px;
      border-bottom: 1px solid var(--border);
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    .modal-body {
      padding: 20px;
      overflow-y: auto;
      font-family: monospace;
      white-space: pre-wrap;
      font-size: 0.85rem;
      background: var(--card-bg);
      color: #e6edf3;
      flex: 1;
    }

    .export-buttons { display: flex; flex-wrap: wrap; gap: 10px; }
    .api-endpoint {
      background: var(--card-bg);
      border: 1px solid var(--border);
      padding: 10px;
      border-radius: 6px;
      margin-bottom: 8px;
      font-family: monospace;
      font-size: 0.85rem;
    }
    .api-method { font-weight: 700; color: #58a6ff; }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div class="logo-area">
        <div class="logo-badge">GoPlan</div>
        <div class="status-pill">
          <span class="status-dot"></span>
          <span id="statusText">Conectado</span>
        </div>
        <span id="ipPort" style="font-size: 0.85rem; color: var(--text-muted); font-family: monospace;"></span>
      </div>
      <div class="header-controls">
        <label for="wsSelect" style="font-size: 0.85rem; color: var(--text-muted);">Workspace:</label>
        <select id="wsSelect" onchange="onWorkspaceChanged()"></select>
        <button class="secondary" onclick="loadAllData()">↻ Actualizar</button>
      </div>
    </header>

    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-number" id="statTotal" style="color: var(--primary);">-</div>
        <div class="stat-label">Total Ideas</div>
      </div>
      <div class="stat-card">
        <div class="stat-number" id="statPending" style="color: var(--warning);">-</div>
        <div class="stat-label">Pendientes</div>
      </div>
      <div class="stat-card">
        <div class="stat-number" id="statInProgress" style="color: #58a6ff;">-</div>
        <div class="stat-label">En Progreso</div>
      </div>
      <div class="stat-card">
        <div class="stat-number" id="statCompleted" style="color: var(--success);">-</div>
        <div class="stat-label">Completadas</div>
      </div>
    </div>

    <div class="card">
      <div class="card-title">Captura Rápida desde el PC</div>
      <form class="capture-form" onsubmit="sendIdea(event)">
        <input type="text" id="newIdeaInput" placeholder="Escribe una idea o tarea con etiquetas (#feature #urgente)..." required autofocus>
        <button type="submit">Enviar a GoPlan</button>
      </form>
    </div>

    <div class="tabs">
      <div class="tab active" id="tabTodo" onclick="switchTab('todo')">📋 Tareas y Planes</div>
      <div class="tab" id="tabContext" onclick="switchTab('context')">📄 CONTEXT.md</div>
      <div class="tab" id="tabExport" onclick="switchTab('export')">📦 Exportar para Agentes</div>
      <div class="tab" id="tabApi" onclick="switchTab('api')">⚡ API REST</div>
    </div>

    <div id="contentTodo">
      <div class="card">
        <div class="card-title">
          <span>TODO.md</span>
          <span id="todoCount" style="font-size: 0.85rem; color: var(--text-muted);"></span>
        </div>
        <ul class="todo-list" id="todoList">
          <li style="color: var(--text-muted); text-align: center; padding: 20px;">Cargando tareas...</li>
        </ul>
      </div>
    </div>

    <div id="contentContext" style="display: none;">
      <div class="card">
        <div class="card-title">
          <span>CONTEXT.md del Workspace</span>
          <button onclick="saveContext()">Guardar Cambios</button>
        </div>
        <textarea id="contextEditor" rows="16" style="width: 100%; font-family: monospace; font-size: 0.9rem;" placeholder="# Contexto del proyecto..."></textarea>
      </div>
    </div>

    <div id="contentExport" style="display: none;">
      <div class="card">
        <div class="card-title">Paquetes para Agentes de IA (Orca, Cursor, Antigravity)</div>
        <p style="color: var(--text-muted); margin-bottom: 16px; font-size: 0.9rem;">
          Descarga los archivos del workspace listos para ser consumidos por cualquier agente en el PC.
        </p>
        <div class="export-buttons">
          <button onclick="downloadExport('zip')">📦 Descargar Bundle ZIP</button>
          <button class="secondary" onclick="downloadExport('md')">📄 Descargar Markdown Unificado</button>
        </div>
      </div>
    </div>

    <div id="contentApi" style="display: none;">
      <div class="card">
        <div class="card-title">Endpoints REST Disponibles</div>
        <div class="api-endpoint"><span class="api-method">GET</span> /api/status</div>
        <div class="api-endpoint"><span class="api-method">GET</span> /api/workspaces</div>
        <div class="api-endpoint"><span class="api-method">GET</span> /api/workspaces/{id}/context</div>
        <div class="api-endpoint"><span class="api-method">PUT</span> /api/workspaces/{id}/context</div>
        <div class="api-endpoint"><span class="api-method">GET</span> /api/workspaces/{id}/todo</div>
        <div class="api-endpoint"><span class="api-method">POST</span> /api/workspaces/{id}/ideas</div>
        <div class="api-endpoint"><span class="api-method">PATCH</span> /api/workspaces/{id}/ideas/{ideaId}</div>
        <div class="api-endpoint"><span class="api-method">POST</span> /api/workspaces/{id}/plans</div>
        <div class="api-endpoint"><span class="api-method">GET</span> /api/workspaces/{id}/plans/{fileName}</div>
        <div class="api-endpoint"><span class="api-method">GET</span> /api/workspaces/{id}/export?type=zip|md</div>
      </div>
    </div>
  </div>

  <div class="modal-overlay" id="planModal" onclick="closeModalOnOverlay(event)">
    <div class="modal-content">
      <div class="modal-header">
        <h3 id="planModalTitle">Plan</h3>
        <button class="secondary" onclick="closePlanModal()">✕ Cerrar</button>
      </div>
      <div class="modal-body" id="planModalBody"></div>
    </div>
  </div>

  <script>
    let activeWorkspaceId = '';

    async function loadAllData() {
      try {
        const statusRes = await fetch('/api/status');
        const statusData = await statusRes.json();
        document.getElementById('ipPort').textContent = statusData.ip + ':' + statusData.port;

        const wsRes = await fetch('/api/workspaces');
        const workspaces = await wsRes.json();
        const wsSelect = document.getElementById('wsSelect');
        wsSelect.innerHTML = '';
        
        let targetId = activeWorkspaceId || (statusData.activeWorkspace ? statusData.activeWorkspace.id : (workspaces[0] ? workspaces[0].id : ''));
        workspaces.forEach(ws => {
          const opt = document.createElement('option');
          opt.value = ws.id;
          opt.textContent = ws.name;
          if (ws.id === targetId) opt.selected = true;
          wsSelect.appendChild(opt);
        });

        activeWorkspaceId = wsSelect.value;
        if (activeWorkspaceId) {
          const currentWs = workspaces.find(w => w.id === activeWorkspaceId);
          if (currentWs && currentWs.stats) {
            document.getElementById('statTotal').textContent = currentWs.stats.total;
            document.getElementById('statPending').textContent = currentWs.stats.pending;
            document.getElementById('statInProgress').textContent = currentWs.stats.inProgress;
            document.getElementById('statCompleted').textContent = currentWs.stats.completed;
          }
          await loadWorkspaceContent(activeWorkspaceId);
        }
      } catch (err) {
        console.error('Error cargando datos:', err);
      }
    }

    async function loadWorkspaceContent(wsId) {
      // Load TODO
      try {
        const todoRes = await fetch(`/api/workspaces/${'$'}{wsId}/todo`);
        const todoData = await todoRes.json();
        const todoList = document.getElementById('todoList');
        todoList.innerHTML = '';

        if (!todoData.ideas || todoData.ideas.length === 0) {
          todoList.innerHTML = '<li style="color: var(--text-muted); text-align: center; padding: 20px;">No hay tareas en este workspace.</li>';
        } else {
          document.getElementById('todoCount').textContent = `${'$'}{todoData.ideas.length} tareas`;
          todoData.ideas.forEach(idea => {
            const li = document.createElement('li');
            li.className = `todo-item ${'$'}{idea.status.toLowerCase()}`;
            
            const badgeClass = `badge-${'$'}{idea.status.toLowerCase()}`;
            const tagsHtml = (idea.tags || []).map(t => `<span class="tag-badge">#${'$'}{t}</span>`).join(' ');
            const plansHtml = (idea.plans || []).map(p => 
              `<button class="plan-btn" onclick="openPlan('${'$'}{wsId}', '${'$'}{encodeURIComponent(p.relativePath)}')">📄 ${'$'}{escapeHtml(p.label || 'Plan')}</button>`
            ).join(' ');

            li.innerHTML = `
              <div class="todo-left">
                <span class="status-badge ${'$'}{badgeClass}">${'$'}{idea.status}</span>
                <span class="todo-title">${'$'}{escapeHtml(idea.title)}</span>
                ${'$'}{tagsHtml}
              </div>
              <div style="display: flex; gap: 6px;">
                ${'$'}{plansHtml}
              </div>
            `;
            todoList.appendChild(li);
          });
        }
      } catch (e) {
        console.error('Error cargando TODO:', e);
      }

      // Load Context
      try {
        const ctxRes = await fetch(`/api/workspaces/${'$'}{wsId}/context`);
        const ctxData = await ctxRes.json();
        document.getElementById('contextEditor').value = ctxData.content || '';
      } catch (e) {
        console.error('Error cargando context:', e);
      }
    }

    async function sendIdea(e) {
      e.preventDefault();
      const input = document.getElementById('newIdeaInput');
      const text = input.value.trim();
      if (!text || !activeWorkspaceId) return;

      try {
        const res = await fetch(`/api/workspaces/${'$'}{activeWorkspaceId}/ideas`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text })
        });
        if (res.ok) {
          input.value = '';
          await loadAllData();
        } else {
          alert('Error al enviar la idea');
        }
      } catch (err) {
        alert('Error de conexión: ' + err.message);
      }
    }

    async function saveContext() {
      if (!activeWorkspaceId) return;
      const content = document.getElementById('contextEditor').value;
      try {
        const res = await fetch(`/api/workspaces/${'$'}{activeWorkspaceId}/context`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ workspaceId: activeWorkspaceId, content })
        });
        if (res.ok) {
          alert('CONTEXT.md guardado correctamente en GoPlan');
        } else {
          alert('Error al guardar CONTEXT.md');
        }
      } catch (err) {
        alert('Error de conexión: ' + err.message);
      }
    }

    async function openPlan(wsId, rawRelativePath) {
      try {
        const cleanPath = decodeURIComponent(rawRelativePath).replace(/^planes\//, '');
        const res = await fetch(`/api/workspaces/${'$'}{wsId}/plans/${'$'}{cleanPath}`);
        if (res.ok) {
          const markdown = await res.text();
          document.getElementById('planModalTitle').textContent = cleanPath;
          document.getElementById('planModalBody').textContent = markdown;
          document.getElementById('planModal').classList.add('active');
        } else {
          alert('No se pudo cargar el archivo del plan');
        }
      } catch (e) {
        alert('Error al cargar plan: ' + e.message);
      }
    }

    function closePlanModal() {
      document.getElementById('planModal').classList.remove('active');
    }

    function closeModalOnOverlay(e) {
      if (e.target.id === 'planModal') closePlanModal();
    }

    function downloadExport(type) {
      if (!activeWorkspaceId) return;
      window.open(`/api/workspaces/${'$'}{activeWorkspaceId}/export?type=${'$'}{type}`, '_blank');
    }

    function onWorkspaceChanged() {
      activeWorkspaceId = document.getElementById('wsSelect').value;
      loadAllData();
    }

    function switchTab(tabId) {
      ['todo', 'context', 'export', 'api'].forEach(t => {
        const tabEl = document.getElementById('tab' + t.charAt(0).toUpperCase() + t.slice(1));
        const contentEl = document.getElementById('content' + t.charAt(0).toUpperCase() + t.slice(1));
        if (tabEl) tabEl.classList.toggle('active', t === tabId);
        if (contentEl) contentEl.style.display = (t === tabId) ? 'block' : 'none';
      });
    }

    function escapeHtml(text) {
      const div = document.createElement('div');
      div.textContent = text || '';
      return div.innerHTML;
    }

    window.addEventListener('DOMContentLoaded', loadAllData);
  </script>
</body>
</html>
""".trimIndent()
