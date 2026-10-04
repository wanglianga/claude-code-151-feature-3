# 城市图书馆跨馆借还调拨 · 逾期罚款与预约排队服务

一个城市级公共图书馆后端：读者可以检索本馆与各分馆库存、预约排队、跨馆调拨借书；馆员与物流员处理出库、到馆、还书核验、罚款赔偿与各类异常；管理端查看调拨效率、热门预约、逾期与丢损，并针对**热门书预约队列**根据等待人数、复本数量、续借限制与跨馆调拨成本给出**新增采购 / 暂停续借建议**；新购复本到馆后按**预约时间 + 各分馆读者等待时长均衡分配**（避免全部流向总馆），馆员可查看并手动改派，**改派原因强制写入馆藏流转记录**。

技术栈：Java 17 · Spring Boot 3.2 · Spring Data JPA · PostgreSQL 16 · Flyway · springdoc OpenAPI 3 / Swagger UI · Maven · Docker Compose。

---

## 1. 一键启动（Docker Compose，含 PostgreSQL）

```bash
docker compose up --build
```

启动后：

- 服务地址：http://localhost:8080
- Swagger UI（可直接试调全部接口）：http://localhost:8080/swagger-ui.html
- OpenAPI JSON：http://localhost:8080/v3/api-docs

首次启动 Flyway 自动建表并写入种子数据（5 个分馆、4 部书目、13 册馆藏、3 位读者、全部规则参数）。停止并清理：`docker compose down -v`。

## 2. 本地开发启动

前置：JDK 17、Maven 3.8+、PostgreSQL 14+。

```bash
# 1) 准备数据库
createdb library
# 2) 设置数据库连接（可选，默认值如下）
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/library
export SPRING_DATASOURCE_USER=library
export SPRING_DATASOURCE_PASSWORD=library
# 3) 运行（Flyway 自动迁移）
mvn spring-boot:run
# 4) 打包
mvn package
java -jar target/city-library.jar
```

测试使用 H2（PostgreSQL 兼容模式）+ 同一套 Flyway 迁移脚本，无需本地数据库：

```bash
mvn test
```

## 3. 测试账号（种子数据）

| 角色 | 账号 | 说明 |
| --- | --- | --- |
| 读者 | `R001` 张三 | 借书证号即接口中的 `readerCardNo` |
| 读者 | `R002` 李四 | 同上 |
| 读者 | `R003` 王五 | 同上 |
| 馆员 / 物流员 | 请求体中的 `staff` / `staffName` 字段 | 演示环境不做登录鉴权，操作留痕记录操作人姓名 |

分馆代码：`CENTRAL` 中心馆、`NORTH` 城北、`SOUTH` 城南、`WEST` 城西、`EAST` 城东远端分馆。
书目 ID：1《三体》（有电子书）、2《红楼梦》、3《解忧杂货店》（有电子书）、4《平凡的世界》。

## 4. 快速验证（端到端 curl 剧本）

以下剧本覆盖题目主链路：**检索 → 预约锁条码 → 调拨出库 → 到馆通知 → 借出 → 归还 → 统计**。

```bash
# 读者在城西检索《三体》：本馆 0 册、城东可调拨 1 册、ETA 72h、有电子书替代
curl -s -G "http://localhost:8080/api/catalog/search" \
  --data-urlencode "keyword=三体" --data-urlencode "pickupLibraryCode=WEST" | jq

# R001 预约，取书点城西 → 系统锁定城东条码并生成调拨任务
curl -s -X POST http://localhost:8080/api/holds/bibs/1 -H 'Content-Type: application/json' \
  -d '{"readerCardNo":"R001","pickupLibraryCode":"WEST"}' | jq

# 物流员看待出库任务、出库（记录出库馆/目的馆/批次/包裹号/ETA）
TASK_ID=$(curl -s http://localhost:8080/api/transfers/tasks/pending | jq '.[0].id')
curl -s -X POST http://localhost:8080/api/transfers/pickups -H 'Content-Type: application/json' \
  -d "{\"taskIds\":[$TASK_ID],\"staffName\":\"物流员小赵\"}" | jq
PKG=$(curl -s "http://localhost:8080/api/transfers/tasks/$TASK_ID" | jq -r .packageNo)

# 到馆签收 → R001 收到 READY 取书通知，图书在城西预约架（取书时限 3 天）
curl -s -X POST http://localhost:8080/api/transfers/arrivals -H 'Content-Type: application/json' \
  -d "{\"packageNo\":\"$PKG\"}" | jq
curl -s http://localhost:8080/api/notifications/readers/R001 | jq

# R001 借出并查看应还日期/续借资格
BARCODE=$(curl -s "http://localhost:8080/api/holds/readers/R001" | jq -r '.[0].itemBarcode')
LOAN=$(curl -s -X POST http://localhost:8080/api/loans/checkouts -H 'Content-Type: application/json' \
  -d "{\"readerCardNo\":\"R001\",\"itemBarcode\":\"$BARCODE\",\"libraryCode\":\"WEST\"}")
LOAN_ID=$(echo "$LOAN" | jq .id)
curl -s "http://localhost:8080/api/loans/$LOAN_ID/renew-eligibility" | jq

# 还书核验（可传 damaged/missingPages/accessoryMissing，这里为完好归还）
curl -s -X POST http://localhost:8080/api/loans/returns -H 'Content-Type: application/json' \
  -d "{\"itemBarcode\":\"$BARCODE\",\"libraryCode\":\"WEST\"}" | jq

# 管理端：总览/分馆/热门/读者/批次/规则参数
curl -s "http://localhost:8080/api/admin/dashboard?days=30" | jq
curl -s "http://localhost:8080/api/admin/libraries" | jq
curl -s "http://localhost:8080/api/admin/bibs/popular" | jq
curl -s "http://localhost:8080/api/admin/readers?cardNo=R001" | jq
curl -s "http://localhost:8080/api/admin/batches" | jq
curl -s "http://localhost:8080/api/admin/policies" | jq
```

异常链路接口（均在 Swagger 中可试）：

- 取书超时释放：`POST /api/holds/jobs/expire`（定时任务每小时自动执行，也可手动触发）
- 物流丢包：`POST /api/transfers/tasks/{id}/lost` → 馆藏置 LOST、生成 `LOGISTICS` 赔偿单、读者免责保位并自动重新调拨
- 读者取消：`POST /api/holds/{id}/cancel`（未出库立即解锁；在途则到馆释放给下一位）
- 声称已还：`POST /api/claims/loans/{loanId}` → 馆员 `.../found`（入库并豁免逾期）、`.../not-found`（赔书价）、`.../waive`（馆方豁免，必填理由）
- 污损/缺页/附件：还书接口置位 → 分别生成赔偿单，污损书退出预约流转
- 临时闭馆：`POST /api/libraries/{code}/closure` → 在该馆待取预约与临期借阅自动顺延 `closure.graceHours`
- 罚款人工调整：`POST /api/fines/{id}/adjustments`（REDUCE/WAIVE，必须填理由；原值/新值/操作人/规则依据全部审计留痕，`GET /api/fines/{id}/adjustments` 可查）

### 4.1 热门书预约队列调整（建议 → 暂停续借 → 采购到馆 → 均衡分配 → 馆员改派留痕）

```bash
# 假设《平凡的世界》唯一册被 R001 借走，R002/R003 分别在中心馆/城西排队（先制造排队）
curl -s -X POST http://localhost:8080/api/loans/checkouts -H 'Content-Type: application/json' \
  -d '{"readerCardNo":"R001","itemBarcode":"P-0001","libraryCode":"NORTH"}' > /dev/null
curl -s -X POST http://localhost:8080/api/holds/bibs/4 -H 'Content-Type: application/json' \
  -d '{"readerCardNo":"R002","pickupLibraryCode":"CENTRAL"}' > /dev/null
curl -s -X POST http://localhost:8080/api/holds/bibs/4 -H 'Content-Type: application/json' \
  -d '{"readerCardNo":"R003","pickupLibraryCode":"WEST"}' > /dev/null

# ① 需求建议：等待人数/复本比、可续借在借、跨馆调剂成本、建议采购册数、是否建议暂停续借
curl -s http://localhost:8080/api/admin/demand/advice | jq
curl -s http://localhost:8080/api/admin/demand/advice/4 | jq '.byLibrary, .suggestedCopies, .rationale'

# ② 按建议暂停续借（队列消化后可解除）；冻结期间该书在借复本续借资格校验直接拒绝
curl -s -X POST http://localhost:8080/api/admin/bibs/4/renewal-freeze -H 'Content-Type: application/json' \
  -d '{"bibId":4,"staff":"王馆长","reason":"预约积压，暂停续借加速回流"}' | jq
curl -s http://localhost:8080/api/admin/renewal-freezes | jq
curl -s -X POST http://localhost:8080/api/admin/bibs/4/renewal-freeze/release -H 'Content-Type: application/json' \
  -d '{"staff":"王馆长","reason":"新购复本已到馆，队列开始消化"}' | jq

# ③ 登记 2 册新购复本到中心馆：系统按预约时间 + 各分馆等待时长逐册给均衡分配建议（不全部流向总馆）
ACQ=$(curl -s -X POST http://localhost:8080/api/admin/acquisitions -H 'Content-Type: application/json' \
  -d '{"bibId":4,"copyCount":2,"receivingLibraryCode":"CENTRAL","staff":"采购馆员小冯","note":"热门加购"}')
echo "$ACQ" | jq '.allocations[] | {itemBarcode, readerCardNo, targetLibraryCode, fulfillMode, etaHours, pickupWindowEnd}'
ACQ_ID=$(echo "$ACQ" | jq .id)

# ④ 馆员可手动改派（改派给其他排队预约）或跳过（复本在指定馆上架留存）；staff+reason 必填，原因进流转记录
#    此处把建议配送城西 R003 的一册跳过、改在城北上架留存
ALLOC2=$(echo "$ACQ" | jq '.allocations[1].id')
curl -s -X POST http://localhost:8080/api/admin/allocations/$ALLOC2/adjust -H 'Content-Type: application/json' \
  -d '{"skip":true,"shelfLibraryCode":"NORTH","staff":"王馆长","reason":"城北馆下周书展，留存一册"}' | jq

# ⑤ 一键确认执行（也可逐册 /api/admin/allocations/{id}/confirm）：
#    中心馆一册本地直架 R002 即 READY；通知写明“预计取书窗口”（即日至取书时限）
curl -s -X POST http://localhost:8080/api/admin/acquisitions/$ACQ_ID/confirm -H 'Content-Type: application/json' \
  -d '{"staff":"王馆长"}' | jq '.status, .allocations[] | {itemBarcode, status, decidedBy, reason}'
curl -s http://localhost:8080/api/notifications/readers/R002 | jq '.[0].message'

# ⑥ 馆藏流转记录：采购到馆/系统建议/馆员改派原因/执行/暂停续借全部可审计
curl -s "http://localhost:8080/api/admin/flow-logs?bibId=4" | jq

# 执行前还可整批取消：POST /api/admin/acquisitions/{id}/cancel（复本在收货馆上架并走常规队列再分配，原因留痕）
```

## 5. 领域模型与状态机

- `items` 馆藏条码：`AVAILABLE → LOCKED → IN_TRANSIT → ON_HOLD → CHECKED_OUT`，异常分支 `LOST / DAMAGED`；新购到馆待馆员确认分配为 `RECEIVED`（不参与在架可借检索）。
- `holds` 预约：`QUEUED → READY → BORROWED`；异常 `CANCELLED / EXPIRED / LOST`。
- `transfer_tasks` 调拨：`CREATED → PICKED_UP → ARRIVED`；异常 `LOST / CANCELLED`；按 `transfer_batches` 物流批次聚合包裹。
- `loans` 借阅：`ACTIVE → RETURNED`；争议 `CLAIMED_RETURNED`；确认丢失 `LOST`。
- `fines` 罚款/赔偿：类型含逾期、污损、缺页、附件、赔书、物流丢包、人工；承担方 `READER / LIBRARY / LOGISTICS`；状态 `OPEN / PAID / WAIVED / REDUCED`，调整记录在 `fine_adjustments`。
- `item_acquisitions` / `copy_allocations` 新购批次与逐册分配建议：建议 `SUGGESTED`，馆员采纳执行记 `CONFIRMED`、手动改派后记 `MANUAL`、跳过/取消后记 `SKIPPED`；批次 `RECEIVED → ALLOCATED / CANCELLED`。
- `hold_renewal_freezes` 热门书暂停续借：按书目生效/解除（记录操作人、原因）。
- `collection_flow_logs` 馆藏流转记录：采购到馆、系统分配建议、馆员改派（含原因）、确认执行、暂停/解除续借、批次取消全部可审计。

一致性设计要点：

1. **单一再分配入口**：取消、超时、到馆失效、完好归还产生的在馆副本，统一经 `HoldService.reallocate(item, library)` 分配给队列首位（取书点在本馆的等待者优先，省一趟物流），无人排队则上架。
2. **统一加锁顺序**：所有同时操作“预约 ↔ 馆藏”的入口先锁该书目活跃预约行（`SELECT … FOR UPDATE`），再锁具体馆藏行，避免并发抢同一本与死锁；新购批次登记/改派/确认/取消沿用同一锁顺序。
3. **异常状态联动**：丢包/损坏/争议/闭馆在库存、预约、借阅、罚款之间同步变更，且同一事务提交。
4. **建议与执行分离**：新购复本到馆先以 `RECEIVED` 暂存（不抢占普通队列），系统只产出逐册均衡 `SUGGESTED`；馆员确认或手动改派（原因入 `collection_flow_logs`）后才真正改变预约/馆藏/调拨状态。

## 6. 公平性与成本规则（可在管理端调整，见 `policies`）

| 规则 key | 默认值 | 含义 |
| --- | --- | --- |
| `loan.periodDays` | 30 | 标准借阅期限 |
| `loan.renewLimit` | 1 | 续借次数上限；且**该书有人排队时禁止续借**——热门书不被少数读者长期占用 |
| `hold.pickupDays` | 3 | 到馆取书时限，超时释放给下一位 |
| `hold.maxPerReader` | 10 | 单人同时有效预约上限 |
| `fine.daily` / `fine.cap` | 0.5 元/天 / 20 元 | 逾期罚款单价与单册封顶 |
| `fee.damage/missingPages/accessory/replacement/logisticsLoss` | 10/20/5/50/60 | 污损、缺页、缺附件、赔书、物流丢包赔付 |
| `transfer.protectionRatio` | 0.5 | **外馆保护**：调走 1 册后该馆留存可借数不得低于 ⌈比例×在流转馆藏数⌉，远端分馆库存不会被抽空（单册分馆默认保留） |
| `transfer.sameRegionHours/otherRegionHours` | 24/72 | 同/跨片区 ETA；选源馆时按 ETA、再按距离取最优，控制物流成本 |
| `closure.graceHours` | 24 | 临时闭馆时取书时限与应还日期自动顺延 |
| `acquisition.waitRatio` | 2.0 | 采购建议阈值：等待人数/在流转复本数超过该比例（且排队数达 `acquisition.minQueue`）时建议新增采购 |
| `acquisition.minQueue` | 3 | 触发采购建议的最少未满足排队人数 |
| `allocation.balanceWeight` | 0.5 | 新复本均衡权重：0=完全按预约时间 FIFO，1=完全按分馆轮次（各馆先服务本馆第一位），中间值加权 |

每条罚款都携带 `ruleBasis`（规则依据文案），馆员处理异常与人工调整时可直接向读者解释；调减/豁免强制填写理由并保留原值、新值、操作人、时间戳。

## 7. 本次实际执行过的验证

- `mvn test`：**18 个测试全部通过**（H2 + 真实 Flyway 迁移 V1~V3）：
  - `LibraryFlowTests`（15 个端到端业务流程）：在原 11 个（检索视图与外馆保护、跨馆调拨全链路、FIFO 排队与超时释放、保护比例收紧、物流丢包保位重拨与物流赔偿、出库前/在途取消、逾期罚款累计封顶/逾期阻借/排队阻续、污损缺页附件赔偿与退出流转、声称已还三种结案与人工调整审计、临时闭馆双顺延、管理端统计一致性）基础上，新增：
    - **需求建议 + 暂停续借（12）**：借空唯一复本并 3 人排队后，建议接口正确返回等待/复本比、建议采购册数、建议暂停续借与各馆等待画像；冻结后在借续借资格校验被拒且 `renew` 抛错，缺原因/操作人的设置与解除被拒，解除后冻结记录归档。
    - **新购复本分馆均衡（13）**：中心馆 2 人、城西 2 人、城南 1 人按时间排队，权重置 1（纯分馆轮次）时 3 册建议依次落 `CENTRAL/WEST/SOUTH` 而非全给总馆；新复本为 `RECEIVED` 不占在架可借；本地窗口 = 到馆+3 天、跨馆窗口 = ETA 72h+3 天；一键确认后本地即 `READY`、跨馆各生成中心馆→城西/城南调拨，通知含“预计取书窗口/预计在途 72 小时”；城西包裹出库到馆后读者 `READY`，剩余队列重新编号。
    - **馆员手动改派留痕（14）**：无原因/无操作人改派被拒、重复占用被拒；改派与跳过均写 `ALLOCATION_MANUAL` 流转记录；确认后改派记 `MANUAL` 并生成跨馆调拨到馆 `READY`，跳过册在指定馆 `AVAILABLE` 上架。
    - **批次取消（15）**：执行前取消需原因，复本在收货馆上架并经单点再分配服务同馆队首（READY），取消原因写入 `ACQUISITION_CANCEL` 流水。
  - `WebApiTests`（3 个 HTTP 冒烟）：原 REST JSON/参数校验 400/OpenAPI 3 与 Swagger UI 之外，新增需求建议、采购登记 201、建议查询、改派缺原因 400、暂停续借、流转记录与冻结列表的 HTTP 链路。
- `mvn package`：构建出 `target/city-library.jar`（约 57 MB，可执行 fat jar）。
- 真实数据库冒烟：本次在本机用独立临时目录初始化了 **PostgreSQL 15** 实例（端口 5433，trust 认证，与系统库隔离）并实际启动该 JAR（Flyway 执行 V1~V3 建表播种成功），通过 curl 走通“借走唯一册 → 不同分馆排队 → 需求建议（各馆等待画像）→ 暂停续借（续借被拒）→ 登记 2 册新购（系统建议：本地直架 + 跨馆 ETA 72h 与取书窗口）→ 缺原因改派 400 → 馆员跳过留痕 → 一键确认（READY 通知含取书窗口、流转记录完整）→ 解除冻结 → Swagger UI / OpenAPI JSON 200”全链路；冒烟后已停止实例并清理临时目录。
  - 该轮冒烟还发现并修复了一个**既有缺陷**：`GET /api/loans/{id}/renew-eligibility` 在事务外访问懒加载关联，真实运行时返回 500（H2 集成测试未覆盖该 HTTP 端点）；已改为在单一只读事务内完成查询，并在 `WebApiTests` 增加该端点的 HTTP 回归断言。
- 未执行项：本环境无 Docker 守护进程，`docker compose up --build` 未在本机实际运行；Dockerfile 为标准 Maven 多阶段构建 + eclipse-temurin:17-jre，compose 使用的 PostgreSQL 16 建表/迁移脚本（V1~V3，均为 PG/H2 通用 SQL）已在上述真实 PostgreSQL 15 实例上验证通过。
