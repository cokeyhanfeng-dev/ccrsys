#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════
# CCR 增量打包脚本(已上线系统增量交付)
# 用法: ./scripts/incr-release.sh <序号如 001> [增量SQL文件...]
#   不传 SQL 文件时,自动收集 db/incr/ 下本日全部增量 SQL
# 产物: release/incr_<日期>_<序号>/
#     ├─ README.md           增量说明(部署/验证/回滚/本轮改动清单)
#     ├─ db/*.sql            本次增量 SQL
#     ├─ backend/ccr-admin.jar  后端增量 jar(整体替换)
#     └─ frontend/dist/      前端增量静态包(整体替换)
# 依赖: docker compose(后端镜像内编译,宿主机无需 JDK)、宿主机 node/npm(前端 dist)
# ══════════════════════════════════════════════════════════════
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SEQ="${1:?用法: ./scripts/incr-release.sh <序号如 001> [增量SQL文件...]}"
shift || true

DATE="$(date +%Y%m%d)"
OUT="release/incr_${DATE}_${SEQ}"
echo "==> 增量产物目录: ${OUT}"
mkdir -p "$OUT/db" "$OUT/backend" "$OUT/frontend"

# ── 1) 后端 jar:构建后端镜像后提取(宿主机无 JDK,走镜像内编译)
echo "==> [1/4] 构建后端镜像(docker compose build backend)..."
docker compose build backend
# 本环境 docker compose images 索引异常(引用过期 ID),改由 config --images 解析镜像名(ccr-backend)
IMG_ID="$(docker compose config --images | awk '/backend$/{print $1; exit}')"
CID="$(docker create "$IMG_ID")"
trap 'docker rm -f "$CID" >/dev/null 2>&1 || true' EXIT
echo "==> [1/4] 提取 jar..."
docker cp "$CID:/app/app.jar" "$OUT/backend/ccr-admin.jar"
docker rm "$CID" >/dev/null 2>&1 || true
trap - EXIT
ls -lh "$OUT/backend/ccr-admin.jar"

# ── 2) 前端 dist:宿主机 npm run build
echo "==> [2/4] 构建前端 dist(npm run build)..."
(cd frontend && npm run build)
cp -r frontend/dist/. "$OUT/frontend/dist/"

# ── 3) 增量 SQL
echo "==> [3/4] 收集增量 SQL..."
if [ "$#" -gt 0 ]; then
  for f in "$@"; do
    cp "$f" "$OUT/db/"
    echo "    + $(basename "$f")"
  done
else
  if compgen -G "db/incr/${DATE}_*.sql" > /dev/null; then
    # 只收集未在历史增量包(release/incr_*/db/)收录过的本日增量 SQL,避免跨包重复交付
    PACKED="$(for f in release/incr_*/db/*.sql; do [ -f "$f" ] && basename "$f"; done; true)"
    for f in db/incr/${DATE}_*.sql; do
      b="$(basename "$f")"
      if ! printf '%s\n' "$PACKED" | grep -qx "$b"; then
        cp "$f" "$OUT/db/"
        echo "    + $b"
      else
        echo "    - 跳过(已在历史增量包收录): $b"
      fi
    done
    [ -z "$(ls -1 "$OUT/db/")" ] && echo "    (本日增量均已交付过,无新 SQL)"
  else
    echo "    (未指定且无本日增量 SQL)"
  fi
fi

# ── 4) 生成 README(部署说明 + 本轮改动清单)
echo "==> [4/4] 生成 README..."
SQL_LIST="$(for f in "$OUT"/db/*.sql; do [ -f "$f" ] && echo "- \`$(basename "$f")\`"; done; true)"
GIT_FILES="$(git -c core.quotepath=false status --short | grep -v -E 'db/incr/|release/' || true)"
{
cat <<EOF
# CCR 增量发布包 incr_${DATE}_${SEQ}

> 增量基线:publish_full.sql(${DATE}) + 此前全部 db/incr/*.sql
> 打包时间:$(date '+%Y-%m-%d %H:%M')

## 内容
- \`db/\`       增量 SQL(见下表,按文件名升序执行)
- \`backend/\`  后端增量 jar(\`ccr-admin.jar\`,整体替换)
- \`frontend/\` 前端增量静态包(\`dist/\`,整体替换)

## 本次增量 SQL
${SQL_LIST}

## 部署步骤
1. **SQL**(已上线库,先于代码生效):
   \`\`\`bash
   docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 < db/<脚本>.sql
   # 若脚本尾部有 Redis key 清理提示,按提示 DEL 对应缓存
   \`\`\`
2. **后端**:替换 \`backend/ccr-admin.jar\` 后重启(\`docker compose up -d --build backend\` 或容器内替换 jar + 重启)。
3. **前端**:替换 \`frontend/dist/\` 后重启 Nginx(\`docker compose up -d --build frontend\` 或替换静态目录 + reload)。

## 本轮改动文件(git,未提交部分)
\`\`\`
${GIT_FILES}
\`\`\`

## 验证 / 回滚
- 验证:按 docs/08_上线检查单.md 对应条目冒烟;SQL 变更查表确认生效。
- 回滚:SQL 反向更新 + 替换上一版 jar/dist(参考 docs/08_上线检查单.md §6)。
EOF
} > "$OUT/README.md"

echo ""
echo "==> 完成: $OUT"
du -sh "$OUT"
