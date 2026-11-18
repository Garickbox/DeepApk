#!/data/data/com.termux/files/usr/bin/bash
set -e

source ~/.jec_notify
cd ~/DeepApk || { say "Нет проекта"; exit 1; }
LOG=~/deepapk_go.log
PATCH="$1"
MSG="${2:-auto}"
[ -z "$PATCH" ] && { echo "usage: jec_go.sh <patch.py> \"msg\""; exit 1; }
[ ! -f "$PATCH" ] && { say "Нет файла патча"; exit 1; }
NO_BUILD="${JEC_NO_BUILD:-0}"

say "Патч начат"
notif "🚀 Patch start" "$MSG" 1000

{
  echo "═══ 0. чистое ═══"
  git checkout -- app/src/main/java 2>/dev/null || true
  git status --short

  echo "═══ 1. patch ═══"
  toast "1/6 patch"
  if ! python "$PATCH"; then
    git checkout -- app/src/main/java 2>/dev/null
    say "Ошибка патча"
    notif "❌ Patch FAIL" "$MSG" 1000
    vib 500; exit 1
  fi
  vib 100; toast "✅ patch"

  echo "═══ 2. uber7 ═══"
  toast "2/6 uber7"
  if ! python ~/jec_uber7.py; then
    git checkout -- app/src/main/java 2>/dev/null
    say "Убер нашёл промахи"
    notif "❌ uber7 FAIL" "$MSG" 1000
    vib 400; exit 1
  fi
  toast "✅ uber7"

  if [ "$NO_BUILD" != "1" ]; then
    echo "═══ 3. build ═══"
    toast "3/6 build"
    say "Сборка"
    notif "🔨 Build..." "$MSG" 1000
    if ! ./gradlew compileDebugKotlin --console=plain --max-workers=2 > ~/gradle_build.log 2>&1; then
      grep -E "^e: " ~/gradle_build.log | head -10
      git checkout -- app/src/main/java 2>/dev/null
      ERR1=$(grep -E '^e: ' ~/gradle_build.log | head -1)
      say "Сборка упала"
      notif "❌ Build FAIL" "$ERR1" 1000
      vib 500; sleep 0.2; vib 500
      exit 1
    fi
    toast "✅ build"
    say "Лок пройден"
  else
    say "Лок пропущен"
  fi

  echo "═══ 4. check ═══"
  toast "4/6 check"
  if ! bash ~/jec_check.sh; then
    git checkout -- app/src/main/java 2>/dev/null
    say "Чек упал"
    notif "❌ Check FAIL" "$MSG" 1000
    vib 400; exit 1
  fi
  toast "✅ check"
  say "Чек пройден"

  echo "═══ 5. push ═══"
  toast "5/6 push"
  say "Пуш начат"
  TOKEN=$(cat ~/.gh_token | tr -d '\n\r ')
  git remote set-url origin "https://${TOKEN}@github.com/Garickbox/DeepApk.git"
  git add -A
  git commit -m "$MSG" || echo "(no changes)"
  PUSH_OUT=$(git push origin main 2>&1)
  echo "$PUSH_OUT" | tail -3
  if echo "$PUSH_OUT" | grep -qE "main ->|Everything up-to-date"; then
    say "Пуш прошёл, жду Си Ай"
    notif "✅ Push OK" "$MSG" 1000
    vib 150
  else
    say "Пуш не прошёл"
    notif "⚠️ Push fail" "$MSG" 1000
    vib 300
    exit 1
  fi

  echo "═══ 6. watch CI ═══"
  toast "6/6 CI"
  sleep 5

  REPO="Garickbox/DeepApk"
  while true; do
    RUN_DATA=$(curl -s -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$REPO/actions/runs?per_page=1")
    RUN_ID=$(echo "$RUN_DATA" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('workflow_runs',[{}])[0].get('id') if d.get('workflow_runs') else '')")

    [ -z "$RUN_ID" ] && { say "Не удалось получить ID раннера"; break; }

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
    say "✅ CI Success"
    notif "✅ CI Success" "Build passed" 1000
    vib 200
  elif [ "$CONCLUSION" = "cancelled" ]; then
    say "⚠️ CI Cancelled"
    notif "⚠️ CI Cancelled" "Check manual trigger" 1000
    vib 400
  else
    say "❌ CI Failure: $CONCLUSION"
    notif "❌ CI Failure" "Check logs" 1000
    vib 500

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

      JOB_ID=$(echo "$JOB_URL" | python3 -c "import sys,re; u=sys.stdin.read(); m=re.search(r'/jobs/(\d+)',u); print(m.group(1) if m else '')")
      if [ -n "$JOB_ID" ]; then
        LOGS=$(curl -s -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$REPO/actions/jobs/$JOB_ID/logs")
        echo "--- фрагмент ошибки ---"
        echo "$LOGS" | grep -E "FAILURE|Execution failed|Caused by" -A 3 -B 3 | head -n 20
        echo "--- конец фрагмента ---"
      fi
    else
      echo "⚠️ Не удалось найти упавший job — смотри вручную"
    fi
  fi
} 2>&1 | tee "$LOG"
termux-clipboard-set < "$LOG" 2>/dev/null || true
