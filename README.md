# 🧠 DeepApk

**Android-чат с ИИ-агентом, у которого есть руки прямо внутри вашего телефона.**

DeepSeek · Gemini 4 · ProxyAPI · SAF-доступ к проекту · Окно Termux · Навыки `.deepapk` · ГОСТ `.docx`

[![Build](https://github.com/Garickbox/DeepApk/actions/workflows/build.yml/badge.svg)](https://github.com/Garickbox/DeepApk/actions)
[![Release](https://img.shields.io/github/v/release/Garickbox/DeepApk?include_prereleases&color=2563eb)](https://github.com/Garickbox/DeepApk/releases)
[![minSdk](https://img.shields.io/badge/minSdk-26-16a34a)](https://developer.android.com/about/versions/oreo)
[![targetSdk](https://img.shields.io/badge/targetSdk-34-16a34a)](https://developer.android.com/about/versions/14)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7c3aed)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack-Compose-2563eb)](https://developer.android.com/jetpack/compose)

[📥 Скачать последний APK](https://github.com/Garickbox/DeepApk/releases/latest) · [📦 Все релизы](https://github.com/Garickbox/DeepApk/releases) · [🐛 Сообщить об ошибке](https://github.com/Garickbox/DeepApk/issues)

---

## ⚡ Зачем это нужно

Обычный мобильный клиент к LLM умеет только переписываться. **DeepApk работает как карманная среда разработки и автоматизации на устройстве** — без ПК, серверов и облачных IDE:

- подключается к локальной папке вашего проекта через системный **Android SAF (`DocumentsContract`)**;
- показывает **unified diff** до записи и меняет файлы **только после вашего нажатия `✅ ОК`** (с бэкапом и откатом в один тап);
- запускает `bash` и `python3` скрипты в **Termux** из единого окна с живым логом и собирает результат в чат;
- собирает офлайн-документы **ГОСТ `.docx`** и переносит роли между устройствами через пакеты **`.deepapk`**.

---

## ✨ Возможности

### 💬 Чат, модели и OCR
- **Провайдеры и быстрый переключатель:**
  - **DeepSeek API** (`deepseek-flash`, `deepseek-chat`, `deepseek-reasoner` с отдельным блоком `Reasoning`);
  - **Google Gemini** (`gemini-4-hydrogen`, `gemini-4-neon`, `gemini-4-argon`, `gemini-3.1-flash-lite`);
  - **ProxyAPI / свой endpoint** (OpenAI-совместимый URL и модели в одном месте).
- **Вложения и OCR:** прикрепление файлов, текста, фото с камеры или галереи с распознаванием текста через **Google ML Kit** и **Tesseract4Android**.
- **Сессии и роли:** боковой drawer диалогов с поиском, переименованием, встроенными пресетами и самообучением через `<skill_save>`.

### 📂 SAF-доступ к коду проекта
- `<fs_tree/>` — обзор дерева каталогов проекта.
- `<fs_grep/>` — поиск совпадений по файлам через `DocumentsContract`.
- `<fs_read/>` — точечное чтение участков кода до 180 строк.
- `<fs_patch/>` и `<fs_write/>` — предпросмотр **unified diff** в нижней панели правок.
- **Транзакции и надёжность:** автоматический бэкап в `filesDir/fs_backups`, откат в один тап и защита блокирующих SAF-вызовов через `runInterruptible` + таймаут 180с.

### 🔌 Единое окно Termux и маркер
- Кнопка `▶ Termux` на исполняемых блоках (`bash`, `sh`, `python`) и в меню сообщения открывает **единое окно запуска**:
  - переключатель интерпретатора `bash` / `python` и редактирование скрипта перед стартом;
  - живой хвост лога в реальном времени и кнопка **«⏹ Остановить»**;
  - прикрепление вывода прямо в ленту вложений компактным чипсом (`📎 В чат`).
- **Ручной маркер:** если ответ модели пришёл без кодового блока, откройте *«Выбрать текст»*, выделите нужный фрагмент пальцем и нажмите **«▶ В Termux»**.

### 📄 ГОСТ `.docx` офлайн
- Генерация настоящих документов Word (**OOXML ZIP**) из XML-блока `<doc_report>`:
  - титульный лист, оглавление и колонтитулы с автонумерацией страниц (`PAGE`);
  - `autofit`-таблицы и рендеринг `<svg>` в PNG;
  - автосохранение HTML-превью — можно открыть в браузере до скачивания `.docx`.

### 🧩 Навыки `.deepapk` (`deepapk-skill-v1`)
- Портативный формат ролей (JSON + Markdown): импорт из мессенджеров и файлового менеджера через `ACTION_VIEW` / `ACTION_SEND` и экспорт одним нажатием.
- Модель умеет запоминать новые правила вашего проекта на лету через тег `<skill_save>`.

---

## 🚀 Установка и первый запуск

### Вариант 1 — Готовый APK (рекомендуется)
1. Откройте [Releases](https://github.com/Garickbox/DeepApk/releases) и скачайте свежий **`DeepApk-build-N.apk`**.
2. Разрешите установку из неизвестных источников и установите приложение.

### Вариант 2 — Сборка из исходников
Требуется **JDK 17**, **Android SDK (compileSdk / build-tools 34)** и ~4 ГБ RAM:

```bash
git clone https://github.com/Garickbox/DeepApk.git
cd DeepApk
./gradlew assembleDebug
```

Готовый APK появится в `app/build/outputs/apk/debug/app-debug.apk`. Также при каждом push в `main` сборка и тесты запускаются в **GitHub Actions** автоматически.

### Первый запуск
1. Откройте **Настройки** и введите API-ключ (DeepSeek, Gemini или ProxyAPI).
2. Выберите провайдера и модель в верхней панели.
3. Подключите папку проекта через SAF-чип в шапке (при необходимости) и выдайте Termux разрешение `RUN_COMMAND` для запуска скриптов.

---

## 🏗️ Архитектура

| Модуль | Назначение |
|---|---|
| `MainActivity.kt` | Точка входа, светлая/тёмная тема, `ACTION_SEND` и `ACTION_VIEW` для `*.deepapk` |
| `ChatScreen.kt` | Главный экран чата, drawer сессий, единое окно Termux, диалоги и маркер текста |
| `DeepSeekApi.kt` · `ModelCatalog.kt` | Стриминг DeepSeek, Gemini и ProxyAPI поверх OkHttp 4.12 |
| `ProjectFs.kt` · `ChatSafController.kt` | Дерево, поиск, чтение и транзакционные бэкапы через `DocumentsContract` |
| `PatchPreview.kt` · `ChatFsEditsBar.kt` | Unified diff и панель подтверждения правок |
| `TermuxBridge.kt` | Выполнение `bash` / `python3` через `RUN_COMMAND`, PID-отмена и чтение хвоста лога |
| `DocxEngine.kt` | Офлайн-сборка ГОСТ `.docx` (OOXML ZIP) и HTML-превью |
| `MarkdownRenderer.kt` · `UserAttachments.kt` | Рендер Markdown/кода/таблиц и чипсы прикреплённых файлов |
| `SkillPackage.kt` · `PresetStore.kt` | Пакеты `.deepapk`, встроенные роли и автообучение `<skill_save>` |
| `AttachmentHelper.kt` · `OcrCache.kt` | Вложения и распознавание текста (ML Kit + Tesseract4Android) |
| `ChatStore.kt` · `SettingsStore.kt` · `UpdateChecker.kt` | Локальное хранение чатов/настроек и проверка новых релизов |

**Стек:** Kotlin 1.9 · Jetpack Compose (Material 3) · Coroutines 1.8.1 · OkHttp 4.12 · Android SAF · OOXML · ML Kit · Tesseract4Android · `minSdk 26` / `targetSdk 34` · `arm64-v8a`

---

## 🎯 Roadmap

- [x] SAF-доступ к проекту (`tree` / `grep` / `read` / `patch` / `write`)
- [x] Транзакционные правки с бэкапом, diff-превью и откатом в один тап
- [x] Единое окно Termux, живой хвост лога и запуск выделенного маркером фрагмента
- [x] Портативные навыки `.deepapk` и автообучение `<skill_save>`
- [x] Генератор ГОСТ `.docx` с SVG и HTML-превью
- [ ] Дальнейшая декомпозиция UI-слоя чата
- [ ] Поддержка новых провайдеров (Claude, OpenAI)
- [ ] Локальный RAG-поиск по кодовой базе
- [ ] Синхронизация проектов по WebDAV / SSH вне SAF

---

## 📜 Лицензия и обратная связь

- **Релизы:** [github.com/Garickbox/DeepApk/releases](https://github.com/Garickbox/DeepApk/releases)
- **Вопросы и баги:** [github.com/Garickbox/DeepApk/issues](https://github.com/Garickbox/DeepApk/issues)
