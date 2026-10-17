#!/usr/bin/env node

/**
 * GoPlan MCP Server Stdio Entrypoint
 */

import { McpServer } from '../src/mcp/server.js';
import { GoPlanClient } from '../src/client.js';

// Redirect any accidental console outputs from libraries to stderr so stdout remains pure JSON-RPC
console.log = (...args) => console.error(...args);
console.warn = (...args) => console.error(...args);
console.info = (...args) => console.error(...args);
console.debug = (...args) => console.error(...args);

// Prevent crashing if client disconnects abruptly (EPIPE)
process.stdout.on('error', (err) => {
  if (err.code === 'EPIPE') process.exit(0);
});

const client = new GoPlanClient();
const server = new McpServer({
  client,
  input: process.stdin,
  output: process.stdout
});

server.start();

process.on('SIGINT', () => {
  server.stop();
  process.exit(0);
});

process.on('SIGTERM', () => {
  server.stop();
  process.exit(0);
});
