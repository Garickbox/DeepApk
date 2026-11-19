# 🧠 DeepApk

**Android-чат с агентом, у которого есть руки внутри твоего телефона.**

DeepSeek / Gemini · SAF-доступ к коду проекта · Termux-мост · .deepapk скиллы · ГОСТ .docx

[![Build](https://github.com/Garickbox/DeepApk/actions/workflows/build.yml/badge.svg)](https://github.com/Garickbox/DeepApk/actions)
[![Release](https://img.shields.io/github/v/release/Garickbox/DeepApk?include_prereleases&color=blue)](https://github.com/Garickbox/DeepApk/releases)
[![minSdk](https://img.shields.io/badge/minSdk-26-green)](https://developer.android.com/about/versions/oreo)
[![targetSdk](https://img.shields.io/badge/targetSdk-34-green)](https://developer.android.com/about/versions/14)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-purple)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack-Compose-blueviolet)](https://developer.android.com/jetpack/compose)

---

## ⚡ Что это

Обычный чат-клиент к API LLM — не интересно. **DeepApk — другое.** У ассистента есть **реальный доступ к папке проекта через SAF**, **мост в Termux** (запуск bash/python одной кнопкой) и **собственный формат навыков .deepapk**, который учит модель работать под твой проект.

Ты пишешь: «найди, где вызывается startStream» — модель отвечает XML-тегом, приложение само читает файлы из SAF, отдаёт результат обратно в модель, и та даёт точечный fs_patch с правкой. **Ты нажимаешь ✅ ОК — только тогда файл меняется.** Никакой магии, никаких «модель сама записала».

Всё работает **на телефоне**, без ПК и сервера.

## ✨ Возможности

### 💬 Чат

- **Стриминг** от DeepSeek (deepseek-chat, deepseek-reasoner) и Gemini (gemini-1.5-pro, gemini-1.5-flash, gemini-2.0-flash) с переключением одним тапом
- **Reasoning** отдельным блоком (deepseek-reasoner)
- **Множественные сессии** с drawer'ом, переименованием, удалением
- **Роли (presets)** — default, termux, docx_report, deepapk_dev — встроенные .md заготовки, правятся и сохраняются локально
- **Auto-learn** через skill_save — модель по ходу работы обновляет свой preset
- **Attachments** — файлы, текст, изображения

### 📂 SAF-доступ к проекту

- **fs_tree** — дерево файлов (рекурсивно, с фильтрами)
- **fs_grep** — grep по коду проекта через DocumentsContract
- **fs_read** — точечное чтение до 180 строк
- **fs_patch / fs_write** — правка файлов с **дифф-превью** и **подтверждением пользователя**
- **Транзакции** — applyWithBackup → commitBackupOk / rollbackBackup. Каждая правка бэкапится в filesDir/fs_backups, откат в один тап
- **runInterruptible + withTimeoutOrNull(180s)** — блокирующие Binder-вызовы SAF прерываются по таймауту
- **P4-lite логирование** — каждая ошибка SAF (SecurityException / IOException / IllegalArgumentException) попадает в logcat с именем файла и docId

### 🔌 Termux-мост

- **▶ Termux** на любом code-блоке — запуск bash/python через RUN_COMMAND
- **Inline fallback** — если MediaStore.insert не сработал, код летит в RUN_COMMAND -c напрямую

### 📄 ГОСТ .docx

- Модель отвечает XML-блоком doc_report — приложение собирает **настоящий .docx** (OOXML ZIP) по **ГОСТ**
- Титульный лист, оглавление, колонтитулы с PAGE, autofit-таблицы, SVG → PNG
- HTML-превью автосохраняется — можно открыть в браузере до скачивания
- Кнопка **📥 Скачать .docx** в каждом сообщении с документом

### 🎨 Markdown

- Блоки кода с копированием, подсветкой, кнопками **▶ Termux** и **🌐 Открыть**
- Таблицы, списки, цитаты, заголовки
- Сворачивание прикреплённых файлов (`[Файл: ...]`) в компактные chip'ы — полный текст идёт в API

### 🧩 .deepapk — свои навыки

- **Формат:** deepapk-skill-v1 — JSON + Markdown, ZIP-контейнер
- **Импорт:** ACTION_VIEW (*.deepapk), ACTION_SEND, пункт **📂 Из файла** в меню
- **Экспорт:** **📤 .deepapk** — сохранить текущий preset с накопленными правилами
- **Встроенные** в assets/skills/: default, termux, docx_report, deepapk_dev
- **skill_save** — модель учится на ходу: сохраняет правило в активный пресет, оно подставляется в системный промт следующего сообщения

## 🚀 Установка

### Готовый APK (рекомендуется)

1. Открой [Releases](https://github.com/Garickbox/DeepApk/releases)
2. Скачай последний DeepApk-build-N.apk
3. Разреши установку из неизвестных источников → установи

### Сборка из исходников

Требуется JDK 17, Android SDK (build-tools 34), ~4 ГБ RAM.

Команды:

- `git clone https://github.com/Garickbox/DeepApk.git`
- `cd DeepApk`
- `./gradlew assembleDebug`

Готовый APK появится в `app/build/outputs/apk/debug/app-debug.apk`.

Альтернатива — **CI собирает APK автоматически** при каждом push в main (см. .github/workflows/build.yml). Артефакт доступен во вкладке Actions.

### Первый запуск

1. Задай **API-ключ** DeepSeek или Gemini в Настройках
2. Выбери модель (чип DS / ✦ Gemini в шапке)
3. Подключи **SAF-папку проекта** — чип в TopBar → выбери папку с репозиторием
4. Готово: модель видит структуру и может предлагать правки

## 🏗️ Архитектура

- **MainActivity.kt** — тема, роутинг chat/settings, ACTION_SEND / ACTION_VIEW *.deepapk
- **ChatScreen.kt** — главный экран: лента, drawer, TopBar, старт стрима
- **ChatSafController.kt** — SAF-цикл (Success / Error / Timeout), таймаут, retry
- **ChatFsEditsBar.kt** — нижний бар правок + раскрывающийся diff
- **ProjectFs.kt** — SAF через DocumentsContract: tree / grep / read / write / backup
- **PatchPreview.kt** — unified diff из fs_patch / fs_write (до применения)
- **SkillPackage.kt** — формат .deepapk (импорт / экспорт)
- **PresetStore.kt** — встроенные + пользовательские скиллы
- **DocxEngine.kt** — ГОСТ .docx (OOXML, autofit, PAGE)
- **MarkdownRenderer.kt** — рендер Markdown/кода/таблиц/doc_report
- **DeepSeekApi.kt** — DeepSeek + Gemini клиенты (OkHttp, стриминг)
- **ChatStore.kt** — сессии в JSON
- **SettingsStore.kt** — настройки в SharedPreferences
- **SettingsScreen.kt / SettingsDialogs.kt** — экран настроек и диалоги
- **ModelCatalog.kt** — каталог моделей
- **AttachmentHelper.kt** — прикрепление файлов
- **TermuxBridge.kt** — RUN_COMMAND мост (файл + inline fallback)
- **UpdateChecker.kt** — проверка обновлений по GitHub API

**Стек:** Kotlin · Jetpack Compose (Material 3) · Coroutines · OkHttp 4.12 · Storage Access Framework · OOXML.

## 🧪 Скрипты автора (локальные, вне репозитория)

**Важно:** эти скрипты не входят в репозиторий — это личная обвязка автора в его Termux. У вас их не будет, а `bash ~/deep_build.sh` упадёт с `No such file`. Если нужны — пишите свои аналоги, в проекте их нет.

- **~/deep_build.sh** — авторская сборка (deepapk_guard.py + gradle + aapt2). Эквивалент: `./gradlew assembleDebug`
- **~/deepapk_guard.py** — проверка Log.w / try-catch перед сборкой
- **~/deep_go.sh "msg"** — сборка + commit + push + CI-watch
- **~/aile_dl.sh** — ожидание CI + скачивание APK в apk/
- **~/deep_watch.sh** — ожидание CI (wget — обход РКН >8 КБ)
- **~/deep_install.sh** — установка APK через ADB
- **~/deep_err.sh** — дамп ошибок CI в log.txt

Для публичного использования — только `./gradlew assembleDebug` и вкладка Actions.

## 🎯 Roadmap

- [x] SAF-доступ к проекту (tree / grep / read / patch / write)
- [x] Транзакционные правки с бэкапом и откатом
- [x] Diff-preview перед применением
- [x] Termux-мост с inline fallback
- [x] .deepapk скиллы (импорт / экспорт / auto-learn)
- [x] ГОСТ .docx
- [ ] P6 — разгрузка ChatScreen.kt (фазы 2–3)
- [ ] Multi-model — Claude, OpenAI
- [ ] RAG-память — эмбеддинги по проекту
- [ ] Smoke-тесты для ProjectFs
- [ ] Плагины — .deepapk с исполняемым кодом
- [ ] WebDAV/SSH — синк проекта вне SAF

## 🤝 Участие

- **Kotlin**: не пиши юникод-эскейпы в строках — только живой UTF-8 эмодзи или суррогатные пары
- **AndroidManifest**: pathSuffix требует android:host="*"
- **Compose**: не ломай imePadding / navigationBarsPadding, не пиши continue внутри forEach
- **SAF**: блокирующие вызовы — только через runInterruptible(Dispatchers.IO) + withTimeoutOrNull
- **Файлы**: всё временное — в ~/deep_tmp, никакого git add -A

## 📜 Лицензия

MIT — в ближайшее время.

## 💬 Контакты

**Разработчик:** Garickbox
**Issues:** https://github.com/Garickbox/DeepApk/issues
**Releases:** https://github.com/Garickbox/DeepApk/releases

---

**Готово к бою.** 🔥

*DeepApk — не «ещё один клиент к API». Это агент, который живёт в твоём телефоне и умеет править твой код.*
