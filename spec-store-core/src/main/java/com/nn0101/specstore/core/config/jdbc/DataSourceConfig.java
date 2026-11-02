package com.nn0101.specstore.core.config.jdbc;

import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.pool.DruidDataSource;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.encrypt.config.EncryptRuleConfiguration;
import org.apache.shardingsphere.encrypt.config.rule.EncryptColumnItemRuleConfiguration;
import org.apache.shardingsphere.encrypt.config.rule.EncryptColumnRuleConfiguration;
import org.apache.shardingsphere.encrypt.config.rule.EncryptTableRuleConfiguration;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 数据源配置，对应 {@code sharding.*} 配置段。
 *
 * <p>提供三项能力，其中分库分表默认关闭：
 * <ol>
 *   <li><b>读写分离</b>：{@link Group} 里的 {@code master} 承接写、{@code slaves} 承接读；</li>
 *   <li><b>字段加密</b>：{@link Encrypt} 指定要加密的表与列；</li>
 *   <li><b>分库分表</b>：{@link Split} 打开开关后，按每张表自己的分片字段自动分库/分表。</li>
 * </ol>
 *
 * <p>配置结构本身就是分组的，因此嵌套类型都放在这里，不再单独建文件：
 * <pre>
 * sharding:
 *   pool:          {@link Pool}         连接池公共参数
 *   single-tables: [...]                单表规则覆盖范围
 *   datasources:   {@link Group}[]      读写分离组
 *   encrypt:       {@link Encrypt}      字段加密
 *   split:         {@link Split}        分库分表（默认关闭）
 * </pre>
 */
@Slf4j
@Getter
@Setter
@ConfigurationProperties(prefix = "sharding")
public class DataSourceConfig {

    /**
     * 目前唯一支持的连接池实现。
     */
    public static final String DRUID_DATA_SOURCE_CLASS = "com.alibaba.druid.pool.DruidDataSource";

    /**
     * 所有物理数据源共用的连接池参数。
     */
    private Pool pool = new Pool();

    /**
     * 单表规则覆盖范围，格式 {@code 读写分离组名.schema.*} 或 {@code 读写分离组名.表名}。
     *
     * <p>留空时由 {@code ShardingSphereConfig} 自动填成
     * {@code 第一个组的组名.*.*}，即"所有业务表都归第一个组"。
     *
     * <p>不建议写成 {@code *.*}：那会把各组里的同名表都登记成同一张表的多个数据节点，
     * 路由时可能落到别的组，写库就不再固定是第一个组的主库了。
     */
    private List<String> singleTables = new ArrayList<>();

    /**
     * 读写分离组列表，每组一个主库 + 若干从库。
     */
    private List<Group> datasources = new ArrayList<>();

    /**
     * 字段加密配置。
     */
    private Encrypt encrypt = new Encrypt();

    /**
     * 分库分表配置，默认关闭。
     */
    private Split split = new Split();

    /**
     * 构建 {@code 物理数据源名 -> DataSource} 映射，主库在前、从库在后，保证顺序稳定。
     */
    public Map<String, DataSource> buildDataSourceMap() {
        Map<String, DataSource> dataSourceMap = new LinkedHashMap<>();
        for (Group group : datasources) {
            if (group.getMaster() == null) {
                String groupName = group.getMasterSlaveName();
                throw new IllegalStateException("读写分离组 [" + (groupName == null ? "未命名" : groupName)
                        + "] 缺少 master 配置");
            }
            putIfAbsent(dataSourceMap, group.getMaster());
            if (group.getSlaves() != null) {
                group.getSlaves().forEach(slave -> putIfAbsent(dataSourceMap, slave));
            }
        }
        if (dataSourceMap.isEmpty()) {
            throw new IllegalStateException("未配置任何数据源，请检查 sharding.datasources");
        }
        return dataSourceMap;
    }

    /**
     * 读写分离组名：优先用 {@code master-slave-name}，未配置时退回 master 数据源名。
     */
    public static String resolveGroupName(Group group) {
        String groupName = group.getMasterSlaveName();
        if (groupName == null || groupName.isBlank()) {
            return group.getMaster().getName();
        }
        return groupName;
    }

    private void putIfAbsent(Map<String, DataSource> dataSourceMap, DataSourceProps prop) {
        if (prop == null || prop.getName() == null || prop.getName().isBlank()) {
            throw new IllegalStateException("数据源名称不能为空，请检查 sharding.datasources");
        }
        if (dataSourceMap.containsKey(prop.getName())) {
            log.warn("数据源 [{}] 重复配置，忽略后出现的定义", prop.getName());
            return;
        }
        dataSourceMap.put(prop.getName(), createDataSource(prop));
    }

    private DataSource createDataSource(DataSourceProps prop) {
        if (prop.getType() != null && !DRUID_DATA_SOURCE_CLASS.equals(prop.getType())) {
            throw new IllegalStateException("数据源 [" + prop.getName() + "] 的 type 只支持 "
                    + DRUID_DATA_SOURCE_CLASS + "，实际为 " + prop.getType());
        }
        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setName(prop.getName());
        dataSource.setUrl(prop.getJdbcUrl());
        dataSource.setUsername(prop.getUsername());
        dataSource.setPassword(prop.getPassword());
        if (prop.getDriverClassName() != null && !prop.getDriverClassName().isBlank()) {
            dataSource.setDriverClassName(prop.getDriverClassName());
        }
        dataSource.setMaxActive(pool.getMaxActive());
        dataSource.setMinIdle(pool.getMinIdle());
        dataSource.setInitialSize(pool.getInitialSize());
        dataSource.setMaxWait(pool.getMaxWait());
        dataSource.setTimeBetweenEvictionRunsMillis(pool.getTimeBetweenEvictionRunsMillis());
        dataSource.setMinEvictableIdleTimeMillis(pool.getMinEvictableIdleTimeMillis());
        dataSource.setValidationQuery(pool.getValidationQuery());
        dataSource.setTestWhileIdle(pool.isTestWhileIdle());
        dataSource.setTestOnBorrow(pool.isTestOnBorrow());
        dataSource.setTestOnReturn(pool.isTestOnReturn());
        dataSource.setLogAbandoned(pool.isLogAbandoned());
        dataSource.setRemoveAbandoned(pool.isRemoveAbandoned());
        dataSource.setRemoveAbandonedTimeout(pool.getRemoveAbandonedTimeout());
        dataSource.setProxyFilters(List.of(statFilter()));
        try {
            dataSource.init();
        } catch (SQLException e) {
            throw new IllegalStateException("初始化数据源 [" + prop.getName() + "] 失败: " + e.getMessage(), e);
        }
        log.info("初始化数据源 [{}] -> {}", prop.getName(), prop.getJdbcUrl());
        return dataSource;
    }

    private StatFilter statFilter() {
        StatFilter statFilter = new StatFilter();
        statFilter.setSlowSqlMillis(5000L);
        statFilter.setLogSlowSql(true);
        statFilter.setMergeSql(true);
        return statFilter;
    }

    /**
     * 连接池公共参数。
     */
    @Getter
    @Setter
    public static class Pool {

        private int maxActive = 20;

        private int minIdle = 10;

        private int initialSize = 5;

        private int maxWait = 10000;

        private int timeBetweenEvictionRunsMillis = 60000;

        private int minEvictableIdleTimeMillis = 1800000;

        private String validationQuery = "SELECT 1";

        private boolean testWhileIdle = true;

        private boolean testOnBorrow = false;

        private boolean testOnReturn = false;

        private boolean logAbandoned = true;

        private boolean removeAbandoned = true;

        private int removeAbandonedTimeout = 300;
    }

    /**
     * 一个读写分离组。
     */
    @Getter
    @Setter
    public static class Group {

        /**
         * 读写分离组名，也是逻辑数据源名；缺省时取 master 的名称。
         */
        private String masterSlaveName;

        /**
         * 主库，承接写操作。
         */
        private DataSourceProps master;

        /**
         * 从库列表，承接读操作，可为空。
         */
        private List<DataSourceProps> slaves = new ArrayList<>();
    }

    /**
     * 一个物理数据源的连接信息。
     */
    @Getter
    @Setter
    public static class DataSourceProps {

        private String name;

        /**
         * 连接池实现类，目前只支持 Druid；配成其它值会直接启动失败，避免静默降级。
         */
        private String type = DRUID_DATA_SOURCE_CLASS;

        private String jdbcUrl;

        private String username;

        private String password;

        private String driverClassName = "org.postgresql.Driver";
    }

    /**
     * 字段加密配置。
     *
     * <p>默认使用 {@link PostgresqlAesEncryptAlgorithm}（算法类型 {@code postgresql}）：
     * AES/ECB + 十六进制密文，物理列存字符串。
     */
    @Getter
    @Setter
    public static class Encrypt {

        /**
         * 加密算法在规则中的名称，列配置通过它引用算法。
         */
        public static final String ENCRYPTOR_NAME = "aes";

        private boolean enabled = true;

        /**
         * 算法类型：{@code postgresql} 走 {@link PostgresqlAesEncryptAlgorithm}；
         * 也可改成 ShardingSphere 内置的 {@code AES}（Base64 密文，密钥需先经摘要）。
         */
        private String algorithmType = PostgresqlAesEncryptAlgorithm.TYPE;

        /**
         * AES 原始密钥，按 {@code US-ASCII} 折叠成 16 字节密钥，不做摘要。
         */
        private String aesKey;

        /**
         * 表名 -> 需要加密的列名集合。
         */
        private Map<String, List<String>> tables = new LinkedHashMap<>();

        /**
         * 构建加密规则；未启用或未配置任何表时返回 {@code null}，表示不注册加密规则。
         */
        public EncryptRuleConfiguration buildEncryptRule() {
            if (!enabled) {
                log.warn("字段加密未启用（sharding.encrypt.enabled=false），敏感字段将以明文落库");
                return null;
            }
            if (tables.isEmpty()) {
                log.warn("未配置任何加密表（sharding.encrypt.tables），跳过字段加密规则");
                return null;
            }
            if (aesKey == null || aesKey.isBlank()) {
                throw new IllegalStateException("已启用字段加密但未配置 sharding.encrypt.aes-key");
            }
            Properties props = new Properties();
            props.setProperty(PostgresqlAesEncryptAlgorithm.AES_KEY_PROPERTY, aesKey);
            Map<String, AlgorithmConfiguration> encryptors = new LinkedHashMap<>();
            encryptors.put(ENCRYPTOR_NAME, new AlgorithmConfiguration(algorithmType, props));
            Collection<EncryptTableRuleConfiguration> tableRules = new LinkedList<>();
            tables.forEach((table, columns) -> {
                if (columns == null || columns.isEmpty()) {
                    return;
                }
                Collection<EncryptColumnRuleConfiguration> columnRules = new LinkedList<>();
                for (String column : columns) {
                    // 密文与逻辑列同名：同一列里存密文，查询时由 ShardingSphere 自动解密
                    columnRules.add(new EncryptColumnRuleConfiguration(column,
                            new EncryptColumnItemRuleConfiguration(column, ENCRYPTOR_NAME)));
                }
                tableRules.add(new EncryptTableRuleConfiguration(table, columnRules));
                log.info("加密表 [{}] 列 {}", table, columns);
            });
            if (tableRules.isEmpty()) {
                return null;
            }
            log.info("字段加密算法 [{}]", algorithmType);
            return new EncryptRuleConfiguration(tableRules, encryptors);
        }
    }

    /**
     * 分库分表配置，默认关闭。
     *
     * <p>每张表各自声明分片键、分库数与分表数，不同表可以按不同规则切分。
     */
    @Getter
    @Setter
    public static class Split {

        /**
         * 分库分表总开关，默认关闭。关闭时所有业务表都由单表规则托管，落第一个读写分离组。
         */
        private boolean enabled = false;

        /**
         * 参与分片的表清单，每张表自带分片规则。
         */
        private List<SplitTable> tables = new ArrayList<>();

        /**
         * 是否存在真正需要分片的表：开关打开且至少有一张表的分库数或分表数大于 1。
         *
         * <p>只开开关、各表分库分表数都是 1 时不做任何分片，行为与关闭一致。
         */
        public boolean isActive() {
            if (!enabled) {
                return false;
            }
            return tables.stream().anyMatch(SplitTable::isActive);
        }
    }

    /**
     * 一张分片表的分片规则。
     */
    @Getter
    @Setter
    public static class SplitTable {

        /**
         * 分表名的分隔符：逻辑表 {@code t_tenant} 的第 0 张分表是 {@code t_tenant_0}。
         */
        public static final String TABLE_NAME_SEPARATOR = "_";

        /**
         * 逻辑表名。
         */
        private String name;

        /**
         * 分片键（数据库列名），分库与分表都按它取模。
         */
        private String shardingColumn = "id";

        /**
         * 分库数，1 表示不分库；大于 1 时这张表分布到前 N 个读写分离组。
         */
        private int databaseCount = 1;

        /**
         * 分表数，1 表示不分表；大于 1 时实际表名为 {@code 逻辑表名_0 … 逻辑表名_(N-1)}。
         */
        private int tableCount = 1;

        /**
         * 这张表是否真的需要分片：分库数或分表数大于 1。
         */
        public boolean isActive() {
            return databaseCount > 1 || tableCount > 1;
        }

        /**
         * 第 {@code tableIndex} 张分表的实际表名；不分表时就是逻辑表名本身。
         */
        public String actualTable(int tableIndex) {
            return tableCount > 1 ? name + TABLE_NAME_SEPARATOR + tableIndex : name;
        }
    }
}
