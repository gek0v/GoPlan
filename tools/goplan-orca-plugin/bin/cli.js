#!/usr/bin/env node

/**
 * GoPlan CLI for quick chat actions and terminal commands
 */

import fs from 'node:fs';
import path from 'node:path';
import { GoPlanClient } from '../src/client.js';
import { saveConfig, loadConfig } from '../src/config.js';
import { formatStatus, formatTodo, formatIdea, formatPlanResult } from '../src/utils/formatter.js';

function parseCliArgs(rawArgs) {
  let serverUrl;
  let workspaceId;
  const flags = {};
  const positional = [];

  for (let i = 0; i < rawArgs.length; i++) {
    const arg = rawArgs[i];
    if (arg.startsWith('--url=')) {
      serverUrl = arg.slice(6);
    } else if (arg === '--url' && i + 1 < rawArgs.length && !rawArgs[i + 1].startsWith('-')) {
      serverUrl = rawArgs[++i];
    } else if (arg.startsWith('--ws=') || arg.startsWith('--workspace=')) {
      workspaceId = arg.slice(arg.indexOf('=') + 1);
    } else if ((arg === '--ws' || arg === '--workspace' || arg === '-w') && i + 1 < rawArgs.length && !rawArgs[i + 1].startsWith('-')) {
      workspaceId = rawArgs[++i];
    } else if (arg.startsWith('--') || (arg.startsWith('-') && arg.length === 2)) {
      const eqIdx = arg.indexOf('=');
      if (eqIdx > 0) {
        flags[arg.slice(0, eqIdx)] = arg.slice(eqIdx + 1);
      } else {
        const flagName = arg;
        const knownValueFlags = ['--status', '--tag', '--group', '--file', '--path', '--file-path', '--idea', '--out', '--output', '--content', '--type'];
        if (knownValueFlags.includes(flagName) && i + 1 < rawArgs.length && !rawArgs[i + 1].startsWith('-')) {
          flags[flagName] = rawArgs[++i];
        } else {
          flags[flagName] = true;
        }
      }
    } else {
      positional.push(arg);
    }
  }

  return { serverUrl, workspaceId, flags, positional };
}

async function main() {
  const rawArgs = process.argv.slice(2);
  const { serverUrl, workspaceId, flags, positional } = parseCliArgs(rawArgs);
  const command = positional[0] ? positional[0].toLowerCase() : 'help';

  const client = new GoPlanClient({
    serverUrl,
    workspaceId
  });

  try {
    switch (command) {
      case 'status': {
        const targetUrl = positional[1] && (positional[1].startsWith('http://') || positional[1].startsWith('https://'))
          ? positional[1]
          : serverUrl;
        const activeClient = targetUrl ? new GoPlanClient({ serverUrl: targetUrl, workspaceId }) : client;
        const status = await activeClient.getStatus();
        console.log(formatStatus(status));
        break;
      }

      case 'context': {
        const wsArg = workspaceId || (positional[1] && !positional[1].startsWith('--') ? positional[1] : undefined);
        const isRaw = flags['--raw'] === true;
        const context = await client.getContext(wsArg, { raw: isRaw });
        const text = typeof context === 'string' ? context : context.content;
        console.log(text);
        break;
      }

      case 'save-context': {
        const input = flags['--file'] || flags['--path'] || positional[1];
        if (!input) {
          console.error('Error: Debe proporcionar el contenido o la ruta del archivo Markdown para guardar en CONTEXT.md.');
          process.exit(1);
        }
        let content = input;
        const isFilePath = input.endsWith('.md') || input.includes('/') || input.includes('\\');
        if (isFilePath && !fs.existsSync(input)) {
          console.error(`Error: No se encontró el archivo especificado: ${input}`);
          process.exit(1);
        }
        if (fs.existsSync(input) && fs.statSync(input).isFile()) {
          content = fs.readFileSync(input, 'utf8');
        }
        const wsArg = workspaceId || positional[2];
        const wsId = await client.resolveActiveWorkspace(wsArg);
        await client.saveContext(wsId, content);
        console.log(`✅ CONTEXT.md actualizado en workspace "${wsId}" (${content.length} caracteres).`);
        break;
      }

      case 'todo': {
        const wsArg = workspaceId || positional[1];
        const statusFilter = flags['--status'];
        const tagFilter = flags['--tag'];
        const groupBy = flags['--group'] || 'status';

        const wsId = await client.resolveActiveWorkspace(wsArg);
        const todo = await client.getTodo(wsId);
        console.log(`### 📋 Tareas en GoPlan (Workspace: ${wsId})\n`);
        console.log(formatTodo(todo, { groupBy, statusFilter, tagFilter }));
        break;
      }

      case 'idea': {
        const text = positional.slice(1).join(' ').trim();
        if (!text) {
          console.error('Error: Debe proporcionar el texto de la idea (ej: `goplan idea Mi nueva funcionalidad #backend`).');
          process.exit(1);
        }
        const idea = await client.createIdea(workspaceId, text);
        console.log(formatIdea(idea));
        break;
      }

      case 'plan': {
        const fileName = positional[1] || flags['--file'];
        if (!fileName) {
          console.error('Error: Debe proporcionar el nombre del archivo del plan (ej: `goplan plan autenticacion.md`).');
          process.exit(1);
        }
        const wsArg = workspaceId || positional[2];
        const content = await client.getPlan(wsArg, fileName);
        console.log(content);
        break;
      }

      case 'save-plan': {
        const ideaId = flags['--idea'] || positional[1];
        let fileName = flags['--file'];
        const filePath = flags['--path'] || flags['--file-path'];
        let contentText = flags['--content'];
        const wsArg = workspaceId || positional[2];

        if (!ideaId) {
          console.error('Error: Debe especificar el ID de la idea con --idea=<ID>');
          process.exit(1);
        }

        if (filePath) {
          if (!fs.existsSync(filePath)) {
            console.error(`Error: No se encontró el archivo especificado en --path: ${filePath}`);
            process.exit(1);
          }
          contentText = fs.readFileSync(filePath, 'utf8');
          if (!fileName) fileName = path.basename(filePath);
        }

        if (!fileName) {
          const safeId = ideaId.replace(/[^a-zA-Z0-9_-]/g, '_');
          fileName = `plan_${safeId}.md`;
        }

        const res = await client.savePlan(wsArg, {
          ideaId,
          fileName,
          content: contentText || '# Plan de Implementación\n\nGenerado por agente.',
          updateStatusToInProgress: true
        });
        console.log(formatPlanResult(res, ideaId, wsArg));
        if (res.success === false) {
          process.exit(1);
        }
        break;
      }

      case 'export': {
        const exportType = flags['--zip'] || flags['-z'] ? 'zip' : (flags['--md'] || flags['-m'] ? 'md' : (flags['--type'] || 'md'));
        let outputPath = flags['--out'] || flags['--output'];
        let wsArg = workspaceId;

        if (positional[1]) {
          if (positional[1].endsWith('.zip') || positional[1].endsWith('.md') || positional[1].includes('/') || positional[1].includes('\\')) {
            if (!outputPath) outputPath = positional[1];
          } else if (!wsArg) {
            wsArg = positional[1];
          }
        }
        if (positional[2] && !outputPath) {
          outputPath = positional[2];
        }

        const wsId = await client.resolveActiveWorkspace(wsArg);
        const bundle = await client.exportWorkspace(wsId, { type: exportType });

        if (outputPath) {
          let resolved = path.resolve(outputPath);
          const isDir = (fs.existsSync(resolved) && fs.statSync(resolved).isDirectory()) ||
                        outputPath.endsWith('/') || outputPath.endsWith('\\');
          if (isDir) {
            resolved = path.join(resolved, `${wsId}-export.${exportType}`);
          }
          fs.mkdirSync(path.dirname(resolved), { recursive: true });
          if (bundle.type === 'zip') {
            fs.writeFileSync(resolved, bundle.content);
          } else {
            fs.writeFileSync(resolved, bundle.content, 'utf8');
          }
          console.log(`📦 Exportación guardada en: ${resolved}`);
        } else {
          if (bundle.type === 'md') {
            console.log(bundle.content);
          } else {
            console.log(`📦 Bundle ZIP recibido (${bundle.content.length} bytes). Usa --out=archivo.zip para guardarlo.`);
          }
        }
        break;
      }

      case 'config': {
        const newUrl = positional[1];
        const newWs = workspaceId || positional[2];
        if (newUrl) {
          const savedPath = saveConfig({
            serverUrl: newUrl,
            ...(newWs ? { workspaceId: newWs } : {})
          });
          console.log(`✅ Configuración guardada en ${savedPath}:`);
          console.log(`- URL: ${newUrl}`);
          if (newWs) console.log(`- Workspace: ${newWs}`);
        } else {
          const cfg = loadConfig();
          console.log('Configuración actual de GoPlan:');
          console.log(`- Server URL: ${cfg.serverUrl}`);
          console.log(`- Default Workspace: ${cfg.workspaceId || '(detectar automáticamente desde app)'}`);
          console.log(`- Archivo de config: ${cfg.configPath || '(ninguno, usando defaults/env)'}`);
        }
        break;
      }

      case 'update': {
        const shouldBuild = flags['--build'] === true;
        let apkPath = flags['--file'] || flags['--path'];

        if (shouldBuild) {
          console.log('🔨 Compilando APK debug local (gradlew assembleDebug)...');
          const { execSync } = await import('node:child_process');
          const isWindows = process.platform === 'win32';
          const gradlewCmd = isWindows ? '.\\gradlew.bat assembleDebug' : './gradlew assembleDebug';
          execSync(gradlewCmd, { stdio: 'inherit' });
          if (!apkPath) {
            apkPath = path.resolve(process.cwd(), 'app/build/outputs/apk/debug/app-debug.apk');
          }
        }

        if (!apkPath) {
          const defaultPath = path.resolve(process.cwd(), 'app/build/outputs/apk/debug/app-debug.apk');
          if (fs.existsSync(defaultPath)) {
            apkPath = defaultPath;
          } else {
            throw new Error(
              'No se encontró el archivo APK. Ejecuta "goplan update --build" para compilarlo automáticamente o especifica "--file=ruta/al/archivo.apk".'
            );
          }
        }

        if (!fs.existsSync(apkPath)) {
          throw new Error(`Archivo APK no encontrado en: ${apkPath}`);
        }

        const stats = fs.statSync(apkPath);
        const mb = (stats.size / (1024 * 1024)).toFixed(1);
        console.log(`📤 Enviando APK (${mb} MB) por Wi-Fi al servidor GoPlan (${client.baseUrl})...`);

        const res = await client.uploadApkUpdate(apkPath);
        console.log(`✅ ${res.message || 'Actualización enviada con éxito.'}`);
        console.log('📱 ¡Revisa la pantalla de tu móvil! El instalador de paquetes de Android se ha abierto.');
        break;
      }

      case 'help':
      default: {
        console.log(`
GoPlan CLI - Herramienta de sincronización rápida con app móvil

Comandos disponibles:
  status [--url=..]           Verifica conexión y muestra workspace activo
  context [--ws=..] [--raw]   Obtiene el contenido de CONTEXT.md
  save-context <texto|file>   Actualiza CONTEXT.md en la app móvil
  todo [--status=..] [--tag=] Lista tareas e ideas de TODO.md
  idea <texto con #tags>      Envía una nueva idea directamente a GoPlan
  plan <nombre.md>            Lee un archivo de plan de la app
  save-plan --idea=.. --file=.. Ingesta un plan y pasa la idea a IN_PROGRESS
  export [--type=md|zip]      Descarga bundle consolidado
  update [--build] [--file=..] Actualiza la app en el móvil por Wi-Fi al instante
  config [url] [workspaceId]  Muestra o guarda la configuración en .goplanrc
`);
        break;
      }
    }
  } catch (err) {
    if (err.diagnostic) {
      console.error(err.diagnostic);
    } else {
      console.error(`Error: ${err.message}`);
    }
    process.exit(1);
  }
}

main();
