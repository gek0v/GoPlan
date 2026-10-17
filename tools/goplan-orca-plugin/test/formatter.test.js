import { describe, it } from 'node:test';
import assert from 'node:assert';
import {
  formatStatus,
  formatTodo,
  formatIdea,
  formatPlanResult,
  renderIdeaLine
} from '../src/utils/formatter.js';

describe('Formatter Utilities', () => {
  it('formats online and offline server status', () => {
    const online = formatStatus({
      status: 'online',
      app: 'GoPlan',
      version: '1.0.0',
      activeWorkspace: 'default',
      ip: '192.168.1.5',
      port: 8080
    });
    assert(online.includes('🟢'));
    assert(online.includes('ONLINE'));
    assert(online.includes('`default`'));
    assert(online.includes('192.168.1.5'));

    const offline = formatStatus({
      status: 'offline',
      activeWorkspace: null
    });
    assert(offline.includes('🔴'));
    assert(offline.includes('Ninguno'));
  });

  it('formats empty todo list', () => {
    const empty = formatTodo({ ideas: [] });
    assert(empty.includes('No hay ideas o tareas'));
  });

  it('formats todo list grouped by status', () => {
    const todo = {
      ideas: [
        { id: '1', title: 'Task 1', status: 'PENDING', tags: ['ui'], plans: [] },
        { id: '2', title: 'Task 2', status: 'IN_PROGRESS', tags: ['api'], plans: [{ label: 'auth', relativePath: 'planes/auth.md' }] },
        { id: '3', title: 'Task 3', status: 'COMPLETED', tags: [], plans: [] }
      ]
    };

    const formatted = formatTodo(todo, { groupBy: 'status' });
    assert(formatted.includes('⏳ En Progreso (`IN_PROGRESS`)'));
    assert(formatted.includes('Task 2'));
    assert(formatted.includes('📝 Pendientes (`PENDING`)'));
    assert(formatted.includes('Task 1'));
    assert(formatted.includes('✅ Completadas (`COMPLETED`)'));
    assert(formatted.includes('Task 3'));
  });

  it('formats todo list filtered by tag', () => {
    const todo = {
      ideas: [
        { id: '1', title: 'Task 1', status: 'PENDING', tags: ['frontend'], plans: [] },
        { id: '2', title: 'Task 2', status: 'PENDING', tags: ['backend'], plans: [] }
      ]
    };

    const formatted = formatTodo(todo, { tagFilter: '#frontend' });
    assert(formatted.includes('Task 1'));
    assert(!formatted.includes('Task 2'));
  });

  it('formats todo list grouped by tag', () => {
    const todo = {
      ideas: [
        { id: '1', title: 'Task 1', status: 'PENDING', tags: ['urgent'], plans: [] },
        { id: '2', title: 'Task 2', status: 'PENDING', tags: [], plans: [] }
      ]
    };

    const formatted = formatTodo(todo, { groupBy: 'tag' });
    assert(formatted.includes('Etiqueta #urgent'));
    assert(formatted.includes('Sin Etiquetas'));
  });

  it('formats created idea with and without tags', () => {
    const idea = {
      id: 'idea-5',
      title: 'New Feature',
      status: 'PENDING',
      tags: ['cool', 'feature'],
      plans: []
    };
    const formatted = formatIdea(idea);
    assert(formatted.includes('idea-5'));
    assert(formatted.includes('New Feature'));
    assert(formatted.includes('#cool #feature'));
  });

  it('formats plan ingestion result', () => {
    const res = formatPlanResult(
      { success: true, createdFiles: ['spec.md'], message: 'Ok' },
      'idea-12',
      'workspace-a'
    );
    assert(res.includes('idea-12'));
    assert(res.includes('IN_PROGRESS'));
    assert(res.includes('planes/spec.md'));
    assert(res.includes('workspace-a'));
  });
});
