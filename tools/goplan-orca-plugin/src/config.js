import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

export const DEFAULT_SERVER_URL = 'http://127.0.0.1:8080';
export const DEFAULT_TIMEOUT_MS = 8000;

/**
 * Resolves a .goplanrc or .goplanrc.json file within a specific directory.
 * @param {string} dir
 * @returns {string|null}
 */
export function resolveConfigFileInDir(dir) {
  if (!dir) return null;
  const rcPath = path.join(dir, '.goplanrc');
  if (fs.existsSync(rcPath) && fs.statSync(rcPath).isFile()) return rcPath;
  const jsonPath = path.join(dir, '.goplanrc.json');
  if (fs.existsSync(jsonPath) && fs.statSync(jsonPath).isFile()) return jsonPath;
  return null;
}

/**
 * Searches for a config file (.goplanrc or .goplanrc.json) in common search locations.
 * @param {string[]} extraPaths - Optional specific directories or file paths to inspect
 * @returns {string|null} Path to the resolved config file or null
 */
export function findConfigFile(extraPaths = []) {
  const candidates = [
    ...extraPaths,
    process.cwd(),
    path.join(process.cwd(), '.goplanrc'),
    path.join(process.cwd(), '.goplanrc.json'),
    path.join(os.homedir(), '.goplanrc'),
    path.join(os.homedir(), '.goplanrc.json')
  ];

  for (const candidate of candidates) {
    if (!candidate) continue;
    try {
      const stats = fs.statSync(candidate);
      if (stats.isFile()) {
        return candidate;
      }
      if (stats.isDirectory()) {
        const found = resolveConfigFileInDir(candidate);
        if (found) return found;
      }
    } catch {
      // Path not found or permission error; continue
    }
  }

  return null;
}

/**
 * Parses config file contents supporting JSON, .env, or INI format.
 * @param {string} content - Raw content of the file
 * @returns {Record<string, any>} Parsed config object
 */
export function parseConfigContent(content) {
  const trimmed = content.trim();
  if (!trimmed) return {};

  if (trimmed.startsWith('{')) {
    try {
      return JSON.parse(trimmed);
    } catch {
      // Fall through to key-value parsing
    }
  }

  const result = {};
  const lines = trimmed.split(/\r?\n/);
  for (const line of lines) {
    const clean = line.trim();
    if (!clean || clean.startsWith('#') || clean.startsWith(';')) continue;
    const eqIdx = clean.indexOf('=');
    if (eqIdx > 0) {
      const key = clean.slice(0, eqIdx).trim();
      let value = clean.slice(eqIdx + 1).trim();
      // Remove surrounding quotes if present
      if (
        (value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))
      ) {
        value = value.slice(1, -1);
      }

      if (key === 'GOPLAN_SERVER_URL' || key === 'serverUrl' || key === 'url') {
        result.serverUrl = value;
      } else if (key === 'GOPLAN_WORKSPACE_ID' || key === 'workspaceId' || key === 'workspace') {
        result.workspaceId = value;
      } else if (key === 'GOPLAN_TIMEOUT_MS' || key === 'timeoutMs' || key === 'timeout') {
        result.timeoutMs = parseInt(value, 10);
      } else {
        result[key] = value;
      }
    }
  }
  return result;
}

/**
 * Loads configuration with precedence:
 * 1. Explicit overrides passed as argument
 * 2. Environment variables (GOPLAN_SERVER_URL, GOPLAN_WORKSPACE_ID, GOPLAN_TIMEOUT_MS)
 * 3. Local config file (.goplanrc / .goplanrc.json) in current directory
 * 4. Global config file (.goplanrc / .goplanrc.json) in home directory
 * 5. Hardcoded defaults (http://127.0.0.1:8080)
 *
 * @param {object} [overrides={}] - Explicit runtime configuration
 * @returns {object} Final resolved configuration
 */
export function loadConfig(overrides = {}) {
  let globalConfig = {};
  const globalPath = overrides.globalConfigPath || resolveConfigFileInDir(os.homedir());
  if (globalPath && fs.existsSync(globalPath)) {
    try {
      globalConfig = parseConfigContent(fs.readFileSync(globalPath, 'utf8'));
      globalConfig._source = globalPath;
    } catch (e) {
      console.error(`Warning: Failed to read global config file ${globalPath}: ${e.message}`);
    }
  }

  let localConfig = {};
  const localPath = overrides.configPath || resolveConfigFileInDir(process.cwd()) || findConfigFile();
  // Don't re-read if localPath is the same file as globalPath
  if (localPath && localPath !== globalPath && fs.existsSync(localPath)) {
    try {
      localConfig = parseConfigContent(fs.readFileSync(localPath, 'utf8'));
      localConfig._source = localPath;
    } catch (e) {
      console.error(`Warning: Failed to read config file ${localPath}: ${e.message}`);
    }
  }

  // Merge global base with local overrides
  const fileConfig = { ...globalConfig, ...localConfig };
  const configFilePath = localConfig._source || globalConfig._source || null;

  const envConfig = {};
  if (process.env.GOPLAN_SERVER_URL) {
    envConfig.serverUrl = process.env.GOPLAN_SERVER_URL;
  }
  if (process.env.GOPLAN_WORKSPACE_ID) {
    envConfig.workspaceId = process.env.GOPLAN_WORKSPACE_ID;
  }
  if (process.env.GOPLAN_TIMEOUT_MS) {
    const t = parseInt(process.env.GOPLAN_TIMEOUT_MS, 10);
    if (!isNaN(t)) envConfig.timeoutMs = t;
  }

  let resolvedUrl = overrides.serverUrl || envConfig.serverUrl || fileConfig.serverUrl || DEFAULT_SERVER_URL;
  // Normalize serverUrl (strip any trailing slashes)
  if (typeof resolvedUrl === 'string') {
    resolvedUrl = resolvedUrl.replace(/\/+$/, '');
  }

  const merged = {
    serverUrl: resolvedUrl,
    workspaceId: overrides.workspaceId || envConfig.workspaceId || fileConfig.workspaceId || null,
    timeoutMs: overrides.timeoutMs || envConfig.timeoutMs || fileConfig.timeoutMs || DEFAULT_TIMEOUT_MS,
    configPath: configFilePath
  };

  return merged;
}

/**
 * Saves or updates configuration to a .goplanrc file.
 * @param {object} newConfig - Keys to save
 * @param {string} [targetPath] - Target file path, defaults to .goplanrc in current working directory
 */
export function saveConfig(newConfig, targetPath = path.join(process.cwd(), '.goplanrc')) {
  let existing = {};
  if (fs.existsSync(targetPath)) {
    try {
      existing = parseConfigContent(fs.readFileSync(targetPath, 'utf8'));
    } catch {
      existing = {};
    }
  }

  const combined = { ...existing, ...newConfig };
  const lines = [
    '# GoPlan Configuration',
    `GOPLAN_SERVER_URL=${combined.serverUrl || DEFAULT_SERVER_URL}`
  ];
  if (combined.workspaceId) {
    lines.push(`GOPLAN_WORKSPACE_ID=${combined.workspaceId}`);
  }
  if (combined.timeoutMs) {
    lines.push(`GOPLAN_TIMEOUT_MS=${combined.timeoutMs}`);
  }
  lines.push('');

  fs.writeFileSync(targetPath, lines.join('\n'), 'utf8');
  return targetPath;
}
