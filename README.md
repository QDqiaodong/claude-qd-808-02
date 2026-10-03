# 饲料加工厂 · 配方批次与质检管理

以「配方 → 投料 → 制粒 → 质检」连续生产闭环为核心的饲料厂中控演示系统。

- 前端：Vue 3 + Element Plus + Vite（单页滚动 + 顶部锚点吸顶条，主色 `#b71c1c`）
- 后端：Spring Boot 3.3.0 + Java 17 + Spring Data JPA(Hibernate 6.5) + MySQL 8 + Redis
- 端口：前端 8288 / 后端 8388 / MySQL 3588 / Redis 6588

## 启动

```bash
./start.sh          # 镜像已存在时秒起
./start.sh --build  # 首次或代码变更后构建
./stop.sh           # 停止
./resume.sh         # 恢复
./clean.sh          # 收摊（down -v，清匿名卷）
```

## 五个区块（一页竖排，锚点吸顶高亮）

| 锚点 | 内容 |
| --- | --- |
| 01 概览 | 今日批次 / 累计产量 / 合格率 三张指标卡 + 纯 SVG 近 7 日产量折线 |
| 02 配料 | 横向堆叠占比条，点色段微调 ±0.5%，锁定某原料后自动平摊其余至 100% |
| 03 投料 | 投料口大按钮阵列，按投料顺序称量，环形倒计时 + 脉冲动画，误差超 ±2% 拦截记异常 |
| 04 制粒 | 温度 / 蒸汽压力 / 环模孔径 三滑块，实时给出推荐环模寿命消耗与寿命消耗条 |
| 05 质检 | 抽样批次卡片 + 检验项表格（超标标红）+ CSS 圆形印章判定 |

## 数据访问路线（本仓技术标识）

- 有序集合：`Recipe.items`、`QcSample.items` 用 `@SortNatural` + `SortedSet`（实体实现 `Comparable`）；
  `Batch.feedings` 用 `@SortComparator(FeedingItem.FeedSeqComparator)` + `SortedSet` 保证严格按投料顺序。
  不使用 `@OrderColumn`，也不是只读 `@OrderBy`。
- 手写 JPQL（`Object[]` 聚合投影）：
  - `RecipeRepository#speciesStats` —— 按畜种统计配方数与成本
  - `FeedingItemRepository#batchFeedErrorStats` —— 按批次统计投料误差率与异常条数
  - `QcSampleRepository#failDistribution` —— 质检不合格项分布
  - `BatchRepository#dailyOutput` —— 按生产日期统计产量（概览折线）
- Redis：概览结果缓存 30 秒（`feed-mill:dashboard`），Redis 不可用时自动降级直查。

## 业务规则（service 抛 BizException）

1. 配方明细占比合计必须 100.00%，否则发布拦截并给出差值。
2. 单一原料不得超过其占比上限（鱼粉 ≤ 8%、预混料 ≤ 4%、石粉 ≤ 3% …）。
3. 投料必须按投料口顺序；实际称重与计划量偏差超过 ±2% 拦截并记为异常。
4. 环模累计产量 + 本批产量超过寿命上限不允许继续制粒（记录 BLOCKED）。
5. 霉菌毒素超标必须判退货，不允许人工改判合格；其它项超标不允许判合格。
6. 退货复检闭环：仅初检结论为退货的批次可发起复检（留发起人/原因/待复核状态）；
   复核员必须不同于初检判定人；复检值写入 qc_recheck_item，初检 qc_sample/qc_item 永不改写；
   霉菌毒素仍超标必须继续退货，全部项目恢复标准范围才允许放行，中间情形判降级并给明确去向；
   一批次同一时刻只有一张进行中复检单（uk_recheck_active 唯一索引），
   最终结论用条件更新原子收口，并发/重复提交只有第一个生效，后到请求看到已完成结果不能覆盖；
   结论生效后批次状态、质检卡片、概览合格率口径一致回写（放行=合格）。

## 接口

```
GET  /api/materials                 原料与筒仓
GET  /api/materials/low-stock       缺料预警
GET  /api/recipes                   配方列表
GET  /api/recipes/{id}              配方详情（@SortNatural 有序明细）
POST /api/recipes/{id}/adjust       占比微调 ±0.5%
POST /api/recipes/{id}/spread       锁定占比并自动平摊
POST /api/recipes/{id}/submit       发布校验（合计 100%）
GET  /api/recipes/stats/species     按畜种统计配方数与成本
GET  /api/batches                   批次列表
GET  /api/batches/{id}              批次详情（@SortComparator 有序投料明细）
POST /api/batches/{id}/feeding      开始称量 / 取消称量 / 确认投料
GET  /api/batches/stats/feed-error  按批次统计投料误差率
GET  /api/pellets                   制粒记录（按环模磨损倒序）
POST /api/pellets/batch/{id}/adjust 工艺参数调整 + 推荐寿命消耗
POST /api/pellets/batch/{id}/produce 制粒产出（超寿命拦截）
GET  /api/qc/samples                抽样批次（@SortNatural 有序检验项）
POST /api/qc/samples/{id}/judge     质检判定
GET  /api/qc/rechecks               复检单列表（含复检检验值）
POST /api/qc/samples/{id}/rechecks  发起退货复检（仅初检退货批次）
POST /api/qc/rechecks/{id}/measure  复核员录入复检检验值
POST /api/qc/rechecks/{id}/conclude 提交复检最终结论（原子收口）
GET  /api/qc/stats/fail-distribution 不合格项分布
GET  /api/stats/dashboard           工段概览
```
