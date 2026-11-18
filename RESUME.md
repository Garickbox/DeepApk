# RESUME — DeepApk

Ты принял проект. Прочитай всё. Не гадай — только по коду и логам.

## 1. ЧТО ЭТО
Android-чат с DeepSeek на Kotlin + Compose. Пользователь — Garickbox (POCO F, Android 14). Сборка в Termux на Redmi (192.168.1.103), установка на POCO через APK из GitHub Releases.

Фичи: DeepSeek API (stream + thinking + встроенный web search), чат с reasoning, Termux-копирование кода, in-app обновление через GitHub Releases.

## 2. GIT
~~~ (сверяй с фактическим состоянием — git log в репе)
- v3: убрал Tavily → встроенный deepseek_web_search + thinking + flash/v4-pro + in-app update + версия в UI
~~~

## 3. CI
~~~ (сверяй по gh api)
build-N: push в main → assembleDebug → Release с APK DeepApk-build-N.apk
~~~

## 4. КАРТА ФАЙЛОВ (пакет com.garickbox.deepapk)
~~~ (сверяй с фактическим состоянием)
- SettingsStore.kt — AppSettings (apiKey, model, thinkingEnabled, webSearchEnabled, temperature, maxTokens, systemPrompt), SharedPreferences "deepapk_settings"
- DeepSeekApi.kt — StreamEvent (Reasoning/Content/SearchInfo/Error/Done), SSE-парсинг, thinking + tools
- UpdateChecker.kt — GitHub Releases, tag build-N, поиск .apk в assets
- MainActivity.kt — экраны chat/settings
- SettingsScreen.kt — поля, модель flash/v4-pro, temperature, maxTokens, обновление
- ChatScreen.kt — LazyColumn reverse, reasoning свёрнутый, copyForTermux (``` → cat > file << 'EOF')
- ChatStore.kt / Message — история (сверь с кодом)
- app/build.gradle.kts — versionCode/Name из GITHUB_RUN_NUMBER, buildConfig
- .github/workflows/build.yml — Build DeepApk
~~~

## 5. API-НОТЫ DeepSeek
~~~
POST https://api.deepseek.com/chat/completions
SSE: "data: {...}" / "data: [DONE]"
delta.reasoning_content — рассуждения (thinking mode)
tools: [{"type":"deepseek_web_search"},{"type":"deepseek_read_url"}]
usage.server_tool_use.tool_search — число поисков
Модели: deepseek-flash (vision+search), deepseek-v4-pro (мощнее, без vision)
Внимание: в ранних коммитах были deepseek-reasoner/chat — сверяй актуальные имена в SettingsScreen/DeepSeekApi
~~~

## 6. СТЕК
~~~
Kotlin (android plugin), AGP, Gradle wrapper, JDK 17, minSdk 26 / compileSdk 34
Compose BOM 2024.09.03, Material3, activity-compose 1.9.2
OkHttp 4.12.0, kotlinx-coroutines-android 1.8.1
buildConfig = true (VERSION_NAME/VERSION_CODE из GITHUB_RUN_NUMBER)
debug.keystore в app/ (android/androiddebugkey)
~~~

## 7. РЕПОЗИТОРИЙ + СЕКРЕТЫ
~~~
GitHub: https://github.com/Garickbox/DeepApk (public)
Package: com.garickbox.deepapk
Token: ~/.gh_token (40 байт, repo+workflow)
RKN блокирует: api.github.com body >8KB (используй per_page=1), raw.githubusercontent >8KB
wget OK, curl OK
~~~

## 8. TERMUX-ИНСТРУМЕНТЫ
~~~
~/deep_build.sh — локальная сборка со спиннером (Task/err/RAM/log)
~/deep_go.sh <patch.py?> "msg" — чистое → patch → build (DEEP_NO_BUILD=1 skip) → push → watch CI
~/deep_watch_ci.sh — GitHub Actions по SHA, до 25 мин, say/notif/vib
~/test_deepseek.sh <key> — тест API (models + chat с thinking/search)
Логи: ~/gradle_build.log, ~/deep_go.log → буфер обмена
Хелперы: say/notif/toast/vib (~/.jec_notify + фолбэк на termux-api)
~~~
