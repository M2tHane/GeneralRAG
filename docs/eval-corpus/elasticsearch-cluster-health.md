<!--
来源：https://www.elastic.co/guide/en/elasticsearch/reference/8.14/cluster-health.html
      https://www.elastic.co/docs/reference/elasticsearch/configuration-reference/cluster-level-shard-allocation-routing-settings
抓取时间：2026-09-14
说明：本文件为评测语料，内容整理自 Elasticsearch 官方文档（8.14），仅用于本地知识库评测测试，不用于商业分发。
-->

# Elasticsearch 集群健康与磁盘水位

## 1. 集群健康状态

集群健康 API（`GET /_cluster/health`）返回 green、yellow、red 三级状态：

- **green**：所有分片（主分片和副本分片）均已分配。
- **yellow**：所有主分片已分配，但存在未分配的副本分片。若集群中某个节点故障，部分数据在修复前可能不可用。
- **red**：存在未分配的主分片，部分数据不可用。集群启动阶段主分片分配过程中可能短暂出现 red。

索引级别的状态由该索引下最差的分片状态决定；集群级别的状态由最差的索引状态决定。

健康 API 的常用参数：

- `wait_for_status`（green/yellow/red）+ `timeout`：等待集群达到某状态，例如 `GET /_cluster/health?wait_for_status=yellow&timeout=50s`。
- `level`：`cluster`（默认）/ `indices` / `shards`，按层级展开健康信息。
- 响应中与排查相关的字段：`unassigned_shards`（未分配分片数）、`initializing_shards`、`relocating_shards`、`active_shards_percent_as_number`。

## 2. 磁盘水位三阶段

基于磁盘的分片分配有两个目的：保证节点不会写满磁盘，以及让分片尽量落在磁盘空间充足的节点上。默认水位分三阶段：

| 阶段 | 设置项 | 默认值 | 触发行为 |
| --- | --- | --- | --- |
| 低水位 | `cluster.routing.allocation.disk.watermark.low` | **85%** | 超过后不再向该节点分配新分片 |
| 高水位 | `cluster.routing.allocation.disk.watermark.high` | **90%** | ES 开始把分片迁移到磁盘使用率较低的节点 |
| 洪泛水位 | `cluster.routing.allocation.disk.watermark.flood_stage` | **95%** | 节点上的索引被加上 `index.blocks.read_only_allow_delete` 块，**拒绝写入**（仅允许删除） |

水位也可以配置为绝对值（如 `500gb`）。注意：磁盘使用率是节点级别的统计，多数据路径节点上可按单盘计算。

## 3. 磁盘超限的处理步骤

1. 清理磁盘空间（删除旧索引、过期数据、日志等），或对确实需要放宽的集群调整水位设置：

```json
PUT _cluster/settings
{
  "persistent": {
    "cluster.routing.allocation.disk.watermark.low": "90%",
    "cluster.routing.allocation.disk.watermark.high": "95%",
    "cluster.routing.allocation.disk.watermark.flood_stage": "97%"
  }
}
```

2. 磁盘恢复后，flood_stage 加上的只读块**不会自动解除**，必须手动恢复：

```json
PUT /<索引名>/_settings
{ "index.blocks.read_only_allow_delete": null }
```

3. 对此前因磁盘超限而分配失败的分片，重试分配：

```json
POST _cluster/reroute?retry_failed=true
```

## 4. 未分配分片的排查

- `GET _cat/shards?v&h=index,shard,prirep,state,unassigned.reason` 定位未分配分片及其原因（如 `CLUSTER_RECOVERED`、`ALLOCATION_FAILED`、`NODE_LEFT`）。
- `GET _cluster/allocation/explain`（可带分片条件）返回最详细的无法分配解释，常见原因包括：副本数大于节点数、磁盘水位超限、节点离线。
- 另一个常见原因：**索引的副本分片数大于集群节点数**，副本永远无法分配，集群长期处于 yellow——这不是故障，需按容量规划调整 `number_of_replicas`。
