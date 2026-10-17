import { loadConfig, DEFAULT_SERVER_URL, DEFAULT_TIMEOUT_MS } from './config.js';
import { formatConnectionDiagnostic, formatNoActiveWorkspaceDiagnostic } from './utils/diagnostics.js';

export const MAX_PLAN_SIZE = 10 * 1024 * 1024; // 10MB
export const MAX_CONTEXT_SIZE = 10 * 1024 * 1024; // 10MB
export const MAX_IDEA_SIZE = 100 * 1024; // 100KB

/**
 * Validates and normalizes plan file name against path traversal and illegal characters.
 * @param {string} fileName
 * @returns {string} Clean file name ending in .md
 */
export function validatePlanFileName(fileName) {
  if (!fileName || typeof fileName !== 'string' || !fileName.trim()) {
    throw new Error('Missing plan fileName');
  }

  let cleanName = fileName.trim().replace(/^planes[\\/]/i, '');
  if (!cleanName.endsWith('.md')) {
    cleanName += '.md';
  }

  const baseName = cleanName.slice(0, -3).trim();
  if (!baseName) {
    throw new Error('Invalid plan file name: filename cannot be empty');
  }

  // Multi-decode defensive check against URL-encoded path traversal attacks
  let decoded = cleanName;
  try {
    for (let i = 0; i < 5; i++) {
      const next = decodeURIComponent(decoded);
      if (next === decoded) break;
      decoded = next;
    }
  } catch {}

  const INVALID_FILENAME_CHARS = /[<>:"/\\|?*\x00-\x1f\x7f]/;
  if (
    cleanName.includes('..') || cleanName.includes('/') || cleanName.includes('\\') || cleanName.includes('\0') ||
    decoded.includes('..') || decoded.includes('/') || decoded.includes('\\') || decoded.includes('\0') ||
    INVALID_FILENAME_CHARS.test(cleanName) || INVALID_FILENAME_CHARS.test(decoded)
  ) {
    throw new Error('Invalid plan file name: path traversal not allowed');
  }

  return cleanName;
}

/**
 * GoPlan HTTP REST API Client
 */
export class GoPlanClient {
  /**
   * @param {object} [options={}]
   * @param {string} [options.serverUrl] - GoPlan server base URL (e.g. "http://192.168.1.50:8080")
   * @param {string} [options.workspaceId] - Default workspace ID
   * @param {number} [options.timeoutMs] - Request timeout in ms
   * @param {typeof fetch} [options.fetchFn] - Custom fetch function for dependency injection / mocking
   */
  constructor(options = {}) {
    this.options = options;
    const config = loadConfig(options);
    this.serverUrl = config.serverUrl;
    this.defaultWorkspaceId = config.workspaceId;
    this.timeoutMs = config.timeoutMs || DEFAULT_TIMEOUT_MS;
    this.fetchFn = options.fetchFn || globalThis.fetch;
  }

  /**
   * Internal HTTP request helper with timeout and diagnostic error handling.
   * @private
   */
  async _request(endpoint, options = {}) {
    const cleanEndpoint = endpoint.startsWith('/') ? endpoint : `/${endpoint}`;
    const url = `${this.serverUrl}${cleanEndpoint}`;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), options.timeoutMs || this.timeoutMs);

    const headers = {
      Accept: 'application/json, text/plain, */*',
      ...(options.headers || {})
    };

    if (options.body && typeof options.body === 'object' && !(options.body instanceof Uint8Array)) {
      headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(options.body);
    }

    try {
      const response = await this.fetchFn(url, {
        ...options,
        headers,
        signal: controller.signal
      });
      clearTimeout(timer);

      if (!response.ok) {
        let errorBody;
        try {
          errorBody = await response.json();
        } catch {
          try {
            errorBody = await response.text();
          } catch {
            errorBody = response.statusText;
          }
        }
        const message = typeof errorBody === 'object'
          ? (errorBody.message || JSON.stringify(errorBody))
          : errorBody;
        const err = new Error(`GoPlan API Error (${response.status} ${response.statusText}): ${message}`);
        err.status = response.status;
        err.responseBody = errorBody;
        throw err;
      }

      return response;
    } catch (error) {
      clearTimeout(timer);
      if (error.name === 'AbortError') {
        const timeoutErr = new Error(`GoPlan request timed out after ${this.timeoutMs}ms to ${url}`);
        timeoutErr.code = 'ETIMEDOUT';
        timeoutErr.diagnostic = formatConnectionDiagnostic(this.serverUrl, timeoutErr);
        throw timeoutErr;
      }
      if (!error.status) {
        // Network connection error (refused, unreachable, etc.)
        error.diagnostic = formatConnectionDiagnostic(this.serverUrl, error);
      }
      throw error;
    }
  }

  /**
   * Check connection and get server health status.
   * GET /api/status
   * @returns {Promise<{ status: string, app: string, version: string, activeWorkspace: string|null, ip: string|null, port: number }>}
   */
  async getStatus() {
    const res = await this._request('/api/status');
    return await res.json();
  }

  /**
   * Resolves the target workspace ID.
   * Uses explicit workspaceId if provided, else configured default,
   * else asks server for activeWorkspace via GET /api/status.
   * @param {string} [explicitId]
   * @returns {Promise<string>}
   */
  async resolveActiveWorkspace(explicitId) {
    if (explicitId && typeof explicitId === 'string' && explicitId.trim()) {
      return explicitId.trim();
    }
    if (this.defaultWorkspaceId && this.defaultWorkspaceId.trim()) {
      return this.defaultWorkspaceId.trim();
    }
    const status = await this.getStatus();
    if (status.activeWorkspace && status.activeWorkspace.trim()) {
      return status.activeWorkspace.trim();
    }
    const err = new Error(formatNoActiveWorkspaceDiagnostic(this.serverUrl));
    err.code = 'NO_ACTIVE_WORKSPACE';
    throw err;
  }

  /**
   * Get list of all workspaces and their stats.
   * GET /api/workspaces
   * @returns {Promise<Array<{ id: string, name: string, stats: object }>>}
   */
  async getWorkspaces() {
    const res = await this._request('/api/workspaces');
    return await res.json();
  }

  /**
   * Create a new workspace on GoPlan.
   * POST /api/workspaces
   * @param {string} name
   * @returns {Promise<{ id: string, name: string, stats: object }>}
   */
  async createWorkspace(name) {
    if (!name || !name.trim()) {
      throw new Error('Workspace name cannot be empty');
    }
    const res = await this._request('/api/workspaces', {
      method: 'POST',
      body: { name: name.trim() }
    });
    return await res.json();
  }

  /**
   * Get CONTEXT.md content of a workspace.
   * GET /api/workspaces/{id}/context
   * @param {string} [workspaceId]
   * @param {object} [options]
   * @param {boolean} [options.raw=false]
   * @returns {Promise<{ workspaceId: string, content: string } | string>}
   */
  async getContext(workspaceId, options = {}) {
    const wsId = await this.resolveActiveWorkspace(workspaceId);
    const query = options.raw ? '?format=raw' : '';
    const res = await this._request(`/api/workspaces/${encodeURIComponent(wsId)}/context${query}`, {
      timeoutMs: options.timeoutMs
    });
    if (options.raw) {
      return await res.text();
    }
    return await res.json();
  }

  /**
   * Update CONTEXT.md content on the mobile app.
   * PUT /api/workspaces/{id}/context
   * @param {string} [workspaceId]
   * @param {string} content
   * @returns {Promise<{ success: boolean, message?: string }>}
   */
  async saveContext(workspaceId, content) {
    const wsId = await this.resolveActiveWorkspace(workspaceId);
    if (typeof content !== 'string') {
      throw new Error('Context content must be a string');
    }
    if (Buffer.byteLength(content, 'utf8') > MAX_CONTEXT_SIZE) {
      throw new Error('Context content exceeds maximum allowed size of 10MB');
    }
    const res = await this._request(`/api/workspaces/${encodeURIComponent(wsId)}/context`, {
      method: 'PUT',
      body: { workspaceId: wsId, content }
    });
    return await res.json();
  }

  /**
   * Get TODO.md tasks and ideas.
   * GET /api/workspaces/{id}/todo
   * @param {string} [workspaceId]
   * @returns {Promise<{ rawMarkdown: string, ideas: Array<{ id: string, title: string, status: string, tags: string[], plans: Array<{ label: string, relativePath: string }> }> }>}
   */
  async getTodo(workspaceId) {
    const wsId = await this.resolveActiveWorkspace(workspaceId);
    const res = await this._request(`/api/workspaces/${encodeURIComponent(wsId)}/todo`);
    return await res.json();
  }

  /**
   * Send a new idea or task with #tags.
   * POST /api/workspaces/{id}/ideas
   * @param {string} [workspaceId]
   * @param {string} text - e.g. "Implement biometric auth #auth #security"
   * @returns {Promise<{ id: string, title: string, status: string, tags: string[], plans: any[] }>}
   */
  async createIdea(workspaceId, text) {
    const wsId = await this.resolveActiveWorkspace(workspaceId);
    if (typeof text !== 'string' || !text.trim()) {
      throw new Error('Idea text cannot be blank');
    }
    if (Buffer.byteLength(text, 'utf8') > MAX_IDEA_SIZE) {
      throw new Error('Idea text exceeds maximum allowed size of 100KB');
    }
    // Normalize adjacent hashtags (#tag1#tag2 -> #tag1 #tag2) so Android parser extracts all tags cleanly
    const normalizedText = text.replace(/#([a-zA-Z0-9_\u00A0-\uFFFF-]+)(?=#)/g, '#$1 ');
    const res = await this._request(`/api/workspaces/${encodeURIComponent(wsId)}/ideas`, {
      method: 'POST',
      body: { text: normalizedText.trim() }
    });
    return await res.json();
  }

  /**
   * Update an idea status, title or tags.
   * PATCH /api/workspaces/{id}/ideas/{ideaId}
   * @param {string} [workspaceId]
   * @param {string} ideaId
   * @param {object} updateData - { status?: string, title?: string, tags?: string[] }
   * @returns {Promise<{ success: boolean, idea?: object }>}
   */
  async updateIdea(workspaceId, ideaId, updateData = {}) {
    const wsId = await this.resolveActiveWorkspace(workspaceId);
    if (!ideaId || typeof ideaId !== 'string' || !ideaId.trim()) throw new Error('Missing ideaId');
    const res = await this._request(`/api/workspaces/${encodeURIComponent(wsId)}/ideas/${encodeURIComponent(ideaId.trim())}`, {
      method: 'PATCH',
      body: updateData
    });
    return await res.json();
  }

  /**
   * Delete an idea and its associated plans.
   * DELETE /api/workspaces/{id}/ideas/{ideaId}
   * @param {string} [workspaceId]
   * @param {string} ideaId
   * @returns {Promise<{ success: boolean, message?: string }>}
   */
  async deleteIdea(workspaceId, ideaId) {
    const wsId = await this.resolveActiveWorkspace(workspaceId);
    if (!ideaId || typeof ideaId !== 'string' || !ideaId.trim()) throw new Error('Missing ideaId');
    const res = await this._request(`/api/workspaces/${encodeURIComponent(wsId)}/ideas/${encodeURIComponent(ideaId.trim())}`, {
      method: 'DELETE'
    });
    return await res.json();
  }

  /**
   * Read the content of a specific plan file in planes/<nombre>.md.
   * GET /api/workspaces/{id}/plans/{fileName...}
   * @param {string} [workspaceId]
   * @param {string} fileName - e.g. "auth_plan.md" or "planes/auth_plan.md"
   * @returns {Promise<string>} Markdown content of the plan
   */
  async getPlan(workspaceId, fileName) {
    const wsId = await this.resolveActiveWorkspace(workspaceId);
    const cleanName = validatePlanFileName(fileName);
    const res = await this._request(`/api/workspaces/${encodeURIComponent(wsId)}/plans/${encodeURIComponent(cleanName)}`);
    return await res.text();
  }

  /**
   * Ingest a plan or update on GoPlan and transition idea status to IN_PROGRESS.
   * POST /api/workspaces/{id}/plans
   * @param {string} [workspaceId]
   * @param {object} params
   * @param {string} params.ideaId - Target idea ID to attach plan to
   * @param {string} [params.fileName] - Name of plan file (if single file provided with content)
   * @param {string} [params.content] - Content of plan file (if single file provided with fileName)
   * @param {Array<{ fileName: string, content: string }>} [params.files] - List of plan files
   * @param {string} [params.rawText] - Raw markdown text containing plans
   * @param {boolean} [params.updateStatusToInProgress=true] - Transition idea to IN_PROGRESS
   * @returns {Promise<{ success: boolean, createdFiles: string[], ideaId: string, statusUpdated: boolean }>}
   */
  async savePlan(workspaceId, params = {}) {
    const wsId = await this.resolveActiveWorkspace(workspaceId);
    const { ideaId, fileName, content, files, rawText, updateStatusToInProgress = true } = params;

    if (!ideaId || typeof ideaId !== 'string' || !ideaId.trim()) {
      throw new Error('Missing ideaId to link the plan to');
    }

    let filesPayload = files;
    if (!filesPayload && fileName !== undefined && content !== undefined) {
      const clean = validatePlanFileName(fileName);
      if (typeof content !== 'string') {
        throw new Error('Plan content must be a string');
      }
      if (Buffer.byteLength(content, 'utf8') > MAX_PLAN_SIZE) {
        throw new Error('Plan content exceeds maximum allowed size of 10MB');
      }
      filesPayload = [{ fileName: clean, content }];
    }

    let processedFiles;
    if (filesPayload) {
      if (!Array.isArray(filesPayload)) {
        throw new Error('files parameter must be an array');
      }
      processedFiles = [];
      for (const f of filesPayload) {
        if (!f || typeof f !== 'object') {
          throw new Error('Invalid plan file: file object is required');
        }
        const cleanName = validatePlanFileName(f.fileName);
        if (typeof f.content !== 'string') {
          throw new Error(`Plan file "${cleanName}" content must be a string`);
        }
        if (Buffer.byteLength(f.content, 'utf8') > MAX_PLAN_SIZE) {
          throw new Error(`Plan file "${cleanName}" exceeds maximum allowed size of 10MB`);
        }
        processedFiles.push({ fileName: cleanName, content: f.content });
      }
    }

    if (rawText !== undefined) {
      if (typeof rawText !== 'string') {
        throw new Error('Plan rawText must be a string');
      }
      if (Buffer.byteLength(rawText, 'utf8') > MAX_PLAN_SIZE) {
        throw new Error('Plan rawText exceeds maximum allowed size of 10MB');
      }
    }

    if (!processedFiles && !rawText) {
      throw new Error('Must provide either fileName + content, files array, or rawText for the plan');
    }

    const body = {
      ideaId: ideaId.trim(),
      ...(processedFiles ? { files: processedFiles } : {}),
      ...(rawText ? { rawText } : {})
    };

    const res = await this._request(`/api/workspaces/${encodeURIComponent(wsId)}/plans`, {
      method: 'POST',
      body
    });
    const result = await res.json();

    if (result.success === false) {
      return {
        success: false,
        message: result.message || 'Plan ingestion failed',
        createdFiles: [],
        ideaId: ideaId.trim(),
        statusUpdated: false
      };
    }

    // Per R2 requirement: "vinculándolo a su idea y pasando su estado a IN_PROGRESS"
    let statusUpdated = false;
    if (updateStatusToInProgress) {
      try {
        await this.updateIdea(wsId, ideaId.trim(), { status: 'IN_PROGRESS' });
        statusUpdated = true;
      } catch (e) {
        // Log warning if status transition fails, but don't fail plan ingestion
        console.warn(`[GoPlan] Note: Plan was saved but could not update idea ${ideaId} status: ${e.message}`);
      }
    }

    return {
      success: result.success !== false,
      message: result.message,
      createdFiles: result.createdFiles || [],
      ideaId: ideaId.trim(),
      statusUpdated
    };
  }

  /**
   * Export workspace bundle (unified Markdown or ZIP).
   * GET /api/workspaces/{id}/export?type=md|zip
   * @param {string} [workspaceId]
   * @param {object} [options]
   * @param {'md' | 'zip'} [options.type='md']
   * @param {number} [options.timeoutMs]
   * @returns {Promise<{ type: 'md'|'zip', content: string|Uint8Array }>}
   */
  async exportWorkspace(workspaceId, options = {}) {
    const wsId = await this.resolveActiveWorkspace(workspaceId);
    const exportType = (options.type || 'md').toLowerCase();
    if (!['md', 'zip'].includes(exportType)) {
      throw new Error(`Invalid export type "${exportType}". Supported: "md", "zip"`);
    }

    const res = await this._request(`/api/workspaces/${encodeURIComponent(wsId)}/export?type=${exportType}`, {
      timeoutMs: options.timeoutMs
    });
    if (exportType === 'md') {
      const markdown = await res.text();
      return { type: 'md', content: markdown };
    } else {
      const buffer = await res.arrayBuffer();
      return { type: 'zip', content: new Uint8Array(buffer) };
    }
  }

  /**
   * Upload binary APK file to GoPlan server for immediate in-place update.
   * POST /api/system/update
   * @param {Buffer | Uint8Array | string} apkSource - Raw APK buffer or file path to .apk
   * @param {object} [options]
   * @param {number} [options.timeoutMs=60000]
   * @returns {Promise<{ success: boolean, message: string }>}
   */
  async uploadApkUpdate(apkSource, options = {}) {
    let buffer;
    if (typeof apkSource === 'string') {
      const fs = await import('fs');
      if (!fs.existsSync(apkSource)) {
        throw new Error(`APK file not found at: ${apkSource}`);
      }
      buffer = fs.readFileSync(apkSource);
    } else if (typeof Buffer !== 'undefined' && Buffer.isBuffer(apkSource)) {
      buffer = apkSource;
    } else if (apkSource instanceof Uint8Array) {
      buffer = apkSource;
    } else {
      throw new Error('Invalid APK source: must be a file path string or Buffer/Uint8Array');
    }

    if (buffer.length < 4) {
      throw new Error('Invalid APK: file is too small');
    }

    // Verify ZIP/APK magic bytes: 0x50 0x4B 0x03 0x04
    if (buffer[0] !== 0x50 || buffer[1] !== 0x4B || buffer[2] !== 0x03 || buffer[3] !== 0x04) {
      throw new Error('Invalid APK header: file is not a valid ZIP/APK archive');
    }

    const res = await this._request('/api/system/update', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': String(buffer.length)
      },
      body: buffer,
      timeoutMs: options.timeoutMs || 60000
    });

    const result = await res.json();
    return result;
  }
}
