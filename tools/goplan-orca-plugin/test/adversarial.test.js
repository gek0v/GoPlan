import { describe, it } from 'node:test';
import assert from 'node:assert';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { PassThrough } from 'node:stream';
import { loadConfig, saveConfig, DEFAULT_SERVER_URL } from '../src/config.js';
import { GoPlanClient } from '../src/client.js';
import { McpServer } from '../src/mcp/server.js';
import { createToolHandlers } from '../src/mcp/tools.js';

describe('Adversarial & Stress Tests', () => {
  // Test 1: Config layering between global ~/.goplanrc and local .goplanrc
  it('correctly layers global and local config files without discarding global serverUrl', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'goplan-cfg-test-'));
    const globalFile = path.join(tmpDir, 'global.goplanrc');
    const localFile = path.join(tmpDir, 'local.goplanrc');

    try {
      // Global sets serverUrl and global timeout
      fs.writeFileSync(globalFile, 'GOPLAN_SERVER_URL=http://192.168.1.200:8080\nGOPLAN_TIMEOUT_MS=12000\n');
      // Local sets only workspaceId
      fs.writeFileSync(localFile, 'GOPLAN_WORKSPACE_ID=adversarial-ws\n');

      const config = loadConfig({
        configPath: localFile,
        globalConfigPath: globalFile
      });

      assert.strictEqual(config.serverUrl, 'http://192.168.1.200:8080', 'Should inherit serverUrl from global config');
      assert.strictEqual(config.workspaceId, 'adversarial-ws', 'Should get workspaceId from local config');
      assert.strictEqual(config.timeoutMs, 12000, 'Should inherit timeoutMs from global config');
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  // Test 2: goplan_export to non-existent nested directory
  it('creates parent directory if outputPath has non-existent directories', async () => {
    const mockClient = {
      resolveActiveWorkspace: async () => 'ws-exp',
      exportWorkspace: async () => ({ type: 'zip', content: new Uint8Array([1, 2, 3, 4]) })
    };

    const handlers = createToolHandlers(mockClient);
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'goplan-exp-test-'));
    const nestedOut = path.join(tmpDir, 'deeply', 'nested', 'folder', 'export.zip');

    try {
      const res = await handlers.goplan_export({ type: 'zip', outputPath: nestedOut });
      assert.strictEqual(res.isError, undefined, `Should not error: ${res.content?.[0]?.text}`);
      assert(fs.existsSync(nestedOut), 'Export file should exist in created directory');
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  // Test 3: McpServer handling Content-Length headers without crashing or sending ParseError
  it('handles Content-Length headers gracefully without sending ParseError', async () => {
    const input = new PassThrough();
    const output = new PassThrough();
    const server = new McpServer({ input, output });
    server.start();

    const responses = [];
    output.on('data', (chunk) => {
      for (const line of chunk.toString().split('\n')) {
        const trimmed = line.trim();
        if (trimmed) responses.push(JSON.parse(trimmed));
      }
    });

    // Send LSP-style Content-Length header before JSON
    input.write('Content-Length: 43\r\n\r\n{"jsonrpc":"2.0","method":"ping","id":100}\n');

    await new Promise(r => setTimeout(r, 100));
    server.stop();

    // Responses should NOT contain a Parse Error for the Content-Length header!
    const parseErrors = responses.filter(r => r.error && r.error.code === -32700);
    assert.strictEqual(parseErrors.length, 0, 'Should not emit parse error for Content-Length header');
    const pingRes = responses.find(r => r.id === 100);
    assert(pingRes, 'Should respond to ping request');
  });

  // Test 4: McpServer rapid concurrent requests (50 requests in flight)
  it('handles 50 rapid concurrent JSON-RPC requests correctly', async () => {
    const input = new PassThrough();
    const output = new PassThrough();

    let callCount = 0;
    const mockClient = {
      getStatus: async () => {
        callCount++;
        return { status: 'online', app: 'GoPlan', version: '1.0.0', ip: '127.0.0.1', port: 8080 };
      }
    };

    const server = new McpServer({ client: mockClient, input, output });
    server.start();

    const responses = [];
    output.on('data', (chunk) => {
      for (const line of chunk.toString().split('\n')) {
        const trimmed = line.trim();
        if (trimmed) {
          try {
            responses.push(JSON.parse(trimmed));
          } catch {}
        }
      }
    });

    // Fire 50 requests concurrently
    const totalRequests = 50;
    for (let i = 1; i <= totalRequests; i++) {
      input.write(JSON.stringify({
        jsonrpc: '2.0',
        id: i,
        method: 'tools/call',
        params: { name: 'goplan_status', arguments: {} }
      }) + '\n');
    }

    // Wait for all responses
    const start = Date.now();
    while (responses.length < totalRequests && (Date.now() - start < 3000)) {
      await new Promise(r => setTimeout(r, 20));
    }

    server.stop();

    assert.strictEqual(responses.length, totalRequests, `Expected ${totalRequests} responses, got ${responses.length}`);
    const receivedIds = new Set(responses.map(r => r.id));
    for (let i = 1; i <= totalRequests; i++) {
      assert(receivedIds.has(i), `Missing response for request id ${i}`);
    }
  });

  // Test 5: savePlan path traversal defense on files array
  it('rejects path traversal in files array in savePlan', async () => {
    const client = new GoPlanClient({
      workspaceId: 'test-ws',
      fetchFn: async () => ({ ok: true, json: async () => ({ success: true }) })
    });

    await assert.rejects(
      async () => client.savePlan('test-ws', {
        ideaId: 'idea-1',
        files: [{ fileName: '../../etc/passwd', content: 'test' }]
      }),
      (err) => err.message.includes('path traversal not allowed')
    );
  });

  // Test 6: goplan_status preserves mock fetchFn when serverUrl is specified
  it('goplan_status preserves mock fetchFn when serverUrl is specified in arguments', async () => {
    let customFetchCalled = false;
    const mockClient = new GoPlanClient({
      serverUrl: 'http://default-host:8080',
      fetchFn: async (url) => {
        customFetchCalled = true;
        assert(url.startsWith('http://custom-host:8080'));
        return {
          ok: true,
          status: 200,
          json: async () => ({ status: 'online', app: 'GoPlan', version: '1.0.0', ip: 'custom-host', port: 8080 })
        };
      }
    });

    const handlers = createToolHandlers(mockClient);
    const res = await handlers.goplan_status({ serverUrl: 'http://custom-host:8080' });
    assert(!res.isError, `Expected no error, got: ${res.content?.[0]?.text}`);
    assert.strictEqual(customFetchCalled, true, 'Custom fetchFn should have been called');
  });

  // Test 7: exportWorkspace forwards timeoutMs to _request
  it('exportWorkspace forwards timeoutMs to _request', async () => {
    let receivedTimeout = null;
    const client = new GoPlanClient({
      workspaceId: 'ws-time',
      fetchFn: async (url, opts) => {
        return { ok: true, text: async () => '# Done' };
      }
    });

    // Monkey-patch _request to inspect options
    const originalRequest = client._request.bind(client);
    client._request = async (endpoint, options) => {
      receivedTimeout = options?.timeoutMs;
      return originalRequest(endpoint, options);
    };

    await client.exportWorkspace('ws-time', { type: 'md', timeoutMs: 45000 });
    assert.strictEqual(receivedTimeout, 45000, 'timeoutMs should be passed to _request');
  });
});
