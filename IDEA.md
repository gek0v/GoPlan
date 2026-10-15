# Especificación de Proyecto: Gestor de Ideas y Planes para Desarrollo Agéntico (Android)

## 1. Visión General
Aplicación móvil nativa para Android diseñada para la captura ultrarrápida de ideas de programación organizadas por proyectos (*Workspaces*), su expansión a planes técnicos detallados apoyándose en Gemini (vía intents nativos de Android sin consumo de cuota API), y su transferencia ágil hacia entornos de desarrollo locales (PC) para ser consumidos por agentes de IA.

---

## 2. Arquitectura de Almacenamiento y Estructura de Archivos

La fuente de la verdad serán archivos de texto plano en formato **Markdown (`.md`)**, almacenados localmente en el dispositivo para garantizar velocidad *offline-first* y sincronizados en segundo plano con una carpeta dedicada en **Google Drive** (usando Google Drive API v3).

### 2.1. Árbol de Directorios (Local y en Google Drive)
```text
/GoPlan/ (Carpeta raíz en Google Drive)
├── Proyecto_Alpha/ (Workspace)
│   ├── CONTEXT.md
│   ├── TODO.md
│   └── planes/
│       ├── oauth-google.md
│       ├── pestana-concurso-ui.md
│       └── pestana-concurso-api.md
└── Proyecto_Beta/ (Workspace)
    ├── CONTEXT.md
    ├── TODO.md
    └── planes/
```

### 2.2. Especificación de Archivos Base por Workspace
1. **`CONTEXT.md`**: Archivo de configuración descriptiva del proyecto (stack tecnológico, reglas de arquitectura, convenciones de código, librerías usadas). Se adjuntará automáticamente como contexto oculto/visible al compartir una idea hacia Gemini o al empaquetar para un agente.
2. **`TODO.md`**: Índice principal de ideas del proyecto. La aplicación parseará este archivo para renderizar una interfaz nativa de lista interactiva y escribirá sobre él ante cualquier cambio en la UI.
3. **Carpeta `planes/`**: Almacena los documentos Markdown detallados generados a partir de una idea simple.

---

## 3. Modelo de Datos y Sintaxis Markdown (`TODO.md`)

Cada línea de tarea en `TODO.md` representa una `Idea`. Se utilizará sintaxis Markdown extendida para mantener compatibilidad total con editores externos (Obsidian, VS Code) y lectura directa por agentes.

### 3.1. Estados de una Idea
* `- [ ]` **Pendiente**: Idea capturada, sin iniciar.
* `- [/]` **En progreso**: Actualmente en desarrollo o planificación.
* `- [x]` **Completado**: Implementada.
* `- [-]` **Descartado**: Idea desechada (se mantiene en el historial pero se oculta por defecto en la UI).

### 3.2. Tipos de Ítems y Etiquetas
* **Idea Simple (Solo texto):**
  ```markdown
  - [ ] Incorporar OAuth Google #auth #backend
  ```
* **Idea Compleja (Con enlace a un único archivo de plan):**
  ```markdown
  - [/] [Incorporar OAuth Google](planes/oauth-google.md) #auth #backend
  ```
* **Idea Compleja Multi-archivo (Con enlaces a múltiples archivos relacionados):**
  ```markdown
  - [ ] Añadir pestaña Concurso ([UI](planes/pestana-concurso-ui.md), [API](planes/pestana-concurso-api.md)) #ui #feature
  ```

---

## 4. Flujos de Integración con IA (Gemini) vía Android Intents

Para evitar la gestión de API Keys y cuotas de facturación, la comunicación de ida y vuelta con la app oficial de Gemini se realiza mediante el sistema nativo de Intents de Android (`Intent.ACTION_SEND`).

### 4.1. Flujo de Salida: De la App hacia Gemini ("Expandir Idea")
1. El usuario selecciona una idea simple en la app y pulsa **"Expandir con Gemini"**.
2. La app construye en memoria un bloque de texto estructurado:
   * Contenido del `CONTEXT.md` del Workspace actual.
   * Texto de la idea seleccionada y sus etiquetas.
   * Instrucción recordatoria del formato de salida esperado (separadores).
3. Se lanza el menú nativo `ACTION_SEND` (`text/plain`), permitiendo seleccionar la app de Gemini (idealmente interactuando con un **Gem personalizado**).

### 4.2. Flujo de Entrada: De Gemini hacia la App ("Recibir Plan")
1. La app registra un `intent-filter` en su `AndroidManifest.xml` para recibir `android.intent.action.SEND` con MIME type `text/plain`.
2. En la app de Gemini, el usuario pulsa "Compartir" sobre la respuesta generada y elige nuestra aplicación en el menú de Android.
3. Se abre un modal/pantalla de recepción en nuestra app que solicita:
   * **Seleccionar Workspace** (por defecto el último activo).
   * **Seleccionar Idea destino** (lista de ideas existentes en el `TODO.md` o botón *"Crear nueva idea a partir de este plan"*).
4. **Procesamiento del texto recibido (Parser Multi-archivo):**
   * **Modo Automático (Con separador):** Si el texto contiene bloques delimitados por la etiqueta `=== ARCHIVO: nombre-archivo.md ===`, la app trocea el texto automáticamente, crea cada archivo dentro de la carpeta `planes/` del Workspace, y actualiza la línea correspondiente en `TODO.md` con los enlaces generados.
   * **Modo Manual (Sin separador o edición en vivo):** Si no detecta el separador, guarda todo en un único archivo `.md` dentro de `planes/`. Incluye además un botón en el editor llamado **"Dividir desde el cursor"** que corta el texto desde la posición actual hacia abajo y lo extrae a un nuevo archivo `.md` enlazado a la misma tarea.

---

## 5. Experiencia de Usuario e Interfaz (UI/UX en Android)

### 5.1. Pantalla Principal del Workspace (Vista de Lista Nativa)
* **Cabecera:** Selector rápido de Workspace (Drawer o Dropdown), botón de acceso rápido para editar `CONTEXT.md`, estado de sincronización con Drive y botón de exportación.
* **Filtros rápidos (Chips):** Filtrar por estado (`Pendientes`, `En progreso`, `Completadas`, `Descartadas`) y por `#etiquetas` detectadas en el archivo.
* **Lista de Ideas:**
  * *Tap corto en idea simple:* Abre opciones rápidas (Editar texto, Añadir/Crear plan manualmente, Compartir con Gemini).
  * *Tap corto en idea compleja:* Abre el visor/editor Markdown con pestañas superiores si la tarea tiene múltiples archivos de plan asociados.
  * *Swipe derecha/izquierda:* Cambio rápido de estado (ciclo o menú emergente de los 4 estados).
* **Barra Inferior (Quick Input tipo Chat):**
  * Campo de texto siempre visible en la parte inferior con autocompletado de `#etiquetas` existentes y botón de envío rápido para capturar ideas en menos de 2 segundos.

### 5.2. Visor / Editor de Planes (Markdown)
* Vista previa renderizada de Markdown con conmutador a modo edición de texto plano.
* Barra de herramientas auxiliar sobre el teclado: atajos para `#`, `- [ ]`, bloques de código, y el botón **"Dividir en nuevo archivo"** (para trocear planes grandes manualmente).

---

## 6. Transferencia y Exportación hacia Agentes (Entorno PC)

Además de la sincronización transparente mediante la carpeta de Google Drive, la app contará con un módulo de **Exportación Rápida para Agentes** (accesible por tarea individual, selección múltiple o proyecto completo).

### 6.1. Empaquetado de Contexto
Al exportar una tarea (o varias), el usuario puede elegir:
* **Archivo individual (`.md`):** Envía el plan o concatena en un solo archivo el `CONTEXT.md` + el plan de la tarea.
* **Paquete comprimido (`.zip`):** Genera un archivo `.zip` en caché que contiene `CONTEXT.md`, el fragmento relevante de `TODO.md` y todos los archivos asociados dentro de `planes/`.

### 6.2. Métodos de Envío por Red Local
1. **Menú Compartir Nativo (`ACTION_SEND`):** Permite enviar el `.md` o `.zip` directamente a través de herramientas instaladas como **LocalSend**, **Quick Share**, Telegram, etc.
2. **Servidor HTTP Efímero Integrado:**
   * Al pulsar *"Servir en Red Local"*, la app levanta un micro-servidor HTTP local (ej. usando `Ktor` o `NanoHTTPD` en un puerto libre como `8080`).
   * Muestra un diálogo en pantalla con:
     * Un **Código QR**.
     * La URL local explícita (ej. `http://192.168.1.45:8080/bundle.zip`).
     * Un comando listo para copiar/ejecutar por un agente en terminal: `curl -O http://192.168.1.45:8080/bundle.zip`.
   * El servidor se apaga automáticamente al cerrar el diálogo o tras completar la descarga.

---

## 7. Stack Tecnológico Recomendado (Para el Agente Desarrollador)

* **Lenguaje:** Kotlin.
* **UI Framework:** Jetpack Compose (Material 3).
* **Arquitectura:** MVVM con Clean Architecture (UI Layer -> Domain -> Data Layer).
* **Almacenamiento Local:** Sistema de archivos nativo (File API en almacenamiento interno/específico de la app) + `Room` o `DataStore` únicamente para caché de índices, metadatos de sincronización con Drive y preferencias.
* **Sincronización Nube:** Google Auth + Google Drive API v3 + `WorkManager` para sincronización periódica y tras modificaciones.
* **Servidor Local Embebido:** `NanoHTTPD` o `Ktor Server (CIO engine)` para servir los paquetes `.zip`/`.md` en la LAN.

---

## 8. Anexo: System Prompt para tu "Gem" en Gemini

Puedes crear un Gem personalizado en Gemini llamado **"Arquitecto de Planes"** y pegarle estas instrucciones para que siempre formatee las respuestas compatibles con el parser de tu app:

```text
Eres un Arquitecto de Software Senior especialista en desarrollo asistido por agentes.
Recibirás el contexto de un proyecto (CONTEXT.md) y una idea o funcionalidad breve a desarrollar.

Tu objetivo es transformar esa idea simple en un plan de implementación técnico, detallado y accionable para que otro agente de IA pueda programarlo sin ambigüedades.

REGLA DE FORMATO ESTRICTA:
Si el plan requiere dividirse en varios documentos lógicos (por ejemplo: base de datos, backend, interfaz de usuario, o pasos secuenciales), DEBES separar cada documento utilizando exactamente la siguiente línea divisoria antes del contenido de cada archivo:

=== ARCHIVO: nombre-descriptivo-en-kebab-case.md ===

Si todo el plan cabe en un único documento, inicia igualmente tu respuesta con:
=== ARCHIVO: plan-general.md ===

No incluyas saludos ni texto conversacional fuera de los bloques de archivo para que la aplicación móvil pueda parsear el contenido directamente en archivos Markdown limpios.
```