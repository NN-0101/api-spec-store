package com.nn0101.specstore.core.config.jdbc;

import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.infra.config.mode.ModeConfiguration;
import org.apache.shardingsphere.infra.config.rule.RuleConfiguration;
import org.apache.shardingsphere.readwritesplitting.config.ReadwriteSplittingRuleConfiguration;
import org.apache.shardingsphere.readwritesplitting.config.rule.ReadwriteSplittingDataSourceGroupRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.apache.shardingsphere.single.config.SingleRuleConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * ShardingSphere 5.5.1 数据源装配。
 *
 * <p>提供三项能力：
 * <ol>
 *   <li><b>读写分离</b>：{@code sharding.datasources} 里的每个组解析为一个读写分离组，
 *       写操作走组内 master，读操作按轮询走 slaves；没有 slaves 时退化为单库。</li>
 *   <li><b>字段加密</b>：由 {@link DataSourceConfig.Encrypt} 提供规则，写入自动加密、读取自动解密。</li>
 *   <li><b>分库分表</b>：由 {@link DataSourceConfig.Split} 控制，默认关闭；
 *       打开后按配置的分片字段自动取模分库/分表。</li>
 * </ol>
 *
 * <p>分库分表关闭时（默认）不注册 {@code ShardingRuleConfiguration}，
 * 单表规则 {@link SingleRuleConfiguration} 默认声明
 * {@code 第一个组名.*.*}（"第一个组里的所有表都是单表"），
 * 既避免单表路由抛 {@code SingleTableNotFoundException}，也保证写操作固定落在该组的主库。
 *
 * <p>分库分表打开后，被分片的逻辑表交给 {@code ShardingRuleConfiguration} 路由
 * （分片路由 order=0 先于单表路由 order=10），其余表仍由单表规则托管。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(DataSourceConfig.class)
@EnableTransactionManagement(proxyTargetClass = true)
public class ShardingSphereConfig {

    /**
     * 读写分离负载均衡算法名。
     */
    private static final String LOAD_BALANCER_NAME = "roundRobin";

    /**
     * 负载均衡算法类型，ShardingSphere 5.5.1 的 round-robin 算法 SPI 类型值。
     */
    private static final String LOAD_BALANCER_TYPE = "ROUND_ROBIN";

    /**
     * 全局唯一的逻辑库名。
     */
    private static final String LOGIC_DATABASE_NAME = "spec_store";

    /**
     * 单机模式，无需注册中心。
     */
    private static final String STANDALONE_MODE = "Standalone";

    /**
     * 通过类名反射加载的分片算法类型。
     *
     * <p>不用内置的 {@code MOD}：它实现了 {@code ShardingAutoTableAlgorithm}，
     * 只能配在 {@code autoTables} 上，配到普通 {@code tables} 会在启动时直接失败。
     */
    private static final String CLASS_BASED_ALGORITHM_TYPE = "CLASS_BASED";

    /**
     * {@code CLASS_BASED} 的策略类型，按下标取模属于标准策略。
     */
    private static final String CLASS_BASED_STRATEGY = "STANDARD";

    /**
     * 分片算法实现的类全名。
     */
    private static final String SHARDING_ALGORITHM_CLASS = ModIndexShardingAlgorithm.class.getName();

    /**
     * 分库算法名后缀。算法按表注册（{@code 表名 + 后缀}），避免不同表的分库数互相覆盖。
     */
    private static final String DATABASE_ALGORITHM_SUFFIX = "_databaseMod";

    /**
     * 分表算法名后缀。算法按表注册（{@code 表名 + 后缀}），避免不同表的分表数互相覆盖。
     */
    private static final String TABLE_ALGORITHM_SUFFIX = "_tableMod";

    private final DataSourceConfig dataSourceConfig;

    public ShardingSphereConfig(DataSourceConfig dataSourceConfig) {
        this.dataSourceConfig = dataSourceConfig;
    }

    /**
     * 物理数据源映射：{@code 物理数据源名 -> DataSource}。
     */
    @Bean("dataSourceMap")
    public Map<String, DataSource> dataSourceMap() {
        return dataSourceConfig.buildDataSourceMap();
    }

    /**
     * ShardingSphere 逻辑数据源，作为容器内唯一的 {@link DataSource}。
     */
    @Bean("dataSource")
    public DataSource dataSource(@Qualifier("dataSourceMap") Map<String, DataSource> dataSourceMap) throws SQLException {
        Collection<RuleConfiguration> ruleConfigs = new ArrayList<>();
        ruleConfigs.add(buildReadwriteSplittingRule());
        RuleConfiguration shardingRule = buildShardingRule();
        if (shardingRule != null) {
            ruleConfigs.add(shardingRule);
        }
        ruleConfigs.add(buildSingleRule());
        RuleConfiguration encryptRule = dataSourceConfig.getEncrypt().buildEncryptRule();
        if (encryptRule != null) {
            ruleConfigs.add(encryptRule);
        }
        Properties props = new Properties();
        props.setProperty("sql-show", Boolean.TRUE.toString());
        DataSource dataSource = ShardingSphereDataSourceFactory.createDataSource(LOGIC_DATABASE_NAME,
                new ModeConfiguration(STANDALONE_MODE, null), dataSourceMap, ruleConfigs, props);
        log.info("ShardingSphere 逻辑库 [{}] 初始化完成，规则数 {}", LOGIC_DATABASE_NAME, ruleConfigs.size());
        return dataSource;
    }

    /**
     * 事务管理器，必须绑定逻辑数据源，否则加密与读写分离在同一事务内不生效。
     */
    @Bean("transactionManager")
    public DataSourceTransactionManager transactionManager(@Qualifier("dataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * 每个配置组 = 一个读写分离组：组名取组的 {@code master-slave-name}（缺省为 master 名），
     * 写库取组内 master，读库取其 slaves。没有 slaves 时读写都落在 master。
     */
    private ReadwriteSplittingRuleConfiguration buildReadwriteSplittingRule() {
        Collection<ReadwriteSplittingDataSourceGroupRuleConfiguration> groups = new ArrayList<>();
        for (DataSourceConfig.Group group : dataSourceConfig.getDatasources()) {
            DataSourceConfig.DataSourceProps master = group.getMaster();
            String groupName = DataSourceConfig.resolveGroupName(group);
            List<String> readDataSourceNames = new ArrayList<>();
            if (group.getSlaves() != null) {
                group.getSlaves().stream()
                        .map(DataSourceConfig.DataSourceProps::getName)
                        .filter(name -> name != null && !name.isBlank())
                        .forEach(readDataSourceNames::add);
            }
            groups.add(new ReadwriteSplittingDataSourceGroupRuleConfiguration(groupName,
                    master.getName(), readDataSourceNames, LOAD_BALANCER_NAME));
            log.info("读写分离组 [{}] 写库={} 读库={}", groupName, master.getName(), readDataSourceNames);
        }
        Map<String, AlgorithmConfiguration> loadBalancers = new LinkedHashMap<>();
        loadBalancers.put(LOAD_BALANCER_NAME, new AlgorithmConfiguration(LOAD_BALANCER_TYPE, new Properties()));
        return new ReadwriteSplittingRuleConfiguration(groups, loadBalancers);
    }

    /**
     * 单表规则：默认把第一个读写分离组下的所有表都登记为单表。
     *
     * <p>这里必须带上组名（如 {@code datasource0.*.*}），不能只写 {@code *.*}：
     * 只写 {@code *.*} 会把 ds0 与 ds1 中的同名表都登记成同一张表的多个数据节点，
     * 单表路由会按数据节点顺序挑一个，写库就不再固定是第一个组的主库。
     */
    private SingleRuleConfiguration buildSingleRule() {
        String defaultDataSource = DataSourceConfig.resolveGroupName(dataSourceConfig.getDatasources().get(0));
        Collection<String> tables = dataSourceConfig.getSingleTables();
        if (tables == null || tables.isEmpty()) {
            tables = List.of(defaultDataSource + ".*.*");
        }
        log.info("单表规则 [{}]，默认库 {}", tables, defaultDataSource);
        return new SingleRuleConfiguration(tables, defaultDataSource);
    }

    /**
     * 分库分表规则，开关关闭时返回 {@code null}，表示不注册该规则。
     *
     * <p>每张表各自声明分片键、分库数与分表数，因此算法也按表生成：
     * 分库算法名 {@code 表名_databaseMod}、分表算法名 {@code 表名_tableMod}。
     *
     * <p>分片算法统一用 {@link ModIndexShardingAlgorithm}（按 {@code 分片键 % 候选数量} 选第 N 个）：
     * 分库时按 {@code 分片键 % 分库数} 选中第 N 个读写分离组，
     * 分表时按 {@code 分片键 % 分表数} 选中 {@code 逻辑表_N}。分库、分表可单独开启：
     * <ul>
     *   <li>只开分表（{@code database-count=1}）：所有分表都在第一个组里；</li>
     *   <li>只开分库（{@code table-count=1}）：实际表名不变，只是分散到多个组；</li>
     *   <li>都大于 1：先按库取模再按表取模，共 {@code databaseCount * tableCount} 个数据节点。</li>
     * </ul>
     *
     * <p>数据节点形如 {@code datasource0.t_order_0}，其中 {@code datasource0} 是读写分离组名。
     */
    private ShardingRuleConfiguration buildShardingRule() {
        DataSourceConfig.Split split = dataSourceConfig.getSplit();
        if (split == null || !split.isActive()) {
            log.info("分库分表未启用（sharding.split.enabled=false 或无表设置 database-count/table-count > 1），"
                    + "全部表由单表规则托管");
            return null;
        }
        List<String> groupNames = dataSourceConfig.getDatasources().stream()
                .map(DataSourceConfig::resolveGroupName)
                .toList();
        Map<String, AlgorithmConfiguration> algorithms = new LinkedHashMap<>();
        Collection<ShardingTableRuleConfiguration> tableRules = new ArrayList<>();
        for (DataSourceConfig.SplitTable table : split.getTables()) {
            if (table == null || table.getName() == null || table.getName().isBlank()) {
                throw new IllegalStateException("sharding.split.tables 中存在未配置 name 的表");
            }
            if (!table.isActive()) {
                log.info("分片表 [{}] 分库数与分表数均为 1，跳过分片", table.getName());
                continue;
            }
            if (table.getDatabaseCount() > groupNames.size()) {
                throw new IllegalStateException("分片表 [" + table.getName() + "] 的分库数 "
                        + table.getDatabaseCount() + " 超过已配置的读写分离组数量 "
                        + groupNames.size() + "，请检查 sharding.datasources");
            }
            tableRules.add(buildShardingTableRule(table, groupNames, algorithms));
        }
        if (tableRules.isEmpty()) {
            return null;
        }
        ShardingRuleConfiguration rule = new ShardingRuleConfiguration();
        rule.setTables(tableRules);
        rule.setShardingAlgorithms(algorithms);
        return rule;
    }

    /**
     * 构建单张表的分片规则，并把这张表用到的分库/分表算法注册进 {@code algorithms}。
     */
    private ShardingTableRuleConfiguration buildShardingTableRule(DataSourceConfig.SplitTable table,
                                                                  List<String> groupNames,
                                                                  Map<String, AlgorithmConfiguration> algorithms) {
        String logicTable = table.getName();
        Collection<String> dataNodes = new LinkedHashSet<>();
        for (int databaseIndex = 0; databaseIndex < table.getDatabaseCount(); databaseIndex++) {
            String groupName = groupNames.get(databaseIndex);
            for (int tableIndex = 0; tableIndex < table.getTableCount(); tableIndex++) {
                dataNodes.add(groupName + "." + table.actualTable(tableIndex));
            }
        }
        ShardingTableRuleConfiguration tableRule = new ShardingTableRuleConfiguration(logicTable,
                String.join(",", dataNodes));
        if (table.getDatabaseCount() > 1) {
            String algorithmName = databaseAlgorithmName(logicTable);
            algorithms.put(algorithmName,
                    new AlgorithmConfiguration(CLASS_BASED_ALGORITHM_TYPE, algorithmProps(table.getDatabaseCount())));
            tableRule.setDatabaseShardingStrategy(
                    new StandardShardingStrategyConfiguration(table.getShardingColumn(), algorithmName));
        }
        if (table.getTableCount() > 1) {
            String algorithmName = tableAlgorithmName(logicTable);
            algorithms.put(algorithmName,
                    new AlgorithmConfiguration(CLASS_BASED_ALGORITHM_TYPE, algorithmProps(table.getTableCount())));
            tableRule.setTableShardingStrategy(
                    new StandardShardingStrategyConfiguration(table.getShardingColumn(), algorithmName));
        }
        log.info("分片表 [{}] 分片键={} 库数={} 表数={} 数据节点={}", logicTable, table.getShardingColumn(),
                table.getDatabaseCount(), table.getTableCount(), dataNodes);
        return tableRule;
    }

    /**
     * 每张表的分库算法名，避免不同表的分库数互相覆盖。
     */
    private static String databaseAlgorithmName(String logicTable) {
        return logicTable + DATABASE_ALGORITHM_SUFFIX;
    }

    /**
     * 每张表的分表算法名，避免不同表的分表数互相覆盖。
     */
    private static String tableAlgorithmName(String logicTable) {
        return logicTable + TABLE_ALGORITHM_SUFFIX;
    }

    /**
     * {@code CLASS_BASED} 算法的属性：策略类型、实现类名、分片数量。
     */
    private Properties algorithmProps(int shardingCount) {
        Properties props = new Properties();
        props.setProperty("strategy", CLASS_BASED_STRATEGY);
        props.setProperty("algorithmClassName", SHARDING_ALGORITHM_CLASS);
        props.setProperty(ModIndexShardingAlgorithm.SHARDING_COUNT, String.valueOf(shardingCount));
        return props;
    }
}
