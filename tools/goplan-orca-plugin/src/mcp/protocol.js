/**
 * JSON-RPC 2.0 and MCP Protocol Constants & Utilities
 */

export const PROTOCOL_VERSION = '2024-11-05';
export const SERVER_NAME = 'goplan-orca-plugin';
export const SERVER_VERSION = '1.0.0';

export const JSONRPC_ERRORS = {
  PARSE_ERROR: -32700,
  INVALID_REQUEST: -32600,
  METHOD_NOT_FOUND: -32601,
  INVALID_PARAMS: -32602,
  INTERNAL_ERROR: -32603
};

/**
 * Creates a valid JSON-RPC 2.0 success response.
 * @param {string|number} id
 * @param {any} result
 * @returns {object}
 */
export function createSuccessResponse(id, result) {
  return {
    jsonrpc: '2.0',
    id,
    result
  };
}

/**
 * Creates a valid JSON-RPC 2.0 error response.
 * @param {string|number|null} id
 * @param {number} code
 * @param {string} message
 * @param {any} [data]
 * @returns {object}
 */
export function createErrorResponse(id, code, message, data) {
  return {
    jsonrpc: '2.0',
    id: id ?? null,
    error: {
      code,
      message,
      ...(data !== undefined ? { data } : {})
    }
  };
}

/**
 * Validates and parses an incoming JSON-RPC raw string.
 * @param {string} line
 * @returns {{ isRequest: boolean, isNotification: boolean, data: any } | { error: object }}
 */
export function parseJsonRpcMessage(line) {
  let parsed;
  try {
    parsed = JSON.parse(line);
  } catch (err) {
    return {
      error: createErrorResponse(null, JSONRPC_ERRORS.PARSE_ERROR, `JSON Parse error: ${err.message}`)
    };
  }

  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed) || parsed.jsonrpc !== '2.0' || typeof parsed.method !== 'string') {
    return {
      error: createErrorResponse(parsed?.id ?? null, JSONRPC_ERRORS.INVALID_REQUEST, 'Invalid JSON-RPC request')
    };
  }

  const isNotification = parsed.id === undefined || parsed.id === null;
  return {
    isRequest: !isNotification,
    isNotification,
    data: parsed
  };
}
