# Api Spec Store

> 拒绝随意写接口。集中管理 API 规范文档，通过强校验与流程门禁，让接口设计有章可循。

## 技术栈

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17 | 编译目标 `release 17` |
| Spring Boot | 4.1.1 | Spring Framework 7.0.9，Servlet 6（jakarta） |
| ShardingSphere | 5.5.1 | **读写分离 + 字段加密 + 可开关的分库分表**（分库分表默认关闭） |
| PostgreSQL | 17.x | 本地 `127.0.0.1:5432`，库 `spec_store` |
| Druid | 1.2.28 | 物理连接池 + 监控页 |
| MyBatis-Plus | 3.5.18.1 | `mybatis-plus-spring-boot4-starter`（适配 Boot 4） |

## 数据库架构

```
            ┌──────────────────────────────┐
            │  ShardingSphere 逻辑库        │
            │  spec_store（唯一 DataSource）│
            └───────────────┬──────────────┘
                            │
        ┌───────────────────┴───────────────────┐
        │                                       │
   ┌────▼──────────┐                        ┌────▼──────────┐
   │ 组 datasource0│                        │ 组 datasource1│
   ├───────────────┤                        ├───────────────┤
   │ 写 ds0        │                        │ 写 ds1        │
   │ 读 ds0slave0  │                        │ 读 ds1slave0  │
   └───────────────┘                        └───────────────┘
```

- `sharding.datasources` 里每一项 = 一个**读写分离组**，组内 `master` 承接写、`slaves` 承接读。
- 读写分离组名取组上的 `master-slave-name`（缺省用 master 的数据源名），
  即 `datasource0` / `datasource1`；负载均衡算法为 `ROUND_ROBIN`。
- 默认（不启用分库分表）不注册 `ShardingRuleConfiguration`；
  `SingleRuleConfiguration` 默认声明 `datasource0.*.*`（第一个组下的所有表都是单表），
  既避免 `SingleTableNotFoundException`，也保证写操作固定落在 `datasource0` 的主库 `ds0`。
- **不要**把 `sharding.single-tables` 写成 `*.*`：那会把 `ds0` 与 `ds1` 里的同名表
  登记成同一张表的多个数据节点，单表路由会随机挑一个，写库就不再固定是 `ds0`。
- 需要把某张表放到第二个组时，写成 `datasource1.t_xxx` 这类显式组名 + 表名。

## 分库分表（可开关，默认关闭）

本项目当前**不需要**分库分表，因此默认关闭；开关打开后才注册 `ShardingRuleConfiguration`，
按每张表自己的分片键自动取模路由，业务代码完全不用改。

```yaml
sharding:
  split:
    enabled: true                 # 总开关；false 时所有表由单表规则托管，只落 datasource0
    tables:
      - name: t_order             # 逻辑表名（必填）
        sharding-column: id       # 这张表的分片键，分库与分表都按它取模
        database-count: 1         # 这张表的分库数，1=不分库
        table-count: 4            # 这张表的分表数，1=不分表
      - name: t_tenant
        sharding-column: tenant_id
        database-count: 2         # 每张表可以有不同的分片规则
        table-count: 1
```

分片参数挂在**每张表**上，因此不同表可以按不同列、不同数量切分；
只写 `name` 而 `database-count` / `table-count` 都是 1 的表会被跳过（日志提示），
仍然走单表规则。

- **只开分表**（`database-count: 1`）：所有分表都在第一个组里，实际表名 `t_order_0…3`。
- **只开分库**（`table-count: 1`）：表名不变，行按 `id % 2` 分散到 `datasource0` / `datasource1`。
- **同时开启**：共 `database-count × table-count` 个数据节点，先按库取模再按表取模。
- 算法按表注册（`表名_databaseMod` / `表名_tableMod`），不同表的分片数不会互相覆盖。
- 分片路由 `getOrder()==0` 先于单表路由 `getOrder()==10`，被分片的逻辑表不会被单表规则抢走；
  `SingleTableLoadUtils.getExcludedTables()` 也会把分片表从单表扫描中剔除。
- 不配分片键的查询会广播到全部分片并归并结果（`SELECT COUNT(*)` 会变成 `UNION ALL`）。
- 分片算法用自研的 `ModIndexShardingAlgorithm`（`CLASS_BASED` 注入），
  **不用内置 `MOD`**：内置 `MOD` 属于 `ShardingAutoTableAlgorithm`，只能配在 `autoTables`，
  配到普通 `tables` 会直接启动失败（`tables sharding configuration can not use auto sharding algorithm`）。
  `ModIndexShardingAlgorithm` 对候选名排序后取 `分片键 % 候选数量`，与组名是
  `datasource0` 还是 `ds0` 无关。
- 物理分表需**先于应用启动**建好（ShardingSphere 启动时加载表元数据）。

验证：`mvn test "-Dtest=ShardingSplitTest"`（4 张分表，断言 `id=5` 只落在 `t_order_1`）。

## 字段加密

`sharding.encrypt` 配置需要加密的表与列，ShardingSphere 在写入时自动加密、查询时自动解密，
业务代码看到的一直是明文。

```yaml
sharding:
  encrypt:
    enabled: true
    algorithm-type: postgresql         # 对应 PostgresqlAesEncryptAlgorithm
    aes-key: SpecStore@2026#AesKey     # 生产环境请用环境变量/Jasypt 注入
    tables:
      t_tenant: [phone, email]
      t_tenant_key: [secret]
```

加密算法用的是自实现的 `PostgresqlAesEncryptAlgorithm`（[源码](spec-store-core/src/main/java/com/nn0101/specstore/core/config/jdbc/PostgresqlAesEncryptAlgorithm.java)），
加密规则如下：

| 项 | 规则 |
| --- | --- |
| 密钥派生 | `aes-key` 的 US-ASCII 字节循环异或折叠进 16 字节缓冲区（**不做摘要**） |
| 加密算法 | `Cipher.getInstance("AES")`，即 JDK 默认的 `AES/ECB/PKCS5Padding` |
| 密文编码 | **小写十六进制字符串**（`Hex.encodeHexString`），不是 Base64 |

- PostgreSQL 物理列用 `varchar` / `text`，长度要能容纳十六进制密文（是明文的两倍），密文按字符串比较。
- ECB 是确定性加密：相同明文永远得到相同密文，因此 `=` 等值查询可用，`LIKE` 与范围查询不可用。
  `PostgresqlAesEncryptAlgorithm` 的 `EncryptAlgorithmMetaData` 声明为
  `(supportDecrypt=true, supportEquivalentFilter=true, supportLike=false)`。
- 为什么不用 ShardingSphere 内置的 `AES`：它的密文是 Base64、密钥先经摘要算法再取前 16 字节，
  落库格式与本算法的十六进制不同。若要切到内置实现，把 `algorithm-type` 改成 `AES`
  并补上 `digest-algorithm-name: MD5` 即可，但存量数据需要重新加密。
- 算法通过 `META-INF/services/org.apache.shardingsphere.encrypt.spi.EncryptAlgorithm` 注册，
  所以配置里写 `algorithm-type: postgresql` 就能被 `TypedSPILoader` 找到（类型匹配用 `equalsIgnoreCase`）。

已知密文对照（密钥 `SpecStore@2026#AesKey`，测试里已断言，可用来校验环境一致性）：

```
13800001111      -> 58312261f62300f3c9ece15211b25d34
alice@example.com -> 1a79489d6976f7ff6e9640888806022018e220f609e06d7cf92788965cd8d276
```

## 运行时验证

集成测试 `ShardingSphereDataSourceTest` 会做两件事：

1. 通过逻辑数据源写入明文，再用**原生 JDBC 直连物理库**读取同一字段，断言物理侧是密文；
2. 断言容器内的 `dataSource` 是 `ShardingSphereDataSource`。

实际日志里可以看到读写分离与加密同时生效：

```
Actual SQL: ds0       ::: INSERT INTO t_tenant (...) VALUES (..., '58312261f62300f3c9ece15211b25d34', ...)
Actual SQL: ds0slave0 ::: SELECT tenant_name, phone AS phone, email AS email FROM t_tenant WHERE ...
```

写走 `ds0`，读走 `ds0slave0`，`phone` 落库为小写十六进制密文。

```bash
cd spec-store-core
mvn test "-Dtest=ShardingSphereDataSourceTest"
```

## 本地启动

准备：JDK 17、PostgreSQL 17 已启动（`root/123456`），并已创建库与表：

```sql
CREATE DATABASE spec_store WITH ENCODING 'UTF8' TEMPLATE template0;

CREATE TABLE t_tenant (
    id bigserial PRIMARY KEY,
    tenant_name varchar(128) NOT NULL,
    phone varchar(512),
    email varchar(512),
    status smallint NOT NULL DEFAULT 1,
    created_time timestamp NOT NULL DEFAULT now()
);

CREATE TABLE t_tenant_key (
    id bigserial PRIMARY KEY,
    tenant_id bigint NOT NULL,
    key_name varchar(128) NOT NULL,
    secret varchar(512),
    created_time timestamp NOT NULL DEFAULT now()
);
```

启动：

```bash
cd spec-store-core
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

> PowerShell 下 `-D` 参数必须加引号，否则会被拆成多个参数；`spring-boot:run` 需要在模块目录内执行。

- 服务地址：<http://127.0.0.1:1010/spec-store>
- Druid 监控：<http://127.0.0.1:1010/spec-store/druid/index.html>（`admin` / `admin`，
  仅允许 `127.0.0.1` 访问，可用 `druid.monitor.*` 调整或 `druid.monitor.enabled=false` 关闭）

## 目录约定

```
spec-store-core/src/main/
├── java/com/nn0101/specstore/core/config/jdbc/
│   ├── DataSourceConfig.java             # sharding.* -> 物理数据源 / 分组 / 加密 / 分片开关（含 5 个嵌套配置类型）
│   ├── ShardingSphereConfig.java         # 读写分离 + 分库分表 + 单表 + 加密规则装配，产出唯一的逻辑 DataSource
│   ├── ModIndexShardingAlgorithm.java    # 自研 CLASS_BASED 分片算法（分片键 % 候选数量）
│   ├── PostgresqlAesEncryptAlgorithm.java # 自研加密算法（AES/ECB + 十六进制密文，适配 PostgreSQL）
│   └── DruidConfig.java                  # Druid 监控页与 Web 统计过滤器（jakarta 版）
└── resources/META-INF/services/
    └── org.apache.shardingsphere.encrypt.spi.EncryptAlgorithm   # 把 PostgresqlAesEncryptAlgorithm 注册给 ShardingSphere
```

`DataSourceConfig` 把原来分散的 6 个属性类合并为 6 个静态嵌套类，配置结构与
`application-dev.yml` 的层次一一对应：

| 嵌套类 | 对应配置 | 职责 |
| --- | --- | --- |
| `Pool` | `sharding.pool` | 所有物理数据源共用的连接池参数 |
| `Group` | `sharding.datasources[]` | 一个读写分离组：`master-slave-name` + `master` + `slaves` |
| `DataSourceProps` | `sharding.datasources[].master/.slaves[]` | 一个物理库的连接信息（`name` / `type` / `jdbcUrl` / `driver-class-name` / `username` / `password`） |
| `Encrypt` | `sharding.encrypt` | 加密表列清单，`buildEncryptRule()` 产出加密规则 |
| `Split` | `sharding.split` | 分库分表总开关 + 表清单 |
| `SplitTable` | `sharding.split.tables[]` | 单张表的分片规则：`name` / `sharding-column` / `database-count` / `table-count` |

`sharding.single-tables`（未嵌套类，直接是 `List<String>`）建议留空——留空时默认取
`第一个组名.*.*`；如需把某些表放到别的组，再显式写 `datasource1.t_xxx`。
