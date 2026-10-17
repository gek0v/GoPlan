import { describe, it } from 'node:test';
import assert from 'node:assert';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

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

describe('CLI Commands Execution', () => {
  it('displays help when invoked with help or no args', async () => {
    const res = await runCli(['help']);
    assert.strictEqual(res.code, 0);
    assert(res.stdout.includes('GoPlan CLI'));
    assert(res.stdout.includes('status'));
    assert(res.stdout.includes('idea'));
    assert(res.stdout.includes('todo'));
    assert(res.stdout.includes('context'));
    assert(res.stdout.includes('save-plan'));
  });

  it('prints config settings with config command', async () => {
    const res = await runCli(['config'], { GOPLAN_SERVER_URL: 'http://my-phone:8080' });
    assert.strictEqual(res.code, 0);
    assert(res.stdout.includes('http://my-phone:8080'));
  });

  it('fails gracefully with diagnostic on offline server status', async () => {
    const res = await runCli(['status'], { GOPLAN_SERVER_URL: 'http://127.0.0.1:54321', GOPLAN_TIMEOUT_MS: '500' });
    assert.strictEqual(res.code, 1);
    assert(res.stderr.includes('No se pudo conectar al servidor GoPlan') || res.stderr.includes('ECONNREFUSED'));
  });

  it('validates missing arguments on idea command', async () => {
    const res = await runCli(['idea']);
    assert.strictEqual(res.code, 1);
    assert(res.stderr.includes('Debe proporcionar el texto de la idea'));
  });

  it('validates missing arguments on plan command', async () => {
    const res = await runCli(['plan']);
    assert.strictEqual(res.code, 1);
    assert(res.stderr.includes('Debe proporcionar el nombre del archivo'));
  });

  it('validates missing arguments on save-context command', async () => {
    const res = await runCli(['save-context']);
    assert.strictEqual(res.code, 1);
    assert(res.stderr.includes('Debe proporcionar el contenido'));
  });

  it('validates missing arguments on save-plan command', async () => {
    const res = await runCli(['save-plan']);
    assert.strictEqual(res.code, 1);
    assert(res.stderr.includes('Debe especificar el ID de la idea con --idea=<ID>'));
  });

  it('validates idea command when only --ws flag is passed without idea text', async () => {
    const res = await runCli(['idea', '--ws=test-ws']);
    assert.strictEqual(res.code, 1);
    assert(res.stderr.includes('Debe proporcionar el texto de la idea'));
  });
});
