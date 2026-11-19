# RYSTeams

Замена FTB Teams в семье RYS: команда как общий контекст, без обязательных команд в чате. modid `rysteams`, пакет `works.repka.rysteams`, NeoForge 1.21.1. Автор Дмитрий (MityaYS, RepkaWorks).

Концепт и макеты: `../RYScope/rysteams/` (`docs/01-concept.md`, `docs/02-design.md`, `design/*.dc.html`). Вид и тон семьи: `../RYSCore/CLAUDE.md`, прочитать перед правками интерфейса.

## Как работать с автором

* Общаться только по-русски. Английский допустим в коде и в текстах для игроков, если автор просит.
* Прогресс показывать списком сверху, сделанное помечать ✅, без зачёркиваний.
* Если нет уверенности в механике, не выдумывать, а проверить (CFR, логи, тест в отдельном новом мире).
* Файлы на ПК не удалять, ненужное переносить в `_stale`.
* Перед новыми текстами для игроков показывать черновик.

## Устройство

* `data.Team`: команда в NBT, один формат и для сохранения, и для клиента.
* `server.TeamService`: вся логика, права проверяются здесь, клиенту не верим.
* `server.TeamStore`: файл мира `data/rysteams.dat`, поле `v` это версия формата.
* `network.ModNetwork`: два канала, состояние клиенту и действия серверу.
* `client.TeamScreen`: экран рисуется целиком каждый кадр и записывает кнопки, клик ищет запись под курсором.
* Контракт для других модов лежит в коре: `works.repka.ryscore.team.TeamsApi`. Прямых вызовов между модами семьи нет.
* Права чужих модов (`TeamsApi.registerPermission`, кор 1.11) хранятся в `Team.modPermissions` только после правки лидером, иначе берутся значения мода из реестра ядра (`Team.modRoles`); строки стоят в таблице прав на экране после своих, столбец лидера закреплён.
* Переезд с FTB Teams только шагом `teams` ядра (`server.TeamsMigration` поверх `server.FtbImport.merge`, `docs/decisions.md` ADR-1): своё не затирается, файлы FTB читает `migrate.Snbt` ядра.
* Карта семьи (Т2, `client.TeamMap`, ADR-2): слой «Команда» (пинги, метки, «чем занят»), источник подписей `world.Labels` (при `Labels.present()` `TeamHud` свои подписи в мире не рисует), пункты меню `rysmap:ping`, `rysteams:ping_<вид>`, `rysteams:mark`.
* События `onRoleChanged`, `onAlliesChanged`, `onRenamed` считает `server.ApiEvents` сверкой со снимком команд (при рассылке и раз в секунду), а не вызовами в каждом месте правки.

## Принятые отступления от концепта

* Экран на G: T в игре открывает чат. Пинг на V: средняя кнопка занята выбором блока.
* Состояние команды уходит целиком при изменении, диффами идёт только присутствие: состав и лента маленькие.

* Адаптера API FTB Teams нет: у FTB Teams лицензия All Rights Reserved. Пока FTB Chunks и FTB Quests требуют его, он остаётся рядом, из него только импорт данных.
* Маркеры на картах Xaero и JourneyMap отложены: в RepYS этих карт нет, проверить негде.

## Проверка

```bash
gradlew runAutotest
gradlew test                              # JUnit, в том числе шаг teams на fixtures
gradlew runAutotest -Pautotest=migrate    # шаг teams в игре на fixtures
```

Настоящий сервер и два клиента (сценарий `DuoTest`, вердикты в `run-duo/*/logs/latest.log`):

```bash
sh tools/duo.sh
```

Папка `run-duo/server` должна содержать `eula.txt`, `server.properties` с `online-mode=false` и `server-port=25577`, `ops.json` с игроком Alpha.

Сборка требует соседний `../RYSCore` с выполненным `gradlew publish`. Сценарий сам создаёт новый мир, вердикт в логе: строка `[autotest] PASS`, скриншоты в `run/screenshots`.
