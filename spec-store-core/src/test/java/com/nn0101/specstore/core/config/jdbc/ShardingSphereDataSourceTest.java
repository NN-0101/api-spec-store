package com.nn0101.specstore.core.config.jdbc;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据源集成测试：验证「字段加密 + 读写分离」两条链路都真实生效。
 *
 * <p>加密验证思路：通过 ShardingSphere 逻辑数据源写入明文，再用原生 JDBC 直连物理库
 * 读取同一个字段——逻辑侧应读回明文，物理侧应是 Base64 密文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ShardingSphereDataSourceTest {

    private static final String PHYSICAL_URL =
            "jdbc:postgresql://localhost:5432/spec_store?options=-c%20timezone%3DAsia%2FShanghai";

    private static final String PHYSICAL_USER = "root";

    private static final String PHYSICAL_PASSWORD = "123456";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void encryptAndReadWriteSplittingShouldWork() throws Exception {
        String phone = "13800001111";
        String email = "alice@example.com";
        jdbcTemplate.update("DELETE FROM t_tenant WHERE tenant_name = ?", "it-tenant");
        jdbcTemplate.update(
                "INSERT INTO t_tenant (tenant_name, phone, email, status) VALUES (?, ?, ?, ?)",
                "it-tenant", phone, email, 1);

        // 1) 逻辑侧读取：应自动解密为明文
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT tenant_name, phone, email FROM t_tenant WHERE tenant_name = ?", "it-tenant");
        assertEquals(1, rows.size());
        assertEquals(phone, rows.get(0).get("phone"));
        assertEquals(email, rows.get(0).get("email"));

        // 2) 物理侧直连读取：应是密文，且与明文不同
        try (Connection conn = DriverManager.getConnection(PHYSICAL_URL, PHYSICAL_USER, PHYSICAL_PASSWORD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT phone, email FROM t_tenant WHERE tenant_name = 'it-tenant'")) {
            assertTrue(rs.next(), "物理库中应存在刚写入的记录");
            String cipherPhone = rs.getString("phone");
            String cipherEmail = rs.getString("email");
            assertNotNull(cipherPhone);
            assertNotEquals(phone, cipherPhone, "phone 落库应为密文");
            assertNotEquals(email, cipherEmail, "email 落库应为密文");
            // AES/ECB + 小写十六进制，与 PostgresqlAesEncryptAlgorithm 的规则逐字节一致
            assertEquals("58312261f62300f3c9ece15211b25d34", cipherPhone);
            assertEquals("1a79489d6976f7ff6e9640888806022018e220f609e06d7cf92788965cd8d276", cipherEmail);
            assertTrue(cipherPhone.matches("[0-9a-f]+"), "密文应为小写十六进制，实际为 " + cipherPhone);
            System.out.println("[verify] phone cipher = " + cipherPhone);
            System.out.println("[verify] email cipher = " + cipherEmail);
        }

        jdbcTemplate.update("DELETE FROM t_tenant WHERE tenant_name = ?", "it-tenant");
    }

    @Test
    void dataSourceShouldBeShardingSphereProxy() {
        String className = dataSource.getClass().getName();
        System.out.println("[verify] dataSource class = " + className);
        assertTrue(className.startsWith("org.apache.shardingsphere"),
                "容器内的 dataSource 应是 ShardingSphere 逻辑数据源，实际为 " + className);
    }
}
