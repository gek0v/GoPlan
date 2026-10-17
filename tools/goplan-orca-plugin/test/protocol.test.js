import { describe, it } from 'node:test';
import assert from 'node:assert';
import {
  parseJsonRpcMessage,
  createSuccessResponse,
  createErrorResponse,
  JSONRPC_ERRORS
} from '../src/mcp/protocol.js';

describe('JSON-RPC & MCP Protocol Parser', () => {
  it('parses valid JSON-RPC request', () => {
    const raw = JSON.stringify({
      jsonrpc: '2.0',
      id: 1,
      method: 'tools/call',
      params: { name: 'goplan_status', arguments: {} }
    });
    const parsed = parseJsonRpcMessage(raw);
    assert.strictEqual(parsed.isRequest, true);
    assert.strictEqual(parsed.isNotification, false);
    assert.strictEqual(parsed.data.method, 'tools/call');
    assert.strictEqual(parsed.data.id, 1);
  });

  it('parses valid JSON-RPC notification (no id)', () => {
    const raw = JSON.stringify({
      jsonrpc: '2.0',
      method: 'notifications/initialized'
    });
    const parsed = parseJsonRpcMessage(raw);
    assert.strictEqual(parsed.isRequest, false);
    assert.strictEqual(parsed.isNotification, true);
    assert.strictEqual(parsed.data.method, 'notifications/initialized');
  });

  it('returns parse error on malformed JSON', () => {
    const raw = '{ invalid json line';
    const parsed = parseJsonRpcMessage(raw);
    assert('error' in parsed);
    assert.strictEqual(parsed.error.error.code, JSONRPC_ERRORS.PARSE_ERROR);
  });

  it('returns invalid request on missing jsonrpc version or method', () => {
    const raw1 = JSON.stringify({ id: 1, method: 'foo' }); // missing jsonrpc: '2.0'
    const parsed1 = parseJsonRpcMessage(raw1);
    assert('error' in parsed1);
    assert.strictEqual(parsed1.error.error.code, JSONRPC_ERRORS.INVALID_REQUEST);

    const raw2 = JSON.stringify({ jsonrpc: '2.0', id: 2 }); // missing method
    const parsed2 = parseJsonRpcMessage(raw2);
    assert('error' in parsed2);
    assert.strictEqual(parsed2.error.error.code, JSONRPC_ERRORS.INVALID_REQUEST);
  });

  it('constructs well-formed JSON-RPC success response', () => {
    const res = createSuccessResponse(42, { success: true });
    assert.strictEqual(res.jsonrpc, '2.0');
    assert.strictEqual(res.id, 42);
    assert.deepStrictEqual(res.result, { success: true });
  });

  it('constructs well-formed JSON-RPC error response', () => {
    const res = createErrorResponse(100, JSONRPC_ERRORS.METHOD_NOT_FOUND, 'Method not found', { detail: 'xyz' });
    assert.strictEqual(res.jsonrpc, '2.0');
    assert.strictEqual(res.id, 100);
    assert.strictEqual(res.error.code, JSONRPC_ERRORS.METHOD_NOT_FOUND);
    assert.strictEqual(res.error.message, 'Method not found');
    assert.deepStrictEqual(res.error.data, { detail: 'xyz' });
  });
});
