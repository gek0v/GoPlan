import { describe, it } from 'node:test';
import assert from 'node:assert';
import { PassThrough } from 'node:stream';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  formatStatus,
  formatTodo,
  formatIdea,
  formatPlanResult,
  renderIdeaLine,
  normalizeTags
} from '../src/utils/formatter.js';
import { GoPlanClient } from '../src/client.js';
import { McpServer } from '../src/mcp/server.js';
import { createToolHandlers } from '../src/mcp/tools.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const cliPath = path.resolve(__dirname, '../bin/cli.js');

function runCli(args, env = {}) {
  return new Promise((resolve) => {
    const proc = spawn(process.execPath, [cliPath, ...args], {
      env: { ...process.env, ...env }
    });

    let stdout = '';
    let stderr = '';

    proc.stdout.on('data', d => { stdout += d.toString(); });
    proc.stderr.on('data', d => { stderr += d.toString(); });

    proc.on('close', code => {
      resolve({ code, stdout, stderr });
    });
  });
}

describe('Reviewer 3 Forensic Refinements & Edge Case Tests', () => {
  // Test 1: Formatter null/undefined robustness across all 4 functions
  it('formatters survive null and undefined inputs without TypeErrors', () => {
    // formatStatus
    const statusNull = formatStatus(null);
    assert(statusNull.includes('🔴'));
    assert(statusNull.includes('OFFLINE'));

    const statusUndefined = formatStatus(undefined);
    assert(statusUndefined.includes('🔴'));

    // formatTodo
    const todoNull = formatTodo(null);
    assert(todoNull.includes('No hay ideas o tareas'));

    const todoEmptyObj = formatTodo({});
    assert(todoEmptyObj.includes('No hay ideas o tareas'));

    // formatIdea
    const ideaNull = formatIdea(null);
    assert(ideaNull.includes('no disponible'));

    // formatPlanResult
    const planNull = formatPlanResult(null, 'idea-1');
    assert(planNull.includes('idea-1'));
  });

  // Test 2: renderIdeaLine edge cases (empty text, missing id, missing status, emojis)
  it('renderIdeaLine handles empty titles, missing IDs, missing status, and UTF-8 emojis', () => {
    // Empty title
    const lineEmptyTitle = renderIdeaLine({ id: 't1', title: '', status: 'PENDING' });
    assert(lineEmptyTitle.includes('*(sin título)*'));
    assert(lineEmptyTitle.includes('`t1`'));

    // Missing ID
    const lineMissingId = renderIdeaLine({ title: 'Task without ID', status: 'IN_PROGRESS' });
    assert(lineMissingId.includes('[-]'));
    assert(lineMissingId.includes('Task without ID'));
    assert(!lineMissingId.includes('`undefined`'));

    // Missing status defaults to PENDING marker
    const lineMissingStatus = renderIdeaLine({ id: 't2', title: 'Task with no status' });
    assert(lineMissingStatus.includes('[ ]'));

    // UTF-8 emojis in title, tags, and plans
    const lineEmojis = renderIdeaLine({
      id: '🚀-1',
      title: 'Desplegar a producción 🔐✨',
      status: 'completed',
      tags: ['release', 'prod🔥'],
      plans: [{ label: 'Plan 🚀', relativePath: 'planes/prod.md' }]
    });
    assert(lineEmojis.includes('[x]'));
    assert(lineEmojis.includes('🚀-1'));
    assert(lineEmojis.includes('Desplegar a producción 🔐✨'));
    assert(lineEmojis.includes('#release #prod🔥'));
    assert(lineEmojis.includes('[Plan 🚀](planes/prod.md)'));
  });

  // Test 3: normalizeTags and adjoining hashtags (#tag1#tag2)
  it('normalizeTags handles adjoining hashtags, duplicate hashes, and special characters', () => {
    const raw = ['tag1#tag2', '#tag3', '  #tag4  ', 'c++', 'v1.0', null, undefined, '', 'emoji_🎯#emoji_🚀'];
    const normalized = normalizeTags(raw);
    assert.deepStrictEqual(normalized, [
      'tag1',
      'tag2',
      'tag3',
      'tag4',
      'c++',
      'v1.0',
      'emoji_🎯',
      'emoji_🚀'
    ]);
  });

  // Test 4: formatTodo filtering with adjoining tags and case insensitivity
  it('formatTodo filters correctly by tags when adjoining hashtags are present', () => {
    const todo = {
      ideas: [
        { id: '1', title: 'Idea One', status: 'PENDING', tags: ['auth#security'] },
        { id: '2', title: 'Idea Two', status: 'PENDING', tags: ['#frontend'] },
        { id: '3', title: 'Idea Three', status: 'PENDING', tags: ['ui', 'backend'] }
      ]
    };

    // Filter by 'security' (which was part of 'auth#security')
    const resSec = formatTodo(todo, { tagFilter: 'security' });
    assert(resSec.includes('Idea One'));
    assert(!resSec.includes('Idea Two'));
    assert(!resSec.includes('Idea Three'));

    // Filter with leading # and uppercase
    const resFront = formatTodo(todo, { tagFilter: '#FRONTEND' });
    assert(resFront.includes('Idea Two'));
    assert(!resFront.includes('Idea One'));
  });

  // Test 5: formatTodo groupBy: 'tag' with multiple and adjoining tags
  it('formatTodo groupBy tag splits adjoining tags and groups accurately', () => {
    const todo = {
      ideas: [
        { id: '1', title: 'Idea 1', status: 'PENDING', tags: ['frontend#urgent'] },
        { id: '2', title: 'Idea 2', status: 'PENDING', tags: ['urgent'] }
      ]
    };

    const grouped = formatTodo(todo, { groupBy: 'tag' });
    assert(grouped.includes('#### Etiqueta #frontend'));
    assert(grouped.includes('#### Etiqueta #urgent'));
    assert(!grouped.includes('#### Etiqueta #frontend#urgent'));
  });

  // Test 6: client.createIdea normalizes adjoining hashtags before dispatch
  it('createIdea normalizes adjacent hashtags so Android parser receives space-separated tags', async () => {
    let sentBody;
    const client = new GoPlanClient({
      workspaceId: 'ws-1',
      fetchFn: async (url, opts) => {
        sentBody = JSON.parse(opts.body);
        return {
          ok: true,
          json: async () => ({ id: '1', title: 'Test', status: 'PENDING', tags: ['tag1', 'tag2', 'tag3'] })
        };
      }
    });

    await client.createIdea('ws-1', 'Crear login #auth#security#v1');
    assert.strictEqual(sentBody.text, 'Crear login #auth #security #v1');
  });

  // Test 7: client.savePlan preserves caller files array without mutating elements
  it('savePlan does not mutate caller files array', async () => {
    const originalFiles = [{ fileName: 'planes/my-plan', content: '# Plan' }];
    const client = new GoPlanClient({
      workspaceId: 'ws-1',
      fetchFn: async () => ({
        ok: true,
        json: async () => ({ success: true, createdFiles: ['my-plan.md'] })
      })
    });

    await client.savePlan('ws-1', {
      ideaId: 'idea-1',
      files: originalFiles,
      updateStatusToInProgress: false
    });

    // Original fileName should remain untouched
    assert.strictEqual(originalFiles[0].fileName, 'planes/my-plan');
  });

  // Test 8: MCP tools return isError: true when server returns success: false
  it('goplan_save_plan returns isError: true when plan ingestion returns success: false', async () => {
    const mockClient = {
      resolveActiveWorkspace: async () => 'ws-1',
      savePlan: async () => ({
        success: false,
        message: 'Plan rejected by server: invalid markdown schema'
      })
    };

    const handlers = createToolHandlers(mockClient);
    const res = await handlers.goplan_save_plan({ ideaId: 'idea-1', fileName: 'test.md', content: 'test' });
    assert.strictEqual(res.isError, true);
    assert(res.content[0].text.includes('Error al Sincronizar el Plan'));
    assert(res.content[0].text.includes('Plan rejected by server'));
  });

  // Test 9: goplan_save_context returns isError: true when saveContext returns success: false
  it('goplan_save_context returns isError: true when saveContext returns success: false', async () => {
    const mockClient = {
      resolveActiveWorkspace: async () => 'ws-1',
      saveContext: async () => ({
        success: false,
        message: 'Disk full'
      })
    };

    const handlers = createToolHandlers(mockClient);
    const res = await handlers.goplan_save_context({ content: '# Context' });
    assert.strictEqual(res.isError, true);
    assert(res.content[0].text.includes('Disk full'));
  });

  // Test 10: goplan_get_context returns informative message on empty CONTEXT.md
  it('goplan_get_context returns informative placeholder when CONTEXT.md is empty', async () => {
    const mockClient = {
      getContext: async () => ({ workspaceId: 'ws-1', content: '   ' })
    };

    const handlers = createToolHandlers(mockClient);
    const res = await handlers.goplan_get_context({});
    assert(!res.isError);
    assert(res.content[0].text.includes('El archivo CONTEXT.md está vacío'));
  });

  // Test 11: McpServer strips UTF-8 BOM (\uFEFF) on incoming JSON-RPC lines
  it('McpServer strips UTF-8 BOM without emitting JSON parse error', async () => {
    const input = new PassThrough();
    const output = new PassThrough();
    const server = new McpServer({ input, output });
    server.start();

    const responses = [];
    output.on('data', (chunk) => {
      for (const line of chunk.toString().split('\n')) {
        const trimmed = line.trim();
        if (trimmed) {
          try { responses.push(JSON.parse(trimmed)); } catch {}
        }
      }
    });

    // Send line with leading UTF-8 BOM \uFEFF
    input.write('\uFEFF' + JSON.stringify({ jsonrpc: '2.0', id: 99, method: 'ping' }) + '\n');

    await new Promise(r => setTimeout(r, 60));
    server.stop();

    const pingRes = responses.find(r => r.id === 99);
    assert(pingRes, 'Should respond to ping request even with UTF-8 BOM');
    assert.deepStrictEqual(pingRes.result, {});
  });

  // Test 12: McpServer gracefully handles $/ and notifications/ without crashing
  it('McpServer gracefully handles extension notifications', async () => {
    const server = new McpServer();
    const resLsp = await server.handleMethod('$/progress', { token: 't1' });
    assert.strictEqual(resLsp, null);

    const resNotification = await server.handleMethod('notifications/custom', {});
    assert.strictEqual(resNotification, null);
  });

  // Test 13: CLI argument parsing with space-separated flags (--ws Alpha, --tag frontend)
  it('CLI handles space-separated flags cleanly', async () => {
    // Test --ws Alpha with spaces
    const ctxRes = await runCli(['context', '--ws', 'my-ws-space', '--url', 'http://127.0.0.1:54322']);
    assert.strictEqual(ctxRes.code, 1);
    assert(ctxRes.stderr.includes('127.0.0.1:54322'));

    // Test save-plan with special characters in ideaId sanitizing default filename
    const savePlanRes = await runCli(['save-plan', '--idea', 'IDEA:456', '--path', './missing_file.md']);
    assert.strictEqual(savePlanRes.code, 1);
    assert(savePlanRes.stderr.includes('No se encontró el archivo'));
  });
});
