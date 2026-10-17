import { describe, it } from 'node:test';
import assert from 'node:assert';
import { GoPlanClient } from '../src/client.js';

describe('GoPlanClient REST Client', () => {
  it('successfully fetches GET /api/status', async () => {
    const mockStatus = {
      status: 'online',
      app: 'GoPlan',
      version: '1.0.0',
      activeWorkspace: 'ws-main',
      ip: '192.168.1.42',
      port: 8080
    };

    const mockFetch = async (url, opts) => {
      assert.strictEqual(url, 'http://127.0.0.1:8080/api/status');
      return {
        ok: true,
        status: 200,
        statusText: 'OK',
        json: async () => mockStatus
      };
    };

    const client = new GoPlanClient({ fetchFn: mockFetch });
    const status = await client.getStatus();
    assert.deepStrictEqual(status, mockStatus);
  });

  it('resolves active workspace automatically from status when not explicitly provided', async () => {
    const mockFetch = async (url) => {
      if (url.endsWith('/api/status')) {
        return {
          ok: true,
          status: 200,
          statusText: 'OK',
          json: async () => ({ activeWorkspace: 'auto-resolved-ws' })
        };
      }
      throw new Error(`Unexpected url: ${url}`);
    };

    const client = new GoPlanClient({ fetchFn: mockFetch });
    const resolved = await client.resolveActiveWorkspace();
    assert.strictEqual(resolved, 'auto-resolved-ws');

    // Explicit override should take precedence
    const explicit = await client.resolveActiveWorkspace('custom-ws');
    assert.strictEqual(explicit, 'custom-ws');
  });

  it('throws diagnostic error if server has no active workspace', async () => {
    const mockFetch = async () => ({
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => ({ activeWorkspace: null })
    });

    const client = new GoPlanClient({ fetchFn: mockFetch });
    await assert.rejects(
      async () => client.resolveActiveWorkspace(),
      (err) => {
        assert.strictEqual(err.code, 'NO_ACTIVE_WORKSPACE');
        assert(err.message.includes('no hay un workspace activo'));
        return true;
      }
    );
  });

  it('fetches workspaces and creates a new workspace', async () => {
    const workspaces = [
      { id: 'ws-1', name: 'Default', stats: { total: 5, pending: 2, inProgress: 1, completed: 2, discarded: 0 } }
    ];

    const mockFetch = async (url, opts) => {
      if (url.endsWith('/api/workspaces') && (!opts?.method || opts.method === 'GET')) {
        return { ok: true, status: 200, json: async () => workspaces };
      }
      if (url.endsWith('/api/workspaces') && opts?.method === 'POST') {
        const body = JSON.parse(opts.body);
        assert.strictEqual(body.name, 'New Project');
        return {
          ok: true,
          status: 201,
          json: async () => ({ id: 'ws-new', name: 'New Project', stats: { total: 0 } })
        };
      }
      throw new Error(`Unhandled URL: ${url}`);
    };

    const client = new GoPlanClient({ fetchFn: mockFetch });
    const list = await client.getWorkspaces();
    assert.strictEqual(list.length, 1);

    const created = await client.createWorkspace('New Project');
    assert.strictEqual(created.id, 'ws-new');
  });

  it('reads and writes CONTEXT.md', async () => {
    let savedContent = '';
    const mockFetch = async (url, opts) => {
      if (url.includes('/context') && opts?.method === 'PUT') {
        const body = JSON.parse(opts.body);
        savedContent = body.content;
        assert.strictEqual(body.workspaceId, 'my-ws');
        return { ok: true, status: 200, json: async () => ({ success: true }) };
      }
      if (url.includes('/context?format=raw')) {
        return { ok: true, status: 200, text: async () => savedContent };
      }
      if (url.includes('/context')) {
        return { ok: true, status: 200, json: async () => ({ workspaceId: 'my-ws', content: savedContent }) };
      }
      throw new Error(`Unhandled: ${url}`);
    };

    const client = new GoPlanClient({ fetchFn: mockFetch, workspaceId: 'my-ws' });
    const saveRes = await client.saveContext('my-ws', '# New Context\nAll good.');
    assert.strictEqual(saveRes.success, true);
    assert.strictEqual(savedContent, '# New Context\nAll good.');

    const contextJson = await client.getContext('my-ws');
    assert.strictEqual(contextJson.content, '# New Context\nAll good.');

    const contextRaw = await client.getContext('my-ws', { raw: true });
    assert.strictEqual(contextRaw, '# New Context\nAll good.');
  });

  it('reads TODO.md tasks and sends new ideas with #tags', async () => {
    const mockTodo = {
      rawMarkdown: '# Todo',
      ideas: [
        { id: '1', title: 'Task 1', status: 'PENDING', tags: ['ui'], plans: [] }
      ]
    };

    const mockFetch = async (url, opts) => {
      if (url.includes('/todo')) {
        return { ok: true, status: 200, json: async () => mockTodo };
      }
      if (url.includes('/ideas') && opts?.method === 'POST') {
        const body = JSON.parse(opts.body);
        assert.strictEqual(body.text, 'Build auth system #backend #security');
        return {
          ok: true,
          status: 201,
          json: async () => ({
            id: '2',
            title: 'Build auth system',
            status: 'PENDING',
            tags: ['backend', 'security'],
            plans: []
          })
        };
      }
      throw new Error(`Unhandled: ${url}`);
    };

    const client = new GoPlanClient({ fetchFn: mockFetch, workspaceId: 'ws-test' });
    const todo = await client.getTodo('ws-test');
    assert.strictEqual(todo.ideas.length, 1);

    const idea = await client.createIdea('ws-test', 'Build auth system #backend #security');
    assert.strictEqual(idea.id, '2');
    assert.deepStrictEqual(idea.tags, ['backend', 'security']);
  });

  it('updates idea status and deletes idea', async () => {
    let patched = false;
    let deleted = false;

    const mockFetch = async (url, opts) => {
      if (url.includes('/ideas/idea-42') && opts?.method === 'PATCH') {
        const body = JSON.parse(opts.body);
        assert.strictEqual(body.status, 'IN_PROGRESS');
        patched = true;
        return { ok: true, status: 200, json: async () => ({ success: true }) };
      }
      if (url.includes('/ideas/idea-42') && opts?.method === 'DELETE') {
        deleted = true;
        return { ok: true, status: 200, json: async () => ({ success: true, message: 'Deleted' }) };
      }
      throw new Error(`Unhandled: ${url}`);
    };

    const client = new GoPlanClient({ fetchFn: mockFetch, workspaceId: 'ws-test' });
    const patchRes = await client.updateIdea('ws-test', 'idea-42', { status: 'IN_PROGRESS' });
    assert.strictEqual(patchRes.success, true);
    assert.strictEqual(patched, true);

    const delRes = await client.deleteIdea('ws-test', 'idea-42');
    assert.strictEqual(delRes.success, true);
    assert.strictEqual(deleted, true);
  });

  it('reads plan file and sanitizes directory traversal', async () => {
    const mockFetch = async (url) => {
      if (url.includes('/plans/auth_plan.md')) {
        return { ok: true, status: 200, text: async () => '# Auth Plan Content' };
      }
      throw new Error(`Unhandled: ${url}`);
    };

    const client = new GoPlanClient({ fetchFn: mockFetch, workspaceId: 'ws-test' });
    // Normal name
    const plan1 = await client.getPlan('ws-test', 'auth_plan.md');
    assert.strictEqual(plan1, '# Auth Plan Content');

    // Should strip leading planes/
    const plan2 = await client.getPlan('ws-test', 'planes/auth_plan.md');
    assert.strictEqual(plan2, '# Auth Plan Content');

    // Traversal attack should be rejected
    await assert.rejects(
      async () => client.getPlan('ws-test', '../../secret.md'),
      (err) => err.message.includes('path traversal not allowed')
    );
  });

  it('saves plan and transitions idea status to IN_PROGRESS', async () => {
    let planIngested = false;
    let statusUpdated = false;

    const mockFetch = async (url, opts) => {
      if (url.includes('/plans') && opts?.method === 'POST') {
        const body = JSON.parse(opts.body);
        assert.strictEqual(body.ideaId, 'idea-99');
        assert.strictEqual(body.files.length, 1);
        assert.strictEqual(body.files[0].fileName, 'login_plan.md');
        planIngested = true;
        return {
          ok: true,
          status: 201,
          json: async () => ({ success: true, createdFiles: ['login_plan.md'] })
        };
      }
      if (url.includes('/ideas/idea-99') && opts?.method === 'PATCH') {
        const body = JSON.parse(opts.body);
        assert.strictEqual(body.status, 'IN_PROGRESS');
        statusUpdated = true;
        return { ok: true, status: 200, json: async () => ({ success: true }) };
      }
      throw new Error(`Unhandled: ${url}`);
    };

    const client = new GoPlanClient({ fetchFn: mockFetch, workspaceId: 'ws-test' });
    const result = await client.savePlan('ws-test', {
      ideaId: 'idea-99',
      fileName: 'login_plan.md',
      content: '# Step 1\nStep 2',
      updateStatusToInProgress: true
    });

    assert.strictEqual(result.success, true);
    assert.strictEqual(result.statusUpdated, true);
    assert.deepStrictEqual(result.createdFiles, ['login_plan.md']);
    assert.strictEqual(planIngested, true);
    assert.strictEqual(statusUpdated, true);
  });

  it('exports workspace as Markdown and ZIP', async () => {
    const mockFetch = async (url) => {
      if (url.includes('/export?type=md')) {
        return { ok: true, status: 200, text: async () => '# Full Markdown Bundle' };
      }
      if (url.includes('/export?type=zip')) {
        const buffer = new Uint8Array([0x50, 0x4b, 0x03, 0x04]).buffer; // PK header
        return { ok: true, status: 200, arrayBuffer: async () => buffer };
      }
      throw new Error(`Unhandled: ${url}`);
    };

    const client = new GoPlanClient({ fetchFn: mockFetch, workspaceId: 'ws-test' });
    const mdExport = await client.exportWorkspace('ws-test', { type: 'md' });
    assert.strictEqual(mdExport.type, 'md');
    assert.strictEqual(mdExport.content, '# Full Markdown Bundle');

    const zipExport = await client.exportWorkspace('ws-test', { type: 'zip' });
    assert.strictEqual(zipExport.type, 'zip');
    assert.strictEqual(zipExport.content.length, 4);
    assert.strictEqual(zipExport.content[0], 0x50);
  });

  it('handles connection refused and produces diagnostic message', async () => {
    const mockFetch = async () => {
      const err = new Error('fetch failed');
      err.cause = { code: 'ECONNREFUSED' };
      throw err;
    };

    const client = new GoPlanClient({ serverUrl: 'http://192.168.1.99:8080', fetchFn: mockFetch });
    await assert.rejects(
      async () => client.getStatus(),
      (err) => {
        assert(err.diagnostic.includes('No se pudo conectar al servidor GoPlan'));
        assert(err.diagnostic.includes('192.168.1.99'));
        return true;
      }
    );
  });

  it('handles HTTP error responses gracefully', async () => {
    const mockFetch = async () => ({
      ok: false,
      status: 404,
      statusText: 'Not Found',
      json: async () => ({ message: 'Workspace not found' })
    });

    const client = new GoPlanClient({ fetchFn: mockFetch, workspaceId: 'non-existent' });
    await assert.rejects(
      async () => client.getTodo('non-existent'),
      (err) => {
        assert.strictEqual(err.status, 404);
        assert(err.message.includes('Workspace not found'));
        return true;
      }
    );
  });
});
