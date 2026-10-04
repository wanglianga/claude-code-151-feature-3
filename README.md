# 城市图书馆跨馆借还调拨 · 逾期罚款与预约排队服务

一个城市级公共图书馆后端：读者可以检索本馆与各分馆库存、预约排队、跨馆调拨借书；馆员与物流员处理出库、到馆、还书核验、罚款赔偿与各类异常；管理端查看调拨效率、热门预约、逾期与丢损，并对热门书做需求调控（采购建议、暂停续借、新增复本公平分配）。

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

## 4.1 热门书预约队列调控（本次新增）

某本新书预约人数过多时，服务综合**等待人数、复本数量、续借限制、跨馆调拨成本**给出调控建议；新增复本到馆后按**预约时间 + 读者所在馆**公平分配，通知说明**预计取书窗口**；馆员可查看分配建议并人工调整，调整原因进入**馆藏流转记录**。

```bash
# ① 需求调控建议：等待人数/复本/续借限制/调拨成本 → 建议新增采购或暂停续借
curl -s http://localhost:8080/api/admin/hot-books/1/demand-advice | jq

# ② 暂停该书续借（必填理由，生效后该书一律不可续借；可再次调用恢复）
curl -s -X PUT http://localhost:8080/api/admin/hot-books/1/renewal-suspension \
  -H 'Content-Type: application/json' \
  -d '{"suspended":true,"staff":"馆员老李","reason":"新书预约爆满，暂停续借加快流转"}' | jq

# ③ 新增复本分配建议预览（2 册到总馆）：每册去向、各分馆等待情况、预计取书窗口
curl -s "http://localhost:8080/api/admin/hot-books/1/allocation-plan?arrivalLibraryCode=CENTRAL&copies=2" | jq

# ④ 采购到馆执行：按建议自动分配；可 overrides 人工调整，与建议不一致时必须填 adjustReason
curl -s -X POST http://localhost:8080/api/admin/hot-books/1/acquisitions \
  -H 'Content-Type: application/json' \
  -d '{"arrivalLibraryCode":"CENTRAL","copies":2,"staff":"馆员小王",
       "overrides":[{"copyIndex":1,"holdId":2}],"adjustReason":"R002 为老年读者，优先照顾"}' | jq

# ⑤ 馆藏流转记录：采购到馆 / 自动分配 / 人工调整（含调整原因与操作人）
curl -s http://localhost:8080/api/admin/hot-books/items/N1-0002/movements | jq
curl -s http://localhost:8080/api/admin/hot-books/1/movements | jq
```

分配规则（预览接口 `rules` 字段同步返回）：

1. 逐册计算各分馆优先级 =（该馆最早等待者**已等待时长** + **调拨在途时长**）÷（1 + 本轮已分给该馆册数），优先级最高馆的最早预约者得书——预计到手总等待越长的馆越优先；
2. 每分给某馆一册，该馆优先级折减，**避免新增复本全部流向总馆**等单一馆；
3. 取书馆即到馆馆的读者无需调拨，直接上架待取；其余锁定条码并生成调拨任务；
4. 复本多于排队人数时，多余复本在到馆馆上架可借。

分配后读者收到含**预计取书窗口**的通知：本馆读者为“取书窗口 = 现在 ~ 现在+取书时限”；跨馆读者为“预计调拨到馆时刻 ~ 其+取书时限”（`ALLOCATION` 调配通知），书到馆签收后再发 `READY` 取书通知。

## 5. 领域模型与状态机

- `items` 馆藏条码：`AVAILABLE → LOCKED → IN_TRANSIT → ON_HOLD → CHECKED_OUT`，异常分支 `LOST / DAMAGED`。
- `holds` 预约：`QUEUED → READY → BORROWED`；异常 `CANCELLED / EXPIRED / LOST`。
- `transfer_tasks` 调拨：`CREATED → PICKED_UP → ARRIVED`；异常 `LOST / CANCELLED`；按 `transfer_batches` 物流批次聚合包裹。
- `loans` 借阅：`ACTIVE → RETURNED`；争议 `CLAIMED_RETURNED`；确认丢失 `LOST`。
- `fines` 罚款/赔偿：类型含逾期、污损、缺页、附件、赔书、物流丢包、人工；承担方 `READER / LIBRARY / LOGISTICS`；状态 `OPEN / PAID / WAIVED / REDUCED`，调整记录在 `fine_adjustments`。
- `item_movements` 馆藏流转记录：`ACQUISITION` 采购到馆 / `AUTO_ASSIGN` 系统按队列自动分配 / `MANUAL_ADJUST` 馆员人工调整（强制记录调整原因、操作人、系统建议与调整后去向）。
- `bibs.renew_suspended` 书目级续借暂停：热门书等待人数过多时馆员设置（必填理由），生效期间该书一律不可续借。

一致性设计要点：

1. **单一再分配入口**：取消、超时、到馆失效、完好归还产生的在馆副本，统一经 `HoldService.reallocate(item, library)` 分配给队列首位（取书点在本馆的等待者优先，省一趟物流），无人排队则上架。
2. **统一加锁顺序**：所有同时操作“预约 ↔ 馆藏”的入口先锁该书目活跃预约行（`SELECT … FOR UPDATE`），再锁具体馆藏行，避免并发抢同一本与死锁。
3. **异常状态联动**：丢包/损坏/争议/闭馆在库存、预约、借阅、罚款之间同步变更，且同一事务提交。

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
| `demand.purchaseThreshold` | 3 | 热门书调控：未分到复本的排队人数达到该值且在架不足时，建议新增采购（建议册数 = 等待人数 − 在架可借数） |
| `demand.suspendRenewalThreshold` | 2 | 热门书调控：未分到复本的排队人数达到该值时，建议暂停该书续借 |

每条罚款都携带 `ruleBasis`（规则依据文案），馆员处理异常与人工调整时可直接向读者解释；调减/豁免强制填写理由并保留原值、新值、操作人、时间戳。

## 7. 本次实际执行过的验证

- `mvn test`：**19 个测试全部通过**（H2 + 真实 Flyway 迁移，含 V3 热门书调控迁移）：
  - `LibraryFlowTests`（11 个端到端业务流程）：检索视图与外馆保护、跨馆调拨全链路、FIFO 排队与超时释放、保护比例收紧、物流丢包保位重拨与物流赔偿、出库前/在途取消、逾期罚款累计封顶/逾期阻借/排队阻续、污损缺页附件赔偿与退出流转、声称已还三种结案与人工调整审计、临时闭馆双顺延、管理端统计一致性。
  - `HotBookQueueTests`（5 个热门书调控）：需求建议综合等待人数/复本/续借限制/调拨成本并给出采购与暂停续借建议；暂停续借生效与恢复；新增复本按预约时间+读者所在馆公平分配（3 册不全流向总馆：城西/城南调拨、总馆仅 1 册）且通知含预计取书窗口、流转记录留痕；人工调整不填原因被拒绝、填原因后 MANUAL_ADJUST 进入馆藏流转记录；多余复本到馆上架且条码序号连续。
  - `WebApiTests`（3 个 HTTP 冒烟）：REST JSON、参数校验 400、热门书调控接口（建议/预览/采购/流转记录/暂停续借 422 与生效）、OpenAPI 3 文档与 Swagger UI 可访问。
- `mvn package`：构建出 `target/city-library.jar`（约 57 MB，可执行 fat jar）。
- 真实数据库冒烟：已在本机用 **PostgreSQL 15** 实际启动该 JAR（Flyway V1–V3 建表播种成功，`item_movements` 表与 `demand.*` 参数就绪），并通过 curl 走通：
  - 既有主链路：中文检索（URL 编码）→ 预约锁定城东条码 `C-0004` → 出库批次/包裹号/ETA=72h → 到馆 WEST → 借出（应还日期 +30 天）→ 管理端总览 → Swagger UI / OpenAPI JSON 均 200。
  - 新增热门书链路：借空《三体》6 册 → 3 位读者排队（2 总馆 1 城西）→ `demand-advice` 返回等待 3/在架 0/调拨不可行并建议采购 3 册+暂停续借 → `allocation-plan` 建议第 1 册调城西、第 2 册留总馆（未全部流向总馆）→ `acquisitions` 人工把第 2 册从 R001 调整为 R002（不填原因 422；填原因后 `N1-0002` 流转记录含 MANUAL_ADJUST+原因）→ R003 收到含预计取书窗口的 `ALLOCATION` 通知、调拨出库到馆后收到 `READY` 通知 → 暂停续借后 `renew-eligibility` 返回不可续借并说明理由，恢复后复原。
  - 顺带修复并验证：`GET /api/loans/{id}/renew-eligibility` 原实现跨两个只读事务访问懒加载关联会 500（LazyInitializationException），已改为单事务视图方法并复测 200。
- 未执行项：本环境无 Docker 守护进程，`docker compose up` 未在本机实际运行；Dockerfile 采用标准 Maven 多阶段构建 + eclipse-temurin:17-jre，且 compose 使用的 PostgreSQL 建表迁移脚本已在上述真实 PG 15 实例上验证通过。
