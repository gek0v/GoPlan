import { describe, it } from 'node:test';
import assert from 'node:assert';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { PassThrough } from 'node:stream';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { GoPlanClient } from '../src/client.js';
import { McpServer } from '../src/mcp/server.js';
import { createToolHandlers } from '../src/mcp/tools.js';
import { parseJsonRpcMessage, JSONRPC_ERRORS } from '../src/mcp/protocol.js';

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

describe('Reviewer 2 Comprehensive Boundary & Adversarial Tests', () => {
  // Test 1: McpServer handling null params without throwing TypeError
  it('handles tools/call with null params without throwing TypeError', async () => {
    const server = new McpServer();
    await assert.rejects(
      async () => server.handleMethod('tools/call', null),
      (err) => {
        assert.strictEqual(err.jsonRpcCode, JSONRPC_ERRORS.INVALID_PARAMS);
        assert(err.message.includes('Missing tool name'));
        return true;
      }
    );
  });

  // Test 2: All 8 tool handlers survive arguments === null
  it('all 8 tool handlers handle arguments: null safely without TypeError', async () => {
    const mockClient = {
      serverUrl: 'http://127.0.0.1:8080',
      getStatus: async () => ({ status: 'online', app: 'GoPlan', version: '1.0.0', ip: '127.0.0.1', port: 8080 }),
      getContext: async () => ({ workspaceId: 'ws-1', content: '# Context' }),
      resolveActiveWorkspace: async () => 'ws-1',
      getTodo: async () => ({ ideas: [] }),
      exportWorkspace: async () => ({ type: 'md', content: '# Export' })
    };

    const handlers = createToolHandlers(mockClient);

    // Tools that don't require parameters should succeed
    const statusRes = await handlers.goplan_status(null);
    assert(!statusRes.isError, 'goplan_status should succeed with null args');

    const getContextRes = await handlers.goplan_get_context(null);
    assert(!getContextRes.isError, 'goplan_get_context should succeed with null args');

    const getTodoRes = await handlers.goplan_get_todo(null);
    assert(!getTodoRes.isError, 'goplan_get_todo should succeed with null args');

    const exportRes = await handlers.goplan_export(null);
    assert(!exportRes.isError, 'goplan_export should succeed with null args');

    // Tools that require parameters should return isError: true with clean message (not null pointer TypeError)
    const saveCtxRes = await handlers.goplan_save_context(null);
    assert.strictEqual(saveCtxRes.isError, true);
    assert(saveCtxRes.content[0].text.includes('content') && saveCtxRes.content[0].text.includes('obligatorio'));

    const sendIdeaRes = await handlers.goplan_send_idea(null);
    assert.strictEqual(sendIdeaRes.isError, true);
    assert(sendIdeaRes.content[0].text.includes('text') && sendIdeaRes.content[0].text.includes('obligatorio'));

    const getPlanRes = await handlers.goplan_get_plan(null);
    assert.strictEqual(getPlanRes.isError, true);
    assert(getPlanRes.content[0].text.includes('fileName') && getPlanRes.content[0].text.includes('obligatorio'));

    const savePlanRes = await handlers.goplan_save_plan(null);
    assert.strictEqual(savePlanRes.isError, true);
    assert(savePlanRes.content[0].text.includes('ideaId') && savePlanRes.content[0].text.includes('obligatorio'));
  });

  // Test 3: McpServer stream ignores general HTTP headers (Host, User-Agent, etc.)
  it('McpServer ignores arbitrary HTTP header lines without emitting parse errors', async () => {
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

    input.write('Host: 127.0.0.1:8080\r\n');
    input.write('User-Agent: curl/7.68.0\r\n');
    input.write('Accept: application/json\r\n');
    input.write('\r\n');
    input.write(JSON.stringify({ jsonrpc: '2.0', id: 42, method: 'ping' }) + '\n');

    await new Promise(r => setTimeout(r, 60));
    server.stop();

    const parseErrors = responses.filter(r => r.error && r.error.code === JSONRPC_ERRORS.PARSE_ERROR);
    assert.strictEqual(parseErrors.length, 0, 'Should not emit parse error on HTTP headers');
    const pingRes = responses.find(r => r.id === 42);
    assert(pingRes, 'Should respond to ping request');
  });

  // Test 4: JSON-RPC rejects array / batch request
  it('parseJsonRpcMessage rejects array batch requests as INVALID_REQUEST', () => {
    const raw = JSON.stringify([{ jsonrpc: '2.0', id: 1, method: 'ping' }]);
    const parsed = parseJsonRpcMessage(raw);
    assert('error' in parsed);
    assert.strictEqual(parsed.error.error.code, JSONRPC_ERRORS.INVALID_REQUEST);
  });

  // Test 5: savePlan boundary - rejects whitespace ideaId
  it('savePlan rejects empty or whitespace ideaId', async () => {
    const client = new GoPlanClient({
      workspaceId: 'ws-1',
      fetchFn: async () => ({ ok: true, json: async () => ({ success: true }) })
    });

    await assert.rejects(
      async () => client.savePlan('ws-1', { ideaId: '   ', fileName: 'test.md', content: 'test' }),
      (err) => err.message.includes('Missing ideaId')
    );
  });

  // Test 6: savePlan boundary - rejects content > 10MB
  it('savePlan rejects plan content exceeding 10MB limit', async () => {
    const client = new GoPlanClient({
      workspaceId: 'ws-1',
      fetchFn: async () => ({ ok: true, json: async () => ({ success: true }) })
    });

    // 10MB + 1 byte
    const largeContent = 'A'.repeat(10 * 1024 * 1024 + 1);

    await assert.rejects(
      async () => client.savePlan('ws-1', {
        ideaId: 'idea-1',
        fileName: 'huge.md',
        content: largeContent
      }),
      (err) => err.message.includes('exceeds maximum allowed size of 10MB')
    );

    await assert.rejects(
      async () => client.savePlan('ws-1', {
        ideaId: 'idea-1',
        rawText: largeContent
      }),
      (err) => err.message.includes('exceeds maximum allowed size of 10MB')
    );
  });

  // Test 7: savePlan boundary - rejects invalid filename characters and empty base names
  it('savePlan rejects filenames with illegal filesystem characters or empty base names', async () => {
    const client = new GoPlanClient({
      workspaceId: 'ws-1',
      fetchFn: async () => ({ ok: true, json: async () => ({ success: true }) })
    });

    const invalidNames = [
      'plan:auth.md',
      'plan*test.md',
      'plan?name.md',
      'plan"quote.md',
      'plan<test.md',
      'plan>test.md',
      'plan|pipe.md',
      'plan\nnewline.md',
      '.md',
      'planes/.md'
    ];

    for (const name of invalidNames) {
      await assert.rejects(
        async () => client.savePlan('ws-1', {
          ideaId: 'idea-1',
          fileName: name,
          content: '# Test'
        }),
        (err) => err.message.includes('Invalid plan file name') || err.message.includes('path traversal not allowed'),
        `Should reject invalid filename: ${name}`
      );
    }
  });

  // Test 8: getPlan boundary - multi-decoded URL path traversal defense
  it('getPlan rejects double-encoded URL path traversal', async () => {
    const client = new GoPlanClient({
      workspaceId: 'ws-1',
      fetchFn: async () => ({ ok: true, text: async () => 'secret' })
    });

    // %252e%252e is double encoded ..
    await assert.rejects(
      async () => client.getPlan('ws-1', '%252e%252e/secret.md'),
      (err) => err.message.includes('path traversal not allowed')
    );
  });

  // Test 9: saveContext boundary - rejects content > 10MB and non-string
  it('saveContext rejects oversized content and non-string types', async () => {
    const client = new GoPlanClient({
      workspaceId: 'ws-1',
      fetchFn: async () => ({ ok: true, json: async () => ({ success: true }) })
    });

    await assert.rejects(
      async () => client.saveContext('ws-1', 12345),
      (err) => err.message.includes('must be a string')
    );

    const oversized = 'C'.repeat(10 * 1024 * 1024 + 1);
    await assert.rejects(
      async () => client.saveContext('ws-1', oversized),
      (err) => err.message.includes('exceeds maximum allowed size of 10MB')
    );
  });

  // Test 10: createIdea boundary - rejects non-string and oversized text
  it('createIdea rejects non-string and oversized text', async () => {
    const client = new GoPlanClient({
      workspaceId: 'ws-1',
      fetchFn: async () => ({ ok: true, json: async () => ({ id: '1' }) })
    });

    await assert.rejects(
      async () => client.createIdea('ws-1', null),
      (err) => err.message.includes('Idea text cannot be blank')
    );

    const oversized = 'T'.repeat(100 * 1024 + 1);
    await assert.rejects(
      async () => client.createIdea('ws-1', oversized),
      (err) => err.message.includes('exceeds maximum allowed size of 100KB')
    );
  });

  // Test 11: goplan_export to existing directory path appends filename automatically
  it('goplan_export handles directory in outputPath without EISDIR error', async () => {
    const mockClient = {
      resolveActiveWorkspace: async () => 'ws-dir-test',
      exportWorkspace: async () => ({ type: 'zip', content: new Uint8Array([80, 75, 3, 4]) })
    };

    const handlers = createToolHandlers(mockClient);
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'goplan-dir-test-'));

    try {
      // Pass existing directory path
      const res = await handlers.goplan_export({ type: 'zip', outputPath: tmpDir });
      assert(!res.isError, `Should not error: ${res.content?.[0]?.text}`);
      const expectedFile = path.join(tmpDir, 'ws-dir-test-export.zip');
      assert(fs.existsSync(expectedFile), 'File should be saved inside the directory');
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  // Test 12: CLI command edge cases
  it('CLI handles --ws flag across context, plan, and idea commands cleanly', async () => {
    // 1. context with --ws
    const ctxRes = await runCli(['context', '--ws=my-ws', '--url=http://127.0.0.1:54321']);
    // Fails with connection diagnostic but correctly targeted http://127.0.0.1:54321
    assert.strictEqual(ctxRes.code, 1);
    assert(ctxRes.stderr.includes('127.0.0.1:54321'));

    // 2. plan with --ws
    const planRes = await runCli(['plan', 'auth.md', '--ws=my-ws', '--url=http://127.0.0.1:54321']);
    assert.strictEqual(planRes.code, 1);
    assert(planRes.stderr.includes('127.0.0.1:54321'));

    // 3. save-plan with nonexistent --path
    const savePlanRes = await runCli(['save-plan', '--idea=IDEA-1', '--path=./nonexistent_plan_xyz.md']);
    assert.strictEqual(savePlanRes.code, 1);
    assert(savePlanRes.stderr.includes('No se encontró el archivo especificado en --path'));

    // 4. save-context with nonexistent file path
    const saveCtxRes = await runCli(['save-context', './nonexistent_context_xyz.md']);
    assert.strictEqual(saveCtxRes.code, 1);
    assert(saveCtxRes.stderr.includes('No se encontró el archivo especificado'));
  });
});
