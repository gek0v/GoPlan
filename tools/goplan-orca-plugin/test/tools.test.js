import { describe, it } from 'node:test';
import assert from 'node:assert';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { TOOL_DEFINITIONS, createToolHandlers } from '../src/mcp/tools.js';
import { GoPlanClient } from '../src/client.js';

describe('MCP Tools Handlers', () => {
  it('exposes exactly the 8 required MCP tools with descriptions and schemas', () => {
    const requiredTools = [
      'goplan_status',
      'goplan_get_context',
      'goplan_save_context',
      'goplan_get_todo',
      'goplan_send_idea',
      'goplan_get_plan',
      'goplan_save_plan',
      'goplan_export'
    ];

    assert.strictEqual(TOOL_DEFINITIONS.length, 8);
    for (const name of requiredTools) {
      const def = TOOL_DEFINITIONS.find(t => t.name === name);
      assert(def, `Missing definition for tool ${name}`);
      assert(def.description && def.description.length > 10);
      assert(def.inputSchema && def.inputSchema.type === 'object');
    }
  });

  it('goplan_status returns formatted status text and raw json', async () => {
    const mockClient = {
      serverUrl: 'http://127.0.0.1:8080',
      getStatus: async () => ({
        status: 'online',
        app: 'GoPlan',
        version: '1.0.0',
        activeWorkspace: 'ws-main',
        ip: '192.168.1.15',
        port: 8080
      })
    };

    const handlers = createToolHandlers(mockClient);
    const res = await handlers.goplan_status({});
    assert(!res.isError);
    assert.strictEqual(res.content.length, 2);
    assert(res.content[0].text.includes('GoPlan Server Status'));
    assert(res.content[0].text.includes('ws-main'));
    assert(res.content[1].text.includes('192.168.1.15'));
  });

  it('goplan_get_context returns CONTEXT.md content', async () => {
    const mockClient = {
      getContext: async (wsId, opts) => ({
        workspaceId: wsId || 'active-ws',
        content: '# Architecture & Context\nImportant instructions.'
      })
    };

    const handlers = createToolHandlers(mockClient);
    const res = await handlers.goplan_get_context({});
    assert(!res.isError);
    assert(res.content[0].text.includes('# Architecture & Context'));
    assert(res.content[0].text.includes('active-ws'));
  });

  it('goplan_save_context updates CONTEXT.md and validates input', async () => {
    let savedWs, savedContent;
    const mockClient = {
      resolveActiveWorkspace: async (id) => id || 'ws-def',
      saveContext: async (wsId, content) => {
        savedWs = wsId;
        savedContent = content;
        return { success: true };
      }
    };

    const handlers = createToolHandlers(mockClient);

    // Error when content is missing
    const errRes = await handlers.goplan_save_context({});
    assert.strictEqual(errRes.isError, true);
    assert(errRes.content[0].text.includes('obligatorio'));

    // Success
    const res = await handlers.goplan_save_context({ content: '# Updated Context' });
    assert(!res.isError);
    assert.strictEqual(savedWs, 'ws-def');
    assert.strictEqual(savedContent, '# Updated Context');
    assert(res.content[0].text.includes('actualizado correctamente'));
  });

  it('goplan_get_todo formats tasks by status and by tag', async () => {
    const mockClient = {
      resolveActiveWorkspace: async () => 'ws-todo',
      getTodo: async () => ({
        ideas: [
          { id: '1', title: 'Task A', status: 'PENDING', tags: ['frontend'], plans: [] },
          { id: '2', title: 'Task B', status: 'IN_PROGRESS', tags: ['backend'], plans: [{ label: 'p1', relativePath: 'planes/p1.md' }] }
        ]
      })
    };

    const handlers = createToolHandlers(mockClient);

    // Group by status
    const resStatus = await handlers.goplan_get_todo({ groupBy: 'status' });
    assert(!resStatus.isError);
    assert(resStatus.content[0].text.includes('En Progreso (`IN_PROGRESS`)'));
    assert(resStatus.content[0].text.includes('Task B'));
    assert(resStatus.content[0].text.includes('Pendientes (`PENDING`)'));
    assert(resStatus.content[0].text.includes('Task A'));

    // Group by tag
    const resTag = await handlers.goplan_get_todo({ groupBy: 'tag' });
    assert(!resTag.isError);
    assert(resTag.content[0].text.includes('Etiqueta #frontend'));
    assert(resTag.content[0].text.includes('Etiqueta #backend'));
  });

  it('goplan_send_idea sends idea with hashtags and validates text', async () => {
    let sentText;
    const mockClient = {
      resolveActiveWorkspace: async () => 'ws-ideas',
      createIdea: async (wsId, text) => {
        sentText = text;
        return {
          id: 'idea-10',
          title: 'Add dark mode',
          status: 'PENDING',
          tags: ['theme', 'ui'],
          plans: []
        };
      }
    };

    const handlers = createToolHandlers(mockClient);

    // Missing text
    const errRes = await handlers.goplan_send_idea({});
    assert.strictEqual(errRes.isError, true);

    // Success
    const res = await handlers.goplan_send_idea({ text: 'Add dark mode #theme #ui' });
    assert(!res.isError);
    assert.strictEqual(sentText, 'Add dark mode #theme #ui');
    assert(res.content[0].text.includes('idea-10'));
    assert(res.content[0].text.includes('#theme'));
  });

  it('goplan_get_plan reads plan file content and validates fileName', async () => {
    const mockClient = {
      resolveActiveWorkspace: async () => 'ws-plans',
      getPlan: async (wsId, fileName) => `# Plan Content for ${fileName}`
    };

    const handlers = createToolHandlers(mockClient);

    // Missing fileName
    const errRes = await handlers.goplan_get_plan({});
    assert.strictEqual(errRes.isError, true);

    // Success
    const res = await handlers.goplan_get_plan({ fileName: 'auth.md' });
    assert(!res.isError);
    assert(res.content[0].text.includes('# Plan Content for auth.md'));
  });

  it('goplan_save_plan ingests plan and reports status update', async () => {
    let savedParams;
    const mockClient = {
      resolveActiveWorkspace: async () => 'ws-plans',
      savePlan: async (wsId, params) => {
        savedParams = params;
        return {
          success: true,
          createdFiles: ['my_plan.md'],
          ideaId: params.ideaId,
          statusUpdated: true
        };
      }
    };

    const handlers = createToolHandlers(mockClient);

    // Missing ideaId
    const errRes = await handlers.goplan_save_plan({});
    assert.strictEqual(errRes.isError, true);

    // Success
    const res = await handlers.goplan_save_plan({
      ideaId: 'idea-77',
      fileName: 'my_plan.md',
      content: '# Plan Details'
    });
    assert(!res.isError);
    assert.strictEqual(savedParams.ideaId, 'idea-77');
    assert(res.content[0].text.includes('IN_PROGRESS'));
    assert(res.content[0].text.includes('planes/my_plan.md'));
  });

  it('goplan_export supports Markdown and writing ZIP to disk', async () => {
    const mockClient = {
      resolveActiveWorkspace: async () => 'ws-exp',
      exportWorkspace: async (wsId, opts) => {
        if (opts.type === 'zip') {
          return { type: 'zip', content: new Uint8Array([1, 2, 3, 4]) };
        }
        return { type: 'md', content: '# Full MD Bundle' };
      }
    };

    const handlers = createToolHandlers(mockClient);

    // In-memory markdown
    const mdRes = await handlers.goplan_export({ type: 'md' });
    assert(!mdRes.isError);
    assert(mdRes.content[0].text.includes('# Full MD Bundle'));

    // ZIP export to file
    const tmpZip = path.join(os.tmpdir(), `test_export_${Date.now()}.zip`);
    try {
      const zipRes = await handlers.goplan_export({ type: 'zip', outputPath: tmpZip });
      assert(!zipRes.isError);
      assert(fs.existsSync(tmpZip));
      assert.strictEqual(fs.readFileSync(tmpZip).length, 4);
    } finally {
      if (fs.existsSync(tmpZip)) fs.unlinkSync(tmpZip);
    }
  });

  it('properly returns isError=true and diagnostic on network failure', async () => {
    const mockClient = {
      getStatus: async () => {
        const err = new Error('ECONNREFUSED');
        err.diagnostic = 'Diagnóstico: no se pudo conectar al servidor';
        throw err;
      }
    };

    const handlers = createToolHandlers(mockClient);
    const res = await handlers.goplan_status({});
    assert.strictEqual(res.isError, true);
    assert(res.content[0].text.includes('Diagnóstico: no se pudo conectar'));
  });
});
