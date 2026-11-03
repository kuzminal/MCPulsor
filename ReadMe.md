# MCPulsor

**MCPulsor** — компактный MCP-сервер (Model Context Protocol) на Java, который отдаёт инструменты по HTTP. Сейчас это каркас с одним демонстрационным инструментом `bioSensor`, возвращающим фиксированное значение пульса.

MCP — открытый протокол, через который LLM-приложения (IDE, чат-клиенты, агенты) получают доступ к внешним возможностям: инструментам, ресурсам и промптам. MCPulsor реализует серверную часть протокола и публикует свою возможность `bioSensor` как MCP-инструмент.

Проект удобно использовать как:

- готовый шаблон MCP-сервера на Java;
- песочницу для проверки MCP-клиентов и Inspector;
- основу для подключения реальных источников данных (медицинские датчики, телеметрия, внутренние API).

> **Статус:** учебно-демонстрационный проект. Реальных измерений, аутентификации и тестов пока нет — см. раздел [Ограничения](#ограничения).

---

## Содержание

- [Что умеет](#что-умеет)
- [Требования](#требования)
- [Быстрый старт](#быстрый-старт)
  - [Docker Compose](#docker-compose)
  - [Локальный запуск](#локальный-запуск)
- [Проверка через curl](#проверка-через-curl)
- [Подключение MCP-клиентов](#подключение-mcp-клиентов)
- [Инструмент bioSensor](#инструмент-biosensor)
- [Архитектура](#архитектура)
- [Структура проекта](#структура-проекта)
- [Технологии](#технологии)
- [Сборка](#сборка)
- [Конфигурация](#конфигурация)
- [Разработка: как добавить инструмент](#разработка-как-добавить-инструмент)
- [Тестирование](#тестирование)
- [Ограничения](#ограничения)
- [Идеи для развития](#идеи-для-развития)
- [Диагностика проблем](#диагностика-проблем)
- [Участие в разработке](#участие-в-разработке)
- [Лицензия](#лицензия)

---

## Что умеет

- Поднимает MCP-сервер поверх **Streamable HTTP** на эндпоинте `/mcpulsor`.
- Публикует инструмент `bioSensor` с названием и описанием, понятными LLM-клиенту.
- Поддерживает стандартный жизненный цикл MCP: `initialize` → `notifications/initialized` → `tools/list` → `tools/call`.
- Возвращает ответы в формате JSON-RPC 2.0; в рамках сессии поддерживает SSE-поток.
- Собирается в исполняемый fat-jar и запускается в Docker.
- В комплекте с `docker-compose.yml` поднимается официальный MCP Inspector для ручной проверки инструментов.

Предметных данных сервер не хранит — все ответы зашиты в код. При этом транспорт ведёт MCP-сессии в памяти процесса.

---

## Требования

| Что нужно | Версия | Зачем |
|---|---|---|
| JDK | **25+** | Проект компилируется под Java 25 и использует `java.lang.IO` |
| Maven | 3.9+ | Сборка многомодульного проекта |
| Docker + Compose v2 | актуальная версия | Опционально: контейнерный запуск и Inspector |
| Node.js | актуальная LTS | Опционально: локальный запуск Inspector через `npx` |

Проверка окружения:

```bash
java -version
mvn -version
docker --version
```

> Важно: Maven должен запускаться на JDK 25 или новее. Если `mvn -version` показывает старую Java, задайте `JAVA_HOME` перед сборкой.

---

## Быстрый старт

### Docker Compose

Из корня проекта:

```bash
docker compose up --build
```

Поднимутся два сервиса:

| Сервис | Адрес | Назначение |
|---|---|---|
| `mcpulsor-server` | `http://localhost:8888/mcpulsor` | MCP-сервер (внутри контейнера — порт `8081`) |
| `inspector-http` | `http://localhost:6274` | UI MCP Inspector |

Остановить:

```bash
docker compose down
```

Пересобрать после изменений:

```bash
docker compose up --build --force-recreate
```

> Compose публикует порт `8888` на все интерфейсы. Для локальной разработки безопаснее ограничить его: `"127.0.0.1:8888:8081"`.

### Локальный запуск

Сборка fat-jar:

```bash
mvn clean package
```

Запуск:

```bash
java -jar mcpulsor-server/target/mcpulsor-server-1.0-SNAPSHOT-fat.jar
```

Сервер слушает `http://localhost:8081/mcpulsor`. В логе появится `Server Application Started`.

В IDE запускайте класс `ru.kuzmin.mcpulsor.server.McpulsorServerApplication`.

---

## Проверка через curl

MCP-сессия создаётся на `initialize`, после чего её идентификатор нужно передавать в заголовке `Mcp-Session-Id`.

```bash
BASE=http://localhost:8081/mcpulsor

# 1. Инициализация и получение Mcp-Session-Id
SID=$(curl -sS -D - -o /dev/null -X POST "$BASE" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":"2025-06-18",
        "capabilities":{},
        "clientInfo":{"name":"curl","version":"1.0"}}}' \
  | awk -F': ' 'tolower($1)=="mcp-session-id"{print $2}' | tr -d '\r')

echo "Session: $SID"

# 2. Подтверждение инициализации
curl -sS -X POST "$BASE" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# 3. Список инструментов
curl -sS -X POST "$BASE" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# 4. Вызов инструмента
curl -sS -X POST "$BASE" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
        "name":"bioSensor","arguments":{}}}'
```

Ожидаемый результат вызова:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{"type": "text", "text": "Your pulse rate is 72 bpm."}],
    "isError": false
  }
}
```

Ответы на запросы после инициализации могут приходить в формате SSE (`event: message`, строка `data: ...`), а не чистым JSON. Это нормальное поведение Streamable HTTP транспорта: формат согласуется через заголовок `Accept`.

---

## Подключение MCP-клиентов

Общие параметры подключения:

| Параметр | Значение |
|---|---|
| Transport | Streamable HTTP |
| URL (локальный запуск) | `http://localhost:8081/mcpulsor` |
| URL (Docker Compose) | `http://localhost:8888/mcpulsor` |

Пример для Claude Code:

```bash
claude mcp add --transport http mcpulsor http://localhost:8888/mcpulsor
```

Пример JSON-конфигурации (синтаксис зависит от клиента — Cursor, VS Code, Claude Desktop и др.):

```json
{
  "mcpServers": {
    "mcpulsor": {
      "type": "http",
      "url": "http://localhost:8888/mcpulsor"
    }
  }
}
```

### MCP Inspector

**Вариант 1 — Inspector в Docker (уже есть в compose):**

1. Откройте `http://localhost:6274`.
2. Transport: `Streamable HTTP`.
3. URL: `http://mcpulsor-server:8081/mcpulsor` — Inspector и сервер находятся в одной Docker-сети, поэтому имя сервиса резолвится напрямую.
4. Нажмите Connect, затем откройте вкладку Tools и вызовите `bioSensor`.

Если обращаетесь через опубликованный порт хоста, используйте `http://host.docker.internal:8888/mcpulsor`: Inspector выполняет запрос изнутри своего контейнера, где `localhost` — это сам контейнер. На macOS и Windows `host.docker.internal` указывает на хост. На Linux добавьте в сервис `inspector-http`:

```yaml
extra_hosts:
  - "host.docker.internal:host-gateway"
```

**Вариант 2 — Inspector локально:**

```bash
npx @modelcontextprotocol/inspector
```

UI откроется на `http://localhost:6274`. Укажите Transport `Streamable HTTP` и URL `http://localhost:8081/mcpulsor` (или `8888`, если сервер запущен через compose).

---

## Инструмент bioSensor

| Поле | Значение |
|---|---|
| Имя | `bioSensor` |
| Заголовок | Human Vital Pulse Sensor |
| Описание | This sensor measures the pulse rate of a human being. |
| Входные аргументы | Нет, схема — пустой объект `{"type":"object"}` |
| Результат | Текст `Your pulse rate is 72 bpm.`, `isError: false` |

Это заглушка: значение `72` захардкожено в обработчике. Инструмент нужен, чтобы проверить сквозной сценарий «клиент → MCP → инструмент → ответ», а не для реальных измерений.

---

## Архитектура

```
MCP-клиент (Inspector / Claude / IDE)
        │  HTTP POST /mcpulsor, JSON-RPC 2.0
        ▼
Jetty 11 (embedded, порт 8081)
        │
        ▼
HttpServletStreamableServerTransportProvider
        │  разбор JSON-RPC, сессии, SSE
        ▼
MCP Java SDK: McpServer.sync(...)
        │
        ▼
SyncToolSpecification(bioSensor) → CallToolResult
```

Компоненты:

| Компонент | Роль |
|---|---|
| `McpulsorServerApplication` | Точка входа: создаёт транспорт, инструмент, MCP-сервер и Jetty |
| `HttpServletStreamableServerTransportProvider` | MCP-транспорт поверх Servlet API, эндпоинт `/mcpulsor` |
| `McpServer.sync(...)` | Синхронный MCP-сервер: capabilities, serverInfo, инструменты |
| `McpSchema.Tool` | Описание инструмента для клиента (имя, заголовок, схема входа) |
| `SyncToolSpecification` | Связка «инструмент + обработчик вызова» |
| Jetty `Server` + `ServletContextHandler` | HTTP-контейнер, в который смонтирован MCP-транспорт |

Порядок обработки запроса:

1. Jetty принимает HTTP-запрос на `/mcpulsor`.
2. Транспорт разбирает JSON-RPC и определяет метод.
3. `initialize` создаёт сессию и возвращает `Mcp-Session-Id`.
4. `tools/list` отдаёт описание зарегистрированных инструментов.
5. `tools/call` находит `SyncToolSpecification` по имени и вызывает обработчик.
6. Результат сериализуется в `CallToolResult` и уходит клиенту (JSON или SSE).

---

## Структура проекта

```
MCPulsor/
├── pom.xml                          # parent-pom, модули, Java 25
├── docker-compose.yml               # сервер + MCP Inspector
├── ReadMe.md
└── mcpulsor-server/
    ├── Dockerfile                   # multi-stage: Maven build → JRE runtime
    ├── pom.xml                      # зависимости MCP SDK, Jetty, Jackson 3
    └── src/main/java/ru/kuzmin/mcpulsor/server/
        └── McpulsorServerApplication.java
```

Тестовых исходников сейчас нет. Каталог `target/` — результат сборки, в git не попадает.

---

## Технологии

| Технология | Версия | Комментарий |
|---|---|---|
| Java | 25 | `maven.compiler.source/target = 25`, используется `IO.println` |
| Maven | 3.9+ | Parent + модуль `mcpulsor-server` |
| MCP Java SDK | 2.1.0 | Артефакт `io.modelcontextprotocol.sdk:mcp` |
| Jetty | 11.0.26 | Embedded-сервер, `jetty-server` + `jetty-servlet` |
| Jakarta Servlet API | 6.1.0 | Compile-scope |
| Jackson | 3.1.4 | Пакет `tools.jackson` (Jackson 3), интеграция `mcp-json-jackson3` |
| Lombok | 1.18.48 | Объявлен, но в текущем коде не используется |
| maven-shade-plugin | 3.5.1 | Сборка fat-jar с классификатором `fat` |
| Docker | multi-stage | `maven:3-eclipse-temurin-25` → `eclipse-temurin:25-jre-alpine` |

Особенности стека:

- MCP SDK 2.x использует **Jackson 3** (`tools.jackson.*`), поэтому в коде импортируется `tools.jackson.databind.json.JsonMapper`, а не привычный `com.fasterxml.jackson.*`.
- Jetty 11 реализует Servlet 5.0, при этом в зависимостях объявлен Servlet API 6.1. В embedded-режиме это работает, но при обновлении Jetty стоит выровнять версии (Jetty 12+ для Servlet 6.x).
- `maven.compiler.source/target` лучше заменить на `maven.compiler.release`, чтобы сборка проверяла соответствие JDK.

---

## Сборка

Все команды выполняются из корня проекта.

```bash
# Полная сборка (thin + fat jar)
mvn clean package

# Только модуль сервера
mvn -pl mcpulsor-server clean package

# Без тестов (сейчас тестов нет, но флаг полезен в CI)
mvn -pl mcpulsor-server clean package -DskipTests
```

Результат в `mcpulsor-server/target/`:

| Файл | Назначение |
|---|---|
| `mcpulsor-server-1.0-SNAPSHOT.jar` | Обычный jar без зависимостей |
| `mcpulsor-server-1.0-SNAPSHOT-fat.jar` | Исполняемый jar со всеми зависимостями |

Запуск fat-jar:

```bash
java -jar mcpulsor-server/target/mcpulsor-server-1.0-SNAPSHOT-fat.jar
```

Сборка Docker-образа:

```bash
docker build -f mcpulsor-server/Dockerfile -t mcpulsor-server .
```

Контекст сборки — **корень проекта**, потому что Dockerfile копирует и parent `pom.xml`, и модуль.

---

## Конфигурация

Отдельного файла конфигурации нет: параметры заданы в коде `McpulsorServerApplication`.

| Параметр | Значение | Где меняется |
|---|---|---|
| HTTP-порт | `8081` | `new Server(8081)` |
| MCP-эндпоинт | `/mcpulsor` | `.mcpEndpoint("/mcpulsor")` |
| Имя сервера | `Mcpulsor MCP Server` | `.serverInfo(...)` |
| Версия сервера | `1.0.1` | `.serverInfo(...)` |
| Имя инструмента | `bioSensor` | `McpSchema.Tool.builder("bioSensor", ...)` |
| Значение пульса | `72 bpm` | Текст в `callHandler` |
| Порт в контейнере | `8081` | `EXPOSE 8081` в Dockerfile |
| Порт на хосте | `8888` | `docker-compose.yml` |

Если меняете порт приложения, синхронно обновите:

1. `EXPOSE` в `mcpulsor-server/Dockerfile`;
2. маппинг портов в `docker-compose.yml`;
3. URL в клиентах и примерах из этого README.

Для дальнейшего развития параметры стоит вынести в переменные окружения (`MCPULSOR_PORT`, `MCPULSOR_ENDPOINT` и т.п.) или в `application.properties`.

---

## Разработка: как добавить инструмент

### 1. Опишите инструмент и его входную схему

Схема — обычный JSON Schema. Она нужна клиенту, чтобы понимать аргументы вызова. Понадобятся импорты `McpSchema`, `JacksonMcpJsonMapper` и `tools.jackson.databind.json.JsonMapper`.

```java
McpSchema.Tool pulseHistoryTool = McpSchema.Tool.builder(
        "pulseHistory",
        new JacksonMcpJsonMapper(new JsonMapper()),
        """
        {
          "type": "object",
          "properties": {
            "minutes": {
              "type": "integer",
              "minimum": 1,
              "maximum": 1440,
              "description": "Depth of history in minutes"
            }
          },
          "required": ["minutes"]
        }
        """)
    .title("Pulse History")
    .description("Returns pulse measurements for the last N minutes.")
    .build();
```

### 2. Добавьте обработчик

Обработчик получает `McpSyncServerExchange` и `CallToolRequest`. Аргументы доступны через `request.arguments()`.

```java
McpServerFeatures.SyncToolSpecification pulseHistorySpec =
        McpServerFeatures.SyncToolSpecification.builder()
            .tool(pulseHistoryTool)
            .callHandler((exchange, request) -> {
                var args = request.arguments();
                Object raw = args == null ? null : args.get("minutes");
                int minutes = raw instanceof Number n ? n.intValue() : 15;

                return McpSchema.CallToolResult.builder()
                        .addTextContent("Pulse history for " + minutes + " min: 72 bpm (stub).")
                        .isError(false)
                        .build();
            })
            .build();
```

Для ошибок бизнес-логики возвращайте результат с `isError(true)` и понятным текстом, а не бросайте исключение — так клиент корректно покажет проблему пользователю.

### 3. Зарегистрируйте инструмент

```java
McpServer.sync(transportProvider)
        .serverInfo("Mcpulsor MCP Server", "1.0.1")
        .capabilities(createServerCapabilities())
        .tools(bioSensorToolSpec, pulseHistorySpec)
        .build();
```

Альтернативный короткий вариант — сразу связать инструмент и обработчик:

```java
.toolCall(pulseHistoryTool, (exchange, request) -> { /* ... */ })
```

Но для нескольких инструментов удобнее явные `SyncToolSpecification` и единая точка регистрации.

### 4. Проверьте

```bash
mvn -pl mcpulsor-server clean package
java -jar mcpulsor-server/target/mcpulsor-server-1.0-SNAPSHOT-fat.jar
```

Затем в Inspector выполните `tools/list` — новый инструмент должен появиться в списке — и вызовите его с валидными и невалидными аргументами.

### Соглашения проекта

- Имена инструментов — `camelCase`, стабильные и без пробелов: клиенты используют их как идентификаторы.
- Описание инструмента должно объяснять, **что** он делает и **когда** его вызывать.
- Схема входа обязательна, даже если аргументов нет: `{"type":"object"}`.
- Один инструмент — одна законченная операция; не смешивайте чтение и изменение состояния.
- Не возвращайте клиенту stack trace и внутренние пути.
- Все новые публичные сценарии фиксируйте в этом README.

---

## Тестирование

Сейчас в проекте нет тестов: исходный `AppTest` был удалён при переходе на MCP-сервер.

Рекомендуемая схема:

1. **Юнит-тесты обработчиков** — вынести логику инструмента в отдельный метод или сервис и проверять его без MCP-транспорта.
2. **Интеграционные тесты** — поднимать `HttpServletStreamableServerTransportProvider` на свободном порту и проходить цикл `initialize → tools/list → tools/call` MCP-клиентом из SDK.
3. **Контрактные проверки** — валидировать JSON Schema инструментов и ответы на невалидные аргументы.

Минимальные зависимости для тестов: JUnit 5 и MCP-клиент из того же SDK. Запуск:

```bash
mvn test
```

Ручная проверка описана в разделе [Проверка через curl](#проверка-через-curl) и через MCP Inspector.

---

## Ограничения

- **Данные-заглушки.** `bioSensor` всегда возвращает `72 bpm`; реального датчика или API нет.
- **Нет аутентификации и TLS.** Эндпоинт открыт всем, кто до него достучится. Не публикуйте сервер в интернет без reverse-proxy с авторизацией.
- **Нет CORS-политики.** Для браузерных MCP-клиентов может потребоваться настройка.
- **Сессии в памяти.** Перезапуск процесса теряет сессии; горизонтальное масштабирование без sticky sessions не поддерживается.
- **Порт и эндпоинт захардкожены.** Конфигурация не вынесена наружу.
- **Нет тестов и CI.** Регрессии пока ловятся только вручную.
- **Нет health-check и метрик.** Compose не умеет проверять готовность сервера.
- **Один инструмент и только tools.** Ресурсы, промпты, сэмплинг и elicitation не реализованы.
- **Lombok объявлен, но не используется.** Можно удалить зависимость или начать применять осознанно.
- **Нет graceful shutdown.** Jetty останавливается вместе с процессом; `McpSyncServer.closeGracefully()` не вызывается.

---

## Идеи для развития

- Подключить реальный источник пульса (BLE-датчик, HTTP API, MQTT, база данных).
- Добавить инструменты истории измерений, статистики и пороговых уведомлений.
- Ввести ресурсы (например, `pulse://patients/{id}`) и промпты для типовых сценариев.
- Вынести конфигурацию в переменные окружения или YAML.
- Добавить аутентификацию (Bearer token / OAuth 2.1) и TLS-терминацию.
- Реализовать health-check, structured logging и метрики.
- Покрыть обработчики и транспорт тестами, добавить GitHub Actions.
- Собрать multi-arch Docker-образ и опубликовать в registry.
- Добавить `LICENSE` и `CONTRIBUTING.md`.

---

## Диагностика проблем

| Симптом | Вероятная причина | Решение |
|---|---|---|
| `invalid target release: 25` | Maven запущен на JDK < 25 | Установите JDK 25 и `export JAVA_HOME=...` |
| `NoClassDefFoundError: io/modelcontextprotocol/...` | Запущен thin-jar без classpath | Используйте `*-fat.jar` или соберите classpath |
| `Connection refused` на `:8081` | Сервер не запущен или упал | Проверьте лог, порт и `java -version` |
| `404` на `/mcpulsor` | Неверный путь | Убедитесь, что URL заканчивается на `/mcpulsor` |
| `400` при `tools/call` без сессии | Пропущен `initialize` | Сначала создайте сессию и передавайте `Mcp-Session-Id` |
| Inspector в Docker не видит сервер | `localhost` внутри контейнера | Используйте `host.docker.internal`, см. раздел про Inspector |
| `Port 8081 is already in use` | Порт занят другим процессом | Остановите процесс или измените порт |
| Docker build не находит parent pom | Запуск не из корня проекта | Запускайте `docker build` из корня проекта |
| Клиент не показывает инструмент | Не выполнен `initialize`/`tools/list` | Переподключитесь и проверьте лог сервера |

---

## Участие в разработке

1. Создайте ветку от `main`.
2. Внесите изменение и проверьте сборку: `mvn clean package`.
3. Обновите README, если изменились поведение, конфигурация или контракт инструментов.
4. Опишите в pull request: что изменено, зачем и как проверялось.

Стиль кода: Java 25, 4 пробела, без звёздочных импортов, имена классов — `PascalCase`, методов и переменных — `camelCase`. Публичные контракты инструментов (имя, схема, формат ответа) считаются API и меняются осознанно.

---

## Лицензия

Файл лицензии в репозитории отсутствует. До его добавления условия использования и распространения кода не определены — уточните их у владельца проекта.
