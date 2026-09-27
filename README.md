# fund-flow-risk-java

本地资金流风险检测服务。接收地址之间的转账记录，维护有向资金流图，识别
**环形回流、分层拆分、快速汇聚、地址复用** 四类风险模式。支持增量写入、
并发查询、删除回滚与重启恢复。

## 构建与运行

```bash
mvn compile          # 编译
mvn test             # 运行全部测试
mvn spring-boot:run  # 启动服务（默认 8080 端口）
```

要求 JDK 17+、Maven 3.6+。

## 图模型

- **顶点**：地址（字符串）。
- **边**：一笔转账 `TransferRecord(txId, from, to, amount, timestamp)`，
  方向为 `from -> to`。`amount` 为最小货币单位的整数（避免浮点误差，
  保证结果确定）；`timestamp` 为事件时间（epoch 毫秒）。
- 边以 `txId` 为唯一键：**相同 `txId` 的记录只会产生一条边**（先写胜出），
  重复投递不会生成重复边或重复风险命中。
- 图是边的集合，最终状态与记录到达顺序无关：乱序、迟到记录与按序到达
  产生完全相同的图与命中。
- 允许自环（`from == to`），自环视为长度为 1 的环形回流。

## 规则语义

检测是 `(图状态, 规则参数)` 的**纯函数**：同一状态下用同一参数重复计算，
结果完全一致；调整参数即可重新计算命中。

| 规则 | 语义 |
|------|------|
| `CYCLE` 环形回流 | 存在一条从某地址出发、经不超过 `maxHops` 跳回到自身的有向路径（含自环）。每个简单环只报告一次，以环上字典序最小地址为锚点。同一地址对之间的平行边折叠为最早一条作为证据。 |
| `LAYERING` 分层拆分 | 地址在 `timeWindowMillis` 内向不少于 `minCounterparties` 个不同地址拆出资金，且其中至少一个接收方在收款后同样长度的时间窗内继续向下拆分。拆分链最多追踪 `maxHops` 层，`maxHops < 2` 时该规则不产生命中。每个根地址最多报告一次。 |
| `AGGREGATION` 快速汇聚 | 某地址在长度为 `timeWindowMillis` 的滑动窗口内收到来自不少于 `minCounterparties` 个不同来源的转账（自转账不计）。每个地址最多报告一次，锚定最早满足条件的窗口。 |
| `ADDRESS_REUSE` 地址复用 | 某地址在滑动窗口 `timeWindowMillis` 内**既有转入又有转出**，且涉及的不同对手方总数不少于 `minCounterparties`（自转账不计）。每个地址最多报告一次。 |

所有规则只使用 `amount >= minAmount` 的转账。

### 命中输出

```json
{
  "rule": "CYCLE",
  "addresses": ["a", "b", "c"],
  "path": ["a", "b", "c", "a"],
  "txIds": ["t1", "t2", "t3"],
  "summary": "cycle of 3 address(es): a->b->c->a"
}
```

- `addresses`：相关地址，字典序排序。
- `path`：证据路径（环路径 / 拆分链 / 来源列表 + 目标地址）。
- `txIds`：证据转账 ID，字典序排序。
- `summary`：人类可读的证据摘要。

命中列表按 `(rule, addresses, txIds)` 规范排序，因此对同一组输入
（无论到达顺序如何、是否乱序重复），输出序列完全可复现；`seed` 参数
预留给需要打散并列顺序的场景，默认 0 时输出为规范序。

## 配置项

| 参数 | 默认值 | 含义 |
|------|--------|------|
| `minAmount` | 1 | 金额阈值，低于该值的转账不参与检测 |
| `maxHops` | 6 | 环搜索跳数上限 / 拆分链层数上限 |
| `timeWindowMillis` | 86400000 | 汇聚 / 复用 / 拆分的时间窗口 |
| `minCounterparties` | 3 | 汇聚来源数 / 拆分目标数 / 复用对手方数下限 |
| `seed` | 0 | 固定种子，预留用于确定性并列排序 |

服务级配置（`application.properties`）：

| 配置 | 默认值 | 含义 |
|------|--------|------|
| `fundflow.wal.path` | `data/fundflow.wal` | 预写日志文件路径，用于重启恢复 |

## REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/transfers` | 批量提交转账记录（JSON 数组），原子生效；返回 `{accepted, duplicates, rejected}` |
| `DELETE` | `/api/transfers/{txId}` | 删除/回滚一笔转账；返回是否实际删除 |
| `GET` | `/api/risks` | 以当前图状态运行全部规则；支持 `minAmount`、`maxHops`、`timeWindowMillis`、`minCounterparties`、`seed` 查询参数覆盖默认值 |
| `GET` | `/api/graph/stats` | 图统计 `{addresses, edges}` |

示例：

```bash
curl -X POST localhost:8080/api/transfers -H 'Content-Type: application/json' -d '[
  {"txId":"t1","from":"a","to":"b","amount":100,"timestamp":1000},
  {"txId":"t2","from":"b","to":"c","amount":100,"timestamp":2000},
  {"txId":"t3","from":"c","to":"a","amount":100,"timestamp":3000}
]'
curl 'localhost:8080/api/risks?maxHops=5'
curl -X DELETE localhost:8080/api/transfers/t3
```

## 一致性与持久化设计

- **原子提交**：每次 `apply`/`delete` 先以 JSON 行整体写入 WAL 并 fsync，
  再修改内存图。批次要么完整生效，要么完全不生效。
- **并发**：读写使用公平 `ReentrantReadWriteLock`。写入互斥且原子；
  查询持读锁，永远不会读到半更新的图，也必然看到所有已提交的更新。
- **重启恢复**：启动时按写入顺序重放 WAL（含删除记录）。打开 WAL 时会
  截断末尾的撕裂行（崩溃残留），未提交更新不会产生部分命中。
- **回滚**：`DELETE /api/transfers/{txId}` 从图中移除该边并写入 WAL。
  回滚后图状态与风险命中与"从未写入该记录"完全一致；同一 `txId`
  之后可以重新写入。

## 测试

`src/test/java/.../tests/` 下共 29 个用例：

| 测试类 | 覆盖场景 |
|--------|----------|
| `FlowGraphTest` | 图增删、去重、确定性排序、非法记录 |
| `RuleDetectionTest` | 四类规则命中、参数（阈值/跳数/窗口/数量）敏感性、重复计算一致性 |
| `DeterminismTest` | 乱序输入 + 固定种子输出可复现、重复记录幂等、迟到记录 |
| `ConcurrencyTest` | 多线程写入与查询并存，验证无半更新可见、最终状态与顺序执行一致 |
| `RecoveryTest` | 重启恢复、删除持久化、撕裂行截断、空 WAL |
| `RollbackTest` | 回滚后状态与命中和从未写入一致、回滚后可重写 |
| `ApiIntegrationTest` | REST 端到端：提交、幂等、查询、参数覆盖、回滚 |
