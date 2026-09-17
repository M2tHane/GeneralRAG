# Aurora Cloud 数据库服务指南

本文说明 Aurora Cloud 托管数据库（Managed Database）的默认连接池配置与常见调优项。托管 MySQL 与托管 PostgreSQL 实例默认使用同一套连接池参数。

## 默认连接池配置

Aurora 数据库连接池默认配置如下：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| maximumPoolSize | 20 | 连接池最大连接数 |
| idleTimeout | 600000 ms | 空闲连接最大保留时间，超时后连接被回收 |
| cachePrepStmts | true | 是否缓存 PreparedStatement，默认开启 |

- **idleTimeout** 控制空闲连接最大保留时间，默认 600000 ms（10 分钟）。超过该时长仍空闲的连接会被连接池回收，避免数据库侧累积无用会话。
- **cachePrepStmts** 默认开启，用于缓存 PreparedStatement，降低重复预编译语句的开销。业务侧高频执行同类 SQL 时建议保持开启。
- **maximumPoolSize** 默认 20。应用实例数较多时，应按 `实例数 × maximumPoolSize` 评估数据库侧连接上限，必要时调小该值。

## 调优建议

连接池参数属于实例级配置，修改后对新建立的连接生效。生产实例调整前应先在预发环境验证，避免突发流量下出现连接耗尽。
