# Карта проекта RYSTeams

Собрана `_shared/tools/make-map.py` 2026-11-18. Руками не править: после структурных правок пересобрать.

Java: 22 файлов, 6933 строк. Переводы: `src/main/resources/assets/rysteams/lang/en_us.json`, `src/main/resources/assets/rysteams/lang/ru_ru.json`.
Читать сначала: `CLAUDE.md`, `README.md`.

## Точки входа
- `@Mod`: `works.repka.rysteams.RYSTeams` (`src/main/java`)

## Папки
- `docs/` — 2 файлов
- `gradle/` — 2 файлов
- `src/` — 27 файлов
- `tools/` — 1 файлов

## `src/autotest/java` — пакет `works.repka.rysteams.dev`

**(корень)** (2)
- `DuoTest` 213 — Настоящий сервер и два настоящих клиента: приглашение, клавиша Y, права, присутствие, чат.
- `TeamsAutoTest` 543 — Проверка в игре: создаёт мир, наполняет команду, снимает экраны.

## `src/main/java` — пакет `works.repka.rysteams`

**(корень)** (2)
- `RYSTeams` 207 — Точка входа: общая для клиента и сервера.
- `TeamsConfig` 64 — Настройки сервера: world/serverconfig/rysteams-server.toml.

**api** (2)
- `Permission` 48 — Что разрешено ролям.
- `Role` 18 — Роли команды от старшей к младшей.

**client** (7)
- `AdminScreen` 175 — Экран администратора: все команды, архив распущенных, импорт из FTB Teams.
- `ChatChannels` 74 — Каналы чата: Tab перебирает «общий, команда, союз», сервер различает их по префиксу сообщения.
- `ClientEntry` 155 — Клиентская часть: клавиши, слой поверх мира, сброс при выходе.
- `TeamClient` 240 — Что клиент знает о командах.
- `TeamHud` 387 — Всё, что видно поверх мира: участники, метки и пинги, приглашение, карточка игрока, панель подписей.
- `TeamNotices` 79 — Приглашение и строки команд в общих уведомлениях семьи (RYSCore Notice, макет _audit/notify, вариант A): справа вверху, в колонке тостов игры, поверх окон.
- `TeamScreen` 1439 — Экран команды, размеры из rysteams/design: панель 1200×728 макета, в единицах GUI 600×364.

**data** (1)
- `Team` 369 — Команда.

**network** (1)
- `ModNetwork` 77 — Два канала: сервер шлёт клиенту состояние, клиент серверу действия.

**server** (6)
- `ApiEvents` 65 — События контракта ядра о смене роли, союза и имени (TeamsApi 1.11).
- `FtbImport` 340 — Перенос команд из файлов FTB Teams (world/ftbteams/party/*.snbt).
- `Presence` 122 — Кто где.
- `TeamService` 1758 — Вся логика команд.
- `TeamStore` 92 — Все команды мира.
- `TeamsMigration` 112 — Шаг переезда teams (порядок 10) в реестре ядра: обёртка над merge.

## `src/test/java` — пакет `works.repka.rysteams.server`

**(корень)** (1)
- `TeamsMigrationTest` 356 — Шаг teams на копии файлов FTB сервера RepYS (_shared/_saved/fixtures/ftb-2026-11-18): первый запуск, повтор, сбой записи с продолжением при следующем запуске и живой случай, где ко…
