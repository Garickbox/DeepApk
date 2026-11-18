#!/data/data/com.termux/files/usr/bin/bash
source ~/.jec_notify 2>/dev/null || true
cd ~/DeepApk || { echo "❌ Нет проекта DeepApk"; exit 1; }

LOG=~/deepapk_run.log
PATCH="$1"
MSG="${2:-auto}"

[ -z "$PATCH" ] && { echo "usage: deepapk_go.sh <patch.py> \"msg\""; exit 1; }
[ ! -f "$PATCH" ] && { echo "❌ Нет файла патча: $PATCH"; exit 1; }

echo "🚀 START DeepApk Pipeline"
{
  echo "═══ 0. clean ====="
  git checkout -- app/src/main/java 2>/dev/null || true
  git status --short

  echo "═══ 1. patch ====="
  if ! python "$PATCH"; then
    git checkout -- app/src/main/java 2>/dev/null
    echo "❌ Ошибка патча"
    exit 1
  fi
  echo "✅ Patch OK"

  echo "═══ 2. uber7 ====="
  if [ -f ~/jec_uber7.py ]; then
    if ! python ~/jec_uber7.py; then
      git checkout -- app/src/main/java 2>/dev/null
      echo "❌ Uber7 FAIL"
      exit 1
    fi
    echo "✅ Uber7 OK"
  else
    echo "⚠️ jec_uber7.py не найден — пропускаем"
  fi

  echo "═══ 3. check ====="
  if [ -f ~/jec_check.sh ]; then
    if ! bash ~/jec_check.sh; then
      git checkout -- app/src/main/java 2>/dev/null
      echo "❌ Check FAIL"
      exit 1
    fi
    echo "✅ Check OK"
  else
    echo "⚠️ jec_check.sh не найден — пропускаем"
  fi

  echo "═══ 4. push ====="
  TOKEN=$(cat ~/.gh_token | tr -d '\n\r ')
  git remote set-url origin "https://${TOKEN}@github.com/Garickbox/DeepApk.git"
  git add -A
  git commit -m "$MSG" || echo "(no changes)"
  
  PUSH_OUT=$(git push origin main 2>&1)
  echo "$PUSH_OUT" | tail -3
  
  if echo "$PUSH_OUT" | grep -qE "main ->|Everything up-to-date"; then
    echo "✅ Push OK, CI started"
  else
    echo "⚠️ Push failed"
    exit 1
  fi

  echo "═══ 5. watch CI + error extract ====="
  REPO="Garickbox/DeepApk"

  RUN_DATA=$(curl -s -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$REPO/actions/runs?per_page=1")
  RUN_ID=$(echo "$RUN_DATA" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('workflow_runs',[{}])[0].get('id') if d.get('workflow_runs') else '')")
  
  [ -z "$RUN_ID" ] && { echo "❌ Не удалось получить ID раннера"; exit 1; }
  echo "👉 Раннер: https://github.com/$REPO/actions/runs/$RUN_ID"

  while true; do
    RUN_INFO=$(curl -s -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID")
    STATUS=$(echo "$RUN_INFO" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('status'))")
    CONCLUSION=$(echo "$RUN_INFO" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('conclusion'))")

    echo "ℹ️ status=$STATUS | conclusion=$CONCLUSION"
    
    if [ "$STATUS" = "completed" ]; then
      break
    fi
    sleep 10
  done

  if [ "$CONCLUSION" = "success" ]; then
    echo "✅ Сборка прошла успешно"
  else
    echo "❌ Сборка упала (conclusion=$CONCLUSION)"
    
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

      echo "❌ Упал job: $JOB_NAME (ID: $JOB_ID)"
      
      if [ -n "$JOB_ID" ]; then
        LOGS=$(curl -s -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$REPO/actions/jobs/$JOB_ID/logs" | head -n 600)
        
        echo "--- ФРАГМЕНТ ОШИБКИ (как в твоем шаблоне) ---"
        echo "$LOGS" | grep -E "FAILURE|Execution failed|Caused by|e: " -A 5 -B 5 | head -n 40
        echo "-------------------------------------------"
      else
        echo "⚠️ Не удалось извлечь ID job. Смотри вручную на GitHub."
      fi
    else
      echo "⚠️ Не найдено упавших задач."
    fi
  fi
} 2>&1 | tee "$LOG"

termux-clipboard-set < "$LOG" 2>/dev/null || true
echo "✅ Лог скопирован в буфер и сохранен в $LOG"
