#!/data/data/com.termux/files/usr/bin/bash
source ~/.jec_notify 2>/dev/null || true
cd ~/DeepApk || { say "Нет проекта DeepApk"; exit 1; }

LOG=~/deepapk_run.log
PATCH="$1"
MSG="${2:-auto}"

[ -z "$PATCH" ] && { echo "usage: deepapk_go.sh <patch.py> \"msg\""; exit 1; }
[ ! -f "$PATCH" ] && { say "Нет файла патча"; exit 1; }

say "Патч начат"
notif "🚀 Patch start" "$MSG" 1000

{
  echo "═══ 0. чистое ═══"
  git checkout -- app/src/main/java 2>/dev/null || true
  git status --short

  echo "═══ 1. patch ═══"
  toast "1/5 patch"
  if ! python "$PATCH"; then
    git checkout -- app/src/main/java 2>/dev/null
    say "Ошибка патча"
    notif "❌ Patch FAIL" "$MSG" 1000
    vib 500; exit 1
  fi
  vib 100; toast "✅ patch"

  echo "═══ 2. uber7 ═══"
  toast "2/5 uber7"
  if [ -f ~/jec_uber7.py ]; then
    if ! python ~/jec_uber7.py; then
      git checkout -- app/src/main/java 2>/dev/null
      say "Убер нашёл промахи"
      notif "❌ uber7 FAIL" "$MSG" 1000
      vib 400; exit 1
    fi
    toast "✅ uber7"
  else
    say "jec_uber7.py не найден"
    vib 200
  fi

  echo "═══ 3. check ═══"
  toast "3/5 check"
  if [ -f ~/jec_check.sh ]; then
    if ! bash ~/jec_check.sh; then
      git checkout -- app/src/main/java 2>/dev/null
      say "Чек упал"
      notif "❌ Check FAIL" "$MSG" 1000
      vib 400; exit 1
    fi
    toast "✅ check"
    say "Чек пройден"
  else
    say "jec_check.sh не найден"
    vib 200
  fi

  echo "═══ 4. push ═══"
  toast "4/5 push"
  say "Пуш начат"
  TOKEN=$(cat ~/.gh_token | tr -d '\n\r ')
  git remote set-url origin "https://${TOKEN}@github.com/Garickbox/DeepApk.git"
  git add -A
  git commit -m "$MSG" || echo "(no changes)"
  PUSH_OUT=$(git push origin main 2>&1)
  echo "$PUSH_OUT" | tail -3

  if echo "$PUSH_OUT" | grep -qE "main ->|Everything up-to-date"; then
    say "Пуш прошёл, жду CI"
    notif "✅ Push OK" "$MSG" 1000
    vib 150
  else
    say "Пуш не прошёл"
    notif "⚠️ Push fail" "$MSG" 1000
    vib 300; exit 1
  fi

  echo "═══ 5. watch CI ═══"
  toast "5/5 CI"
  sleep 5
  REPO="Garickbox/DeepApk"

  RUN_DATA=$(curl -s -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$REPO/actions/runs?per_page=1")
  RUN_ID=$(echo "$RUN_DATA" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('workflow_runs',[{}])[0].get('id') if d.get('workflow_runs') else '')")

  [ -z "$RUN_ID" ] && { say "Не удалось получить ID раннера"; exit 1; }
  say "Раннер: https://github.com/$REPO/actions/runs/$RUN_ID"

  while true; do
    RUN_INFO=$(curl -s -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID")
    STATUS=$(echo "$RUN_INFO" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('status'))")
    CONCLUSION=$(echo "$RUN_INFO" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('conclusion'))")

    say "status=$STATUS | conclusion=$CONCLUSION"

    if [ "$STATUS" = "completed" ]; then
      break
    fi
    sleep 10
  done

  if [ "$CONCLUSION" = "success" ]; then
    say "✅ Сборка OK"
    notif "✅ CI Success" "Build passed" 1000
    vib 200
  elif [ "$CONCLUSION" = "cancelled" ]; then
    say "⚠️ Сборка отменена (cancelled)"
    notif "⚠️ CI Cancelled" "Check manual trigger" 1000
    vib 400
  else
    say "❌ Сборка упала: $CONCLUSION"
    notif "❌ CI Failure" "Check logs" 1000
    vib 500

    JOBS_URL=$(echo "$RUN_INFO" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('jobs_url'))")
    JOBS=$(curl -s -H "Authorization: Bearer $TOKEN" "$JOBS_URL")

    FAILED_JOB_INFO=$(echo "$JOBS" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for j in d.get('jobs', []):
  if j.get('conclusion') == 'failure':
    print(f\"{j.get('name')}|{j.get('id')}\")
    break
")

    if [ -n "$FAILED_JOB_INFO" ]; then
      JOB_NAME=$(echo "$FAILED_JOB_INFO" | cut -d'|' -f1)
      JOB_ID=$(echo "$FAILED_JOB_INFO" | cut -d'|' -f2)

      say "Упал job: $JOB_NAME"
      LOGS=$(curl -s -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$REPO/actions/jobs/$JOB_ID/logs" | head -n 600)

      echo "--- ФРАГМЕНТ ОШИБКИ ---"
      echo "$LOGS" | grep -E "FAILURE|Execution failed|Caused by|e: " -A 5 -B 5 | head -n 40
      echo "-----------------------"
    else
      say "Не найдено упавших задач — смотри вручную"
    fi
  fi
} 2>&1 | tee "$LOG"
termux-clipboard-set < "$LOG" 2>/dev/null || true
