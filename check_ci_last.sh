#!/data/data/com.termux/files/usr/bin/bash
set -e

source ~/.jec_notify 2>/dev/null || true
REPO="Garickbox/DeepApk"
TOKEN=$(cat ~/.gh_token | tr -d '\n\r ')

echo "🔍 Проверяем последний раннер для $REPO..."

# Получаем последний раннер
RUN_DATA=$(curl -s -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$REPO/actions/runs?per_page=1")
RUN_ID=$(echo "$RUN_DATA" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('workflow_runs',[{}])[0].get('id') if d.get('workflow_runs') else '')")

if [ -z "$RUN_ID" ]; then
  say "❌ Нет раннеров"
  exit 1
fi

RUN_URL="https://github.com/$REPO/actions/runs/$RUN_ID"
echo "👉 Раннер: $RUN_URL"

# Статус раннера
RUN_INFO=$(curl -s -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID")
STATUS=$(echo "$RUN_INFO" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('status'))")
CONCLUSION=$(echo "$RUN_INFO" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('conclusion'))")

echo "ℹ️ status=$STATUS | conclusion=$CONCLUSION"

if [ "$STATUS" = "completed" ]; then
  if [ "$CONCLUSION" = "success" ]; then
    say "✅ Сборка OK"
    notif "✅ CI Success" "Build passed" 1000
    vib 200
  else
    say "❌ Сборка упала"
    notif "❌ CI Failure" "Check logs" 1000
    vib 500

    # Ищем первый упавший job
    JOBS_URL=$(echo "$RUN_INFO" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('jobs_url'))")
    JOBS=$(curl -s -H "Authorization: Bearer $TOKEN" "$JOBS_URL")
    FAILED_JOB=$(echo "$JOBS" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for j in d.get('jobs', []):
  if j.get('conclusion') == 'failure':
    print(f\"{j.get('name')}|{j.get('html_url')}\")
    break
")

    if [ -n "$FAILED_JOB" ]; then
      JOB_NAME=$(echo "$FAILED_JOB" | cut -d'|' -f1)
      JOB_URL=$(echo "$FAILED_JOB" | cut -d'|' -f2)
      echo "❌ Упал job: $JOB_NAME"
      echo "👉 Лог: $JOB_URL"

      # Получаем ID job из URL
      JOB_ID=$(echo "$JOB_URL" | python3 -c "import sys,re; u=sys.stdin.read(); m=re.search(r'/jobs/(\d+)',u); print(m.group(1) if m else '')")
      if [ -n "$JOB_ID" ]; then
        # Скачиваем логи и ищем ключевые строки ошибки
        LOGS=$(curl -s -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$REPO/actions/jobs/$JOB_ID/logs")
        echo "--- фрагмент ошибки ---"
        # Ищем блок с ошибкой: строки с FAILURE, Execution failed, Caused by
        echo "$LOGS" | grep -E "FAILURE|Execution failed|Caused by" -A 3 -B 3 | head -n 20
        echo "--- конец фрагмента ---"
      fi
    else
      echo "⚠️ Не удалось найти упавший job — смотри вручную"
    fi
  fi
else
  say "⏳ Сборка ещё идёт"
  notif "⏳ CI Running" "Status: $STATUS" 1000
fi
