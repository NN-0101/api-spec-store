package com.nn0101.specstore.core.config.jdbc;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * 按下标取模的分片算法：{@code 分片键 % 候选数量} 选出排序后的第 N 个候选名。
 *
 * <p>为什么不用内置的 {@code MOD}：内置 {@code MOD}（{@code ModShardingAlgorithm}）实现了
 * {@code ShardingAutoTableAlgorithm}，只能配在 {@code autoTables} 上，配到普通
 * {@code tables} 会在启动时报
 * {@code tables sharding configuration can not use auto sharding algorithm}。
 *
 * <p>本算法不关心表名/库名的命名规则：分库时候选是读写分离组名（如 {@code datasource0}、
 * {@code datasource1}），分表时候选是实际表名（如 {@code t_order_0} … {@code t_order_3}），
 * 统一"排好序取第 {@code 分片键 % 候选数量} 个"即可，所以组名无论是
 * {@code datasource0} 还是 {@code ds0} 都能正常工作。
 *
 * <p>通过 {@code CLASS_BASED} 使用：
 * <pre>
 * sharding-algorithms:
 *   tableMod:
 *     type: CLASS_BASED
 *     props:
 *       strategy: STANDARD
 *       algorithmClassName: com.nn0101.specstore.core.config.jdbc.ModIndexShardingAlgorithm
 *       sharding-count: 4
 * </pre>
 * {@code sharding-count} 可省略，省略时用本次路由的候选数量。
 */
public final class ModIndexShardingAlgorithm implements StandardShardingAlgorithm<Comparable<?>> {

    /**
     * 分片数量（候选数量）的属性名。
     */
    public static final String SHARDING_COUNT = "sharding-count";

    /**
     * 算法类型，仅用于日志与异常信息。
     */
    public static final String TYPE = "MOD_INDEX";

    private int shardingCount = -1;

    @Override
    public void init(Properties props) {
        String value = props == null ? null : props.getProperty(SHARDING_COUNT);
        if (value != null && !value.isBlank()) {
            this.shardingCount = Integer.parseInt(value.trim());
        }
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String doSharding(Collection<String> availableTargetNames, PreciseShardingValue<Comparable<?>> shardingValue) {
        List<String> candidates = sorted(availableTargetNames);
        int count = resolveCount(candidates);
        int index = toBigInteger(shardingValue.getValue()).mod(BigInteger.valueOf(count)).intValue();
        return candidates.get(index);
    }

    /**
     * 范围查询无法定位到单张表，返回全部候选，由上层合并结果。
     */
    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                         RangeShardingValue<Comparable<?>> shardingValue) {
        return availableTargetNames;
    }

    private List<String> sorted(Collection<String> availableTargetNames) {
        List<String> candidates = new ArrayList<>(availableTargetNames);
        Collections.sort(candidates);
        return candidates;
    }

    private int resolveCount(List<String> candidates) {
        if (candidates.isEmpty()) {
            throw new IllegalStateException("分片算法 [" + TYPE + "] 没有可用的目标，请检查实际表名或读写分离组名是否与配置一致");
        }
        if (shardingCount < 1) {
            return candidates.size();
        }
        if (shardingCount != candidates.size()) {
            throw new IllegalStateException("分片算法 [" + TYPE + "] 配置的分片数 " + shardingCount
                    + " 与实际候选数量 " + candidates.size() + " 不一致，候选为 " + candidates);
        }
        return shardingCount;
    }

    private BigInteger toBigInteger(Comparable<?> value) {
        if (value == null) {
            throw new IllegalStateException("分片键 [" + SHARDING_COUNT + "] 的值为空，无法取模分片");
        }
        if (value instanceof BigInteger bigInteger) {
            return bigInteger;
        }
        if (value instanceof Number number) {
            return BigInteger.valueOf(number.longValue());
        }
        return new BigInteger(String.valueOf(value).trim());
    }
}
