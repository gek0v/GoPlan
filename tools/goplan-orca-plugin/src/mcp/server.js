import readline from 'node:readline';
import { GoPlanClient } from '../client.js';
import {
  PROTOCOL_VERSION,
  SERVER_NAME,
  SERVER_VERSION,
  JSONRPC_ERRORS,
  createSuccessResponse,
  createErrorResponse,
  parseJsonRpcMessage
} from './protocol.js';
import { TOOL_DEFINITIONS, createToolHandlers } from './tools.js';

/**
 * Model Context Protocol (MCP) Server for GoPlan
 */
export class McpServer {
  /**
   * @param {object} [options]
   * @param {GoPlanClient} [options.client]
   * @param {NodeJS.ReadableStream} [options.input]
   * @param {NodeJS.WritableStream} [options.output]
   */
  constructor(options = {}) {
    this.client = options.client || new GoPlanClient();
    this.input = options.input || process.stdin;
    this.output = options.output || process.stdout;
    if (this.output && typeof this.output.on === 'function') {
      this.output.on('error', (err) => {
        const isBenign = err.code === 'EPIPE' || err.code === 'ERR_STREAM_PREMATURE_CLOSE' ||
                         err.name === 'AbortError' || err.message?.includes('aborted');
        if (!isBenign) {
          console.error(`[GoPlan MCP] Output stream error: ${err.message}`);
        }
      });
    }
    this.toolHandlers = createToolHandlers(this.client);
    this.rl = null;
    this.initialized = false;
  }

  /**
   * Start listening for JSON-RPC messages on the input stream.
   */
  start() {
    this.rl = readline.createInterface({
      input: this.input,
      output: null,
      terminal: false
    });

    this.rl.on('line', async (line) => {
      const cleanLine = line.replace(/^\uFEFF/, '').trim();
      if (!cleanLine) return;

      try {
        await this.handleLine(cleanLine);
      } catch (err) {
        console.error(`[GoPlan MCP] Unexpected error handling message: ${err.message}`);
      }
    });

    this.rl.on('close', () => {
      // Stream closed
    });
  }

  /**
   * Stop the server and close stream readers.
   */
  stop() {
    if (this.rl) {
      this.rl.close();
      this.rl = null;
    }
  }

  /**
   * Send a JSON-RPC message to the output stream.
   * @param {object} message
   */
  sendMessage(message) {
    if (!this.output || typeof this.output.write !== 'function') return;
    try {
      this.output.write(JSON.stringify(message) + '\n');
    } catch (err) {
      console.error(`[GoPlan MCP] Failed to write message to stream: ${err.message}`);
    }
  }

  /**
   * Process a single JSON-RPC line.
   * @param {string} line
   */
  async handleLine(line) {
    const clean = line.replace(/^\uFEFF/, '').trim();
    if (!clean) return;

    // Gracefully ignore HTTP/LSP framing headers if sent by client
    if (!clean.startsWith('{') && !clean.startsWith('[')) {
      if (/^[a-zA-Z0-9_-]+:\s*/i.test(clean)) {
        return;
      }
    }

    const parsed = parseJsonRpcMessage(clean);
    if ('error' in parsed) {
      this.sendMessage(parsed.error);
      return;
    }

    const { isNotification, data } = parsed;
    const { id, method, params } = data;

    try {
      const result = await this.handleMethod(method, params);
      if (!isNotification) {
        this.sendMessage(createSuccessResponse(id, result));
      }
    } catch (err) {
      if (!isNotification) {
        const code = err.jsonRpcCode || JSONRPC_ERRORS.INTERNAL_ERROR;
        this.sendMessage(createErrorResponse(id, code, err.message, err.data));
      }
    }
  }

  /**
   * Route and execute MCP protocol methods.
   * @param {string} method
   * @param {any} rawParams
   * @returns {Promise<any>}
   */
  async handleMethod(method, rawParams) {
    const params = (rawParams && typeof rawParams === 'object' && !Array.isArray(rawParams)) ? rawParams : {};
    switch (method) {
      case 'initialize': {
        this.initialized = true;
        return {
          protocolVersion: PROTOCOL_VERSION,
          capabilities: {
            tools: { listChanged: false }
          },
          serverInfo: {
            name: SERVER_NAME,
            version: SERVER_VERSION
          }
        };
      }

      case 'initialized':
      case 'notifications/initialized': {
        this.initialized = true;
        return null;
      }

      case 'notifications/cancelled': {
        return null;
      }

      case 'ping': {
        return {};
      }

      case 'prompts/list': {
        return { prompts: [] };
      }

      case 'resources/list': {
        return { resources: [] };
      }

      case 'tools/list': {
        return {
          tools: TOOL_DEFINITIONS
        };
      }

      case 'tools/call': {
        const { name, arguments: rawArgs } = params;
        const args = (rawArgs && typeof rawArgs === 'object' && !Array.isArray(rawArgs)) ? rawArgs : {};
        if (!name) {
          const err = new Error('Missing tool name in tools/call request');
          err.jsonRpcCode = JSONRPC_ERRORS.INVALID_PARAMS;
          throw err;
        }

        const handler = this.toolHandlers[name];
        if (!handler) {
          const err = new Error(`Tool not found: "${name}"`);
          err.jsonRpcCode = JSONRPC_ERRORS.METHOD_NOT_FOUND;
          throw err;
        }

        try {
          const toolResult = await handler(args);
          return toolResult;
        } catch (toolErr) {
          return {
            isError: true,
            content: [{ type: 'text', text: toolErr.diagnostic || `Error executing ${name}: ${toolErr.message}` }]
          };
        }
      }

      default: {
        if (method.startsWith('notifications/') || method.startsWith('$/')) {
          return null;
        }
        const err = new Error(`Method not supported: "${method}"`);
        err.jsonRpcCode = JSONRPC_ERRORS.METHOD_NOT_FOUND;
        throw err;
      }
    }
  }
}
