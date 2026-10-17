import { describe, it } from 'node:test';
import assert from 'node:assert';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {
  parseConfigContent,
  loadConfig,
  saveConfig,
  DEFAULT_SERVER_URL,
  DEFAULT_TIMEOUT_MS
} from '../src/config.js';

describe('Config Module', () => {
  it('parses empty or whitespace config content', () => {
    assert.deepStrictEqual(parseConfigContent(''), {});
    assert.deepStrictEqual(parseConfigContent('   \n  \n'), {});
  });

  it('parses JSON format config', () => {
    const json = JSON.stringify({
      serverUrl: 'http://192.168.1.100:8080',
      workspaceId: 'mobile-ws',
      timeoutMs: 5000
    });
    const parsed = parseConfigContent(json);
    assert.strictEqual(parsed.serverUrl, 'http://192.168.1.100:8080');
    assert.strictEqual(parsed.workspaceId, 'mobile-ws');
    assert.strictEqual(parsed.timeoutMs, 5000);
  });

  it('parses KEY=VALUE and comments in .goplanrc format', () => {
    const content = `
      # GoPlan config
      GOPLAN_SERVER_URL=http://10.0.0.5:8080
      ; another comment
      GOPLAN_WORKSPACE_ID="work-project"
      GOPLAN_TIMEOUT_MS=6000
    `;
    const parsed = parseConfigContent(content);
    assert.strictEqual(parsed.serverUrl, 'http://10.0.0.5:8080');
    assert.strictEqual(parsed.workspaceId, 'work-project');
    assert.strictEqual(parsed.timeoutMs, 6000);
  });

  it('loads default values when no config is provided', () => {
    const originalUrl = process.env.GOPLAN_SERVER_URL;
    const originalWs = process.env.GOPLAN_WORKSPACE_ID;
    delete process.env.GOPLAN_SERVER_URL;
    delete process.env.GOPLAN_WORKSPACE_ID;

    try {
      const config = loadConfig({ configPath: 'non_existent_file_xyz', globalConfigPath: 'non_existent_global_xyz' });
      assert.strictEqual(config.serverUrl, DEFAULT_SERVER_URL);
      assert.strictEqual(config.workspaceId, null);
      assert.strictEqual(config.timeoutMs, DEFAULT_TIMEOUT_MS);
    } finally {
      if (originalUrl) process.env.GOPLAN_SERVER_URL = originalUrl;
      if (originalWs) process.env.GOPLAN_WORKSPACE_ID = originalWs;
    }
  });

  it('prefers environment variables over config file', () => {
    const tmpFile = path.join(os.tmpdir(), `test_goplanrc_${Date.now()}.json`);
    fs.writeFileSync(tmpFile, JSON.stringify({
      serverUrl: 'http://file-url:8080',
      workspaceId: 'file-ws'
    }));

    process.env.GOPLAN_SERVER_URL = 'http://env-url:8080';
    process.env.GOPLAN_WORKSPACE_ID = 'env-ws';

    try {
      const config = loadConfig({ configPath: tmpFile });
      assert.strictEqual(config.serverUrl, 'http://env-url:8080');
      assert.strictEqual(config.workspaceId, 'env-ws');
    } finally {
      delete process.env.GOPLAN_SERVER_URL;
      delete process.env.GOPLAN_WORKSPACE_ID;
      if (fs.existsSync(tmpFile)) fs.unlinkSync(tmpFile);
    }
  });

  it('prefers explicit runtime overrides over environment and files', () => {
    process.env.GOPLAN_SERVER_URL = 'http://env-url:8080';
    try {
      const config = loadConfig({ serverUrl: 'http://override-url:8080/' });
      // Should strip trailing slash
      assert.strictEqual(config.serverUrl, 'http://override-url:8080');
    } finally {
      delete process.env.GOPLAN_SERVER_URL;
    }
  });

  it('saves and re-reads config file correctly', () => {
    const tmpFile = path.join(os.tmpdir(), `test_save_goplanrc_${Date.now()}.rc`);
    try {
      saveConfig({
        serverUrl: 'http://saved-url:8080',
        workspaceId: 'saved-ws',
        timeoutMs: 4500
      }, tmpFile);

      const loaded = loadConfig({ configPath: tmpFile });
      assert.strictEqual(loaded.serverUrl, 'http://saved-url:8080');
      assert.strictEqual(loaded.workspaceId, 'saved-ws');
      assert.strictEqual(loaded.timeoutMs, 4500);
    } finally {
      if (fs.existsSync(tmpFile)) fs.unlinkSync(tmpFile);
    }
  });
});
