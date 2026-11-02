package com.nn0101.specstore.core.config.jdbc;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分表开关的集成测试：把 {@code t_order} 配成 4 张分表（{@code t_order_0} … {@code t_order_3}），
 * 分片键 {@code id}，验证：
 * <ol>
 *   <li>写操作按 {@code id % 4} 落到对应的物理分表；</li>
 *   <li>不带分片键的查询会广播到全部分表并归并结果；</li>
 *   <li>分库关闭时所有分表仍在第一个读写分离组（{@code ds0}）。</li>
 * </ol>
 *
 * <p>物理分表在类的静态块里创建：ShardingSphere 在容器启动时加载表元数据，
 * 表必须先于上下文存在，因此不能放在测试方法里建。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "sharding.split.enabled=true",
        "sharding.split.tables[0].name=t_order",
        "sharding.split.tables[0].sharding-column=id",
        "sharding.split.tables[0].database-count=1",
        "sharding.split.tables[0].table-count=4"
})
class ShardingSplitTest {

    private static final String PHYSICAL_URL =
            "jdbc:postgresql://localhost:5432/spec_store?options=-c%20timezone%3DAsia%2FShanghai";

    private static final String USERNAME = "root";

    private static final String PASSWORD = "123456";

    private static final int TABLE_COUNT = 4;

    /**
     * 分片键取值 {@code 5} 的落表下标。
     */
    private static final int SAMPLE_ID = 5;

    static {
        try (Connection conn = DriverManager.getConnection(PHYSICAL_URL, USERNAME, PASSWORD);
             Statement st = conn.createStatement()) {
            for (int i = 0; i < TABLE_COUNT; i++) {
                st.execute("CREATE TABLE IF NOT EXISTS t_order_" + i
                        + " (id bigint primary key, order_no varchar(64), amount numeric(12,2),"
                        + " created_time timestamp default now())");
                st.execute("TRUNCATE TABLE t_order_" + i);
            }
        } catch (Exception e) {
            throw new IllegalStateException("初始化分表环境失败", e);
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection conn = DriverManager.getConnection(PHYSICAL_URL, USERNAME, PASSWORD);
             Statement st = conn.createStatement()) {
            for (int i = 0; i < TABLE_COUNT; i++) {
                st.execute("TRUNCATE TABLE t_order_" + i);
            }
        }
    }

    @Test
    void shardingByModShouldRouteToActualTables() throws Exception {
        for (int i = 0; i < 8; i++) {
            jdbcTemplate.update("INSERT INTO t_order (id, order_no, amount) VALUES (?, ?, ?)",
                    (long) i, "SO-" + i, 10 + i);
        }

        // 1) 逻辑侧聚合查询：4 张分表都能被广播、归并
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_order", Integer.class);
        assertEquals(8, total, "不带分片键的查询应广播到全部分表并归并");

        // 2) 带分片键查询：只命中一张分表
        String orderNo = jdbcTemplate.queryForObject(
                "SELECT order_no FROM t_order WHERE id = ?", String.class, (long) SAMPLE_ID);
        assertEquals("SO-" + SAMPLE_ID, orderNo);

        // 3) 物理侧核对：id=5 必须只落在 t_order_1（5 % 4 == 1）
        try (Connection conn = DriverManager.getConnection(PHYSICAL_URL, USERNAME, PASSWORD);
             Statement st = conn.createStatement()) {
            for (int i = 0; i < TABLE_COUNT; i++) {
                try (ResultSet rs = st.executeQuery(
                        "SELECT id FROM t_order_" + i + " WHERE id = " + SAMPLE_ID)) {
                    boolean found = rs.next();
                    assertEquals(i == SAMPLE_ID % TABLE_COUNT, found,
                            "id=" + SAMPLE_ID + " 应只出现在 t_order_" + (SAMPLE_ID % TABLE_COUNT)
                                    + "，实际检查 t_order_" + i);
                }
            }
        }
    }
}
