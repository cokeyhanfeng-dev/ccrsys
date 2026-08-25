# db/incr/ · 增量 SQL 目录

> 系统已全量上线。**后续所有 SQL 改动一律放本目录做成增量脚本**，不再修改全量基线
> `publish_full.sql` 与已发布的 `db/*.sql` 种子文件（保持上线基线不变）。

## 基线关系

- `db/publish_full.sql` = **上线基线**（2026-08-18 全量，冻结不再改）。
- `db/incr/*.sql` = 基线之后的**全部增量变更**，按文件名升序执行。
- 全新环境重建 = `publish_full.sql` → 按序执行全部 `db/incr/*.sql`。
- 已上线环境升级 = 只执行**新增加的**增量脚本（已执行过的不要重复，脚本本身须幂等可重跑）。

## 命名规范

```
YYYYMMDD_NNN_短横线描述.sql
```

- `YYYYMMDD`：编写日期。
- `NNN`：当日递增序号（001、002…），不得跳号/重号。
- 描述：`短横线`分隔的小写英文/拼音，例：`20260818_001_disable_loan_hard_boundary.sql`。
- **只追加，不修改、不删除**已提交的增量脚本。

## 编写规范（每个增量脚本必须满足）

1. 头部注释块：编号、日期、目的、影响表、是否幂等、执行命令。
2. `USE \`ccr_rate\`;` 开头，单库脚本。
3. **幂等可重复执行**：UPDATE/DELETE 加 `WHERE` 状态条件；INSERT 用 `ON DUPLICATE KEY` 或先判重；DDL 用 `IF NOT EXISTS` / `IF EXISTS`。
4. 涉及规则/矩阵/缓存类配置的改动，尾部注释给出**需清理的 Redis key**（部署后执行）。
5. 不夹带测试数据、真实用户/机构数据。

## 执行方法（已上线环境）

```bash
# 本机 Docker
docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 < db/incr/<脚本>.sql
# 外部数据库
mysql -h<主机> -uroot -p --default-character-set=utf8mb4 < db/incr/<脚本>.sql
```

## 配套

- 增量代码（后端 jar / 前端 dist）由 `scripts/incr-release.sh` 打包进 `release/incr_<日期>_<序号>/`。
- 每次增量发布的变更说明记入 `docs/05_开发改动记录.md`。
