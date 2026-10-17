import { describe, it } from 'node:test';
import assert from 'node:assert';
import { PassThrough } from 'node:stream';
import { McpServer } from '../src/mcp/server.js';
import { PROTOCOL_VERSION, SERVER_NAME, JSONRPC_ERRORS } from '../src/mcp/protocol.js';

describe('MCP Server End-to-End Stream Communication', () => {
  function setupTestServer(mockClientOverrides = {}) {
    const input = new PassThrough();
    const output = new PassThrough();

    const mockClient = {
      serverUrl: 'http://127.0.0.1:8080',
      getStatus: async () => ({
        status: 'online',
        app: 'GoPlan',
        version: '1.0.0',
        activeWorkspace: 'default',
        ip: '127.0.0.1',
        port: 8080
      }),
      resolveActiveWorkspace: async (id) => id || 'default',
      createIdea: async (wsId, text) => ({
        id: '123',
        title: text.replace(/#\w+/g, '').trim(),
        status: 'PENDING',
        tags: ['test'],
        plans: []
      }),
      ...mockClientOverrides
    };

    const server = new McpServer({
      client: mockClient,
      input,
      output
    });

    server.start();

    const readResponses = async () => {
      const responses = [];
      let buffer = '';

      for await (const chunk of output) {
        buffer += chunk.toString();
        const lines = buffer.split('\n');
        buffer = lines.pop(); // keep partial line in buffer

        for (const line of lines) {
          const trimmed = line.trim();
          if (trimmed) {
            responses.push(JSON.parse(trimmed));
          }
        }
        if (responses.length > 0) break;
      }
      return responses;
    };

    const send = async (msg) => {
      input.write(JSON.stringify(msg) + '\n');
    };

    return { server, input, output, send, readResponses };
  }

  it('handles MCP initialize handshake', async () => {
    const { server, send, readResponses } = setupTestServer();

    await send({
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: {
        protocolVersion: PROTOCOL_VERSION,
        capabilities: {},
        clientInfo: { name: 'test-client', version: '1.0' }
      }
    });

    const [res] = await readResponses();
    assert.strictEqual(res.jsonrpc, '2.0');
    assert.strictEqual(res.id, 1);
    assert.strictEqual(res.result.protocolVersion, PROTOCOL_VERSION);
    assert.strictEqual(res.result.serverInfo.name, SERVER_NAME);
    assert(res.result.capabilities.tools);

    server.stop();
  });

  it('handles notifications/initialized without response', async () => {
    const { server, send, output } = setupTestServer();

    let outputReceived = false;
    output.on('data', () => { outputReceived = true; });

    await send({
      jsonrpc: '2.0',
      method: 'notifications/initialized',
      params: {}
    });

    // Wait a moment to ensure no response was emitted
    await new Promise(r => setTimeout(r, 50));
    assert.strictEqual(outputReceived, false);

    server.stop();
  });

  it('handles ping method', async () => {
    const { server, send, readResponses } = setupTestServer();

    await send({
      jsonrpc: '2.0',
      id: 2,
      method: 'ping'
    });

    const [res] = await readResponses();
    assert.strictEqual(res.id, 2);
    assert.deepStrictEqual(res.result, {});

    server.stop();
  });

  it('lists all 8 available tools via tools/list', async () => {
    const { server, send, readResponses } = setupTestServer();

    await send({
      jsonrpc: '2.0',
      id: 3,
      method: 'tools/list'
    });

    const [res] = await readResponses();
    assert.strictEqual(res.id, 3);
    assert.strictEqual(res.result.tools.length, 8);
    const names = res.result.tools.map(t => t.name);
    assert(names.includes('goplan_status'));
    assert(names.includes('goplan_get_context'));
    assert(names.includes('goplan_save_context'));
    assert(names.includes('goplan_get_todo'));
    assert(names.includes('goplan_send_idea'));
    assert(names.includes('goplan_get_plan'));
    assert(names.includes('goplan_save_plan'));
    assert(names.includes('goplan_export'));

    server.stop();
  });

  it('calls a tool via tools/call and returns CallToolResult', async () => {
    const { server, send, readResponses } = setupTestServer();

    await send({
      jsonrpc: '2.0',
      id: 4,
      method: 'tools/call',
      params: {
        name: 'goplan_status',
        arguments: {}
      }
    });

    const [res] = await readResponses();
    assert.strictEqual(res.id, 4);
    assert(res.result.content);
    assert(Array.isArray(res.result.content));
    assert(res.result.content[0].text.includes('GoPlan Server Status'));

    server.stop();
  });

  it('returns method not found for unknown method', async () => {
    const { server, send, readResponses } = setupTestServer();

    await send({
      jsonrpc: '2.0',
      id: 5,
      method: 'unknown/method'
    });

    const [res] = await readResponses();
    assert.strictEqual(res.id, 5);
    assert.strictEqual(res.error.code, JSONRPC_ERRORS.METHOD_NOT_FOUND);

    server.stop();
  });

  it('returns error when tool does not exist', async () => {
    const { server, send, readResponses } = setupTestServer();

    await send({
      jsonrpc: '2.0',
      id: 6,
      method: 'tools/call',
      params: {
        name: 'non_existent_tool',
        arguments: {}
      }
    });

    const [res] = await readResponses();
    assert.strictEqual(res.id, 6);
    assert.strictEqual(res.error.code, JSONRPC_ERRORS.METHOD_NOT_FOUND);

    server.stop();
  });

  it('returns parse error on invalid JSON input', async () => {
    const { server, send, input, readResponses } = setupTestServer();

    input.write('{ not valid json\n');

    const [res] = await readResponses();
    assert.strictEqual(res.id, null);
    assert.strictEqual(res.error.code, JSONRPC_ERRORS.PARSE_ERROR);

    server.stop();
  });

  it('handles standard MCP discovery method prompts/list', async () => {
    const { server, send, readResponses } = setupTestServer();

    await send({ jsonrpc: '2.0', id: 10, method: 'prompts/list' });
    const [promptsRes] = await readResponses();
    assert.strictEqual(promptsRes.id, 10);
    assert.deepStrictEqual(promptsRes.result, { prompts: [] });

    server.stop();
  });

  it('handles standard MCP discovery method resources/list', async () => {
    const { server, send, readResponses } = setupTestServer();

    await send({ jsonrpc: '2.0', id: 11, method: 'resources/list' });
    const [resourcesRes] = await readResponses();
    assert.strictEqual(resourcesRes.id, 11);
    assert.deepStrictEqual(resourcesRes.result, { resources: [] });

    server.stop();
  });

  it('handles initialized and notifications/cancelled without crashing', async () => {
    const { server, send } = setupTestServer();

    await send({ jsonrpc: '2.0', method: 'initialized', params: {} });
    await send({ jsonrpc: '2.0', method: 'notifications/cancelled', params: { requestId: 99 } });

    await new Promise(r => setTimeout(r, 40));
    assert.strictEqual(server.initialized, true);

    server.stop();
  });
});
