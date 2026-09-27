# fund-flow-risk-java

本地资金流风险检测服务。接收地址之间的转账记录，维护有向资金流图，
识别**环形回流、分层拆分、快速汇聚、地址复用**四类风险模式，
支持增量写入、并发查询、回滚与重启恢复。

## 构建与运行

```bash
mvn compile          # 编译
mvn test             # 运行全部测试
mvn spring-boot:run  # 启动服务（默认 8080 端口）
```

## 图模型

- **节点**：地址（字符串）。
- **边**：一条转账记录 `TransferRecord(id, from, to, amount, timestamp)`，
  方向为 `from -> to`，金额 `amount` 为精确十进制（BigDecimal），
  `timestamp` 为 epoch 毫秒。
- 边以记录 `id` 为唯一键索引：
  - **幂等去重**：相同 `id` 重复写入不产生重复边（首个写入生效）；
  - **可回滚**：按 `id` 删除即移除对应边，删除不存在的 `id` 为 no-op；
  - **乱序无关**：邻接表按 `(timestamp, id)` 排序维护，任何写入顺序
    得到完全相同的图状态与检测结果。

## 规则语义

检测在查询时对当前已提交图状态做纯函数式计算，命中结果按 `hitKey`
字典序输出，同一图状态与参数下输出完全可复现。

| 规则 | 类型 | 语义 |
| --- | --- | --- |
| 环形回流 | `CYCLE_BACKFLOW` | 存在有向环（≥2 跳，排除自转账），环上每笔金额 ≥ `minAmount`，跳数 ≤ `maxHops`，环上首尾时间跨度 ≤ `timeWindowMillis`。每个环以其最小地址为起点规范化，只报告一次。 |
| 分层拆分 | `LAYERED_SPLIT` | 根地址在窗口 `timeWindowMillis` 内向 ≥ `fanOut` 个不同地址转出（每笔 ≥ `minAmount`），且其中至少一个地址在收款后的窗口内继续向 ≥ `fanOut` 个不同地址转出。需 `maxHops ≥ 2`。每个根地址报告一次。 |
| 快速汇聚 | `RAPID_AGGREGATION` | 某地址在窗口 `timeWindowMillis` 内从 ≥ `fanIn` 个不同来源收款，且窗口内入账总额 ≥ `minAmount`。每个地址报告一次。 |
| 地址复用 | `ADDRESS_REUSE` | 同一地址作为收付双方，与 ≥ `minCounterparties` 个不同对手方发生转账。每个地址报告一次。 |

命中输出 `RiskHit` 包含：`ruleType`（命中规则）、`addresses`（相关地址）、
`path`（路径，如环的地址序列）、`evidence`（证据摘要：跳数、时间跨度、
来源列表、涉及记录 id 等）、`hitKey`（稳定标识，用于去重与排序）。

## 配置项

### 规则参数（每次检测请求可独立指定，命中随参数变化可重复计算）

`POST /api/detect` 请求体 `RuleConfig`：

| 字段 | 默认 | 含义 |
| --- | --- | --- |
| `minAmount` | `1` | 金额阈值（环/拆分为单笔下限，汇聚为窗口总额下限） |
| `maxHops` | `6` | 跳数上限（环的最大边数；拆分要求 ≥2） |
| `timeWindowMillis` | `86400000` | 时间窗口（毫秒） |
| `fanOut` | `3` | 分层拆分每层最小不同目标数 |
| `fanIn` | `3` | 快速汇聚最小不同来源数 |
| `minCounterparties` | `5` | 地址复用最小不同对手方数 |

不传请求体时使用默认参数。

### 服务配置（application.properties）

| 键 | 默认 | 含义 |
| --- | --- | --- |
| `fundflow.wal-file` | `data/fundflow.wal` | WAL 持久化文件路径 |

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/transfers` | 批量写入转账记录，整批一个事务；返回 `added`（实际新增，去重后）与 `total` |
| `DELETE` | `/api/transfers/{id}` | 回滚一条记录；返回 `removed` 与 `total` |
| `POST` | `/api/detect` | 按请求体中的 `RuleConfig` 检测，返回确定性排序的命中列表 |
| `GET` | `/api/transfers` | 当前全部已提交记录（按 id 排序） |
| `GET` | `/api/stats` | 图统计（记录数） |

示例：

```bash
curl -X POST localhost:8080/api/transfers -H 'Content-Type: application/json' -d '[
  {"id":"t1","from":"A","to":"B","amount":100,"timestamp":100},
  {"id":"t2","from":"B","to":"C","amount":100,"timestamp":200},
  {"id":"t3","from":"C","to":"A","amount":100,"timestamp":300}
]'
curl -X POST localhost:8080/api/detect -H 'Content-Type: application/json' -d '{
  "minAmount":10,"maxHops":4,"timeWindowMillis":1000,"fanOut":2,"fanIn":3,"minCounterparties":3
}'
```

## 一致性与持久化设计

- **并发**：`ReentrantReadWriteLock`（公平模式）。写入/回滚持写锁，
  查询持读锁：查询读不到半更新图，也不会漏掉已完成提交的更新。
- **事务与持久化**：每批写入/删除先以一行 JSON 追加到 WAL 并 flush，
  再应用到内存图。崩溃时最后一行可能不完整，重启重放时无法解析的
  尾部行被忽略——已提交更新不丢失，未提交事务不产生部分命中。
- **回滚**：删除按记录 `id` 从图中移除对应边并追加 WAL，
  回滚后图状态与风险命中同从未写入该记录时一致。
- **确定性**：图遍历、命中去重与排序全部基于规范顺序（地址字典序、
  `(timestamp, id)`、`hitKey`），与写入顺序无关。对同一组乱序输入，
  输出集合与顺序完全可复现，可直接用于自动化测试断言。

## 测试

`src/test/java/.../tests/` 下覆盖：

- `RuleDetectionTest`：四类规则命中、阈值/窗口参数敏感性、输出有序性；
- `OutOfOrderDuplicateTest`：固定种子乱序+重复写入的状态与命中一致性、
  幂等去重、迟到记录与整批写入等价；
- `ConcurrencyTest`：多线程写入与并发查询并存，校验批次原子性与不漏更新；
- `RecoveryTest`：重启恢复已提交数据、忽略未提交尾部、空 WAL；
- `RollbackTest`：回滚后与从未写入一致、删除幂等。
