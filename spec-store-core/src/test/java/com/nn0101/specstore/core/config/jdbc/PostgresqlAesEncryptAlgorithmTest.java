package com.nn0101.specstore.core.config.jdbc;

import org.apache.shardingsphere.infra.algorithm.core.context.AlgorithmSQLContext;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PostgresqlAesEncryptAlgorithm} 单元测试。
 *
 * <p>校验加密规则本身：AES/ECB + 小写十六进制密文、确定性加密、可逆。
 * 期望值由同一算法的独立参考实现算出，用于锁定落库格式，
 * 防止有人改动算法后既有数据无法解密。
 */
class PostgresqlAesEncryptAlgorithmTest {

    private static final String AES_KEY = "SpecStore@2026#AesKey";

    private static final String PHONE = "13800001111";

    private static final String EMAIL = "alice@example.com";

    private static final AlgorithmSQLContext CONTEXT =
            new AlgorithmSQLContext("spec_store", null, "t_tenant", "phone");

    private PostgresqlAesEncryptAlgorithm newAlgorithm() {
        PostgresqlAesEncryptAlgorithm algorithm = new PostgresqlAesEncryptAlgorithm();
        Properties props = new Properties();
        props.setProperty(PostgresqlAesEncryptAlgorithm.AES_KEY_PROPERTY, AES_KEY);
        algorithm.init(props);
        return algorithm;
    }

    @Test
    void ciphertextShouldMatchExpectedFormat() {
        PostgresqlAesEncryptAlgorithm algorithm = newAlgorithm();
        // 期望值由 AES/ECB + Hex 小写 的参考实现算出
        assertEquals("58312261f62300f3c9ece15211b25d34", algorithm.encrypt(PHONE, CONTEXT));
        assertEquals("1a79489d6976f7ff6e9640888806022018e220f609e06d7cf92788965cd8d276",
                algorithm.encrypt(EMAIL, CONTEXT));
    }

    @Test
    void encryptThenDecryptShouldRoundTrip() {
        PostgresqlAesEncryptAlgorithm algorithm = newAlgorithm();
        Object cipher = algorithm.encrypt(EMAIL, CONTEXT);
        assertEquals(EMAIL, algorithm.decrypt(cipher, CONTEXT));
    }

    @Test
    void nullShouldStayNull() {
        PostgresqlAesEncryptAlgorithm algorithm = newAlgorithm();
        assertNull(algorithm.encrypt(null, CONTEXT));
        assertNull(algorithm.decrypt(null, CONTEXT));
    }

    @Test
    void encryptionShouldBeDeterministic() {
        // ECB 是确定性加密，这也是等值查询能生效（以及 LIKE 不能生效）的原因
        PostgresqlAesEncryptAlgorithm algorithm = newAlgorithm();
        assertEquals(algorithm.encrypt(PHONE, CONTEXT), algorithm.encrypt(PHONE, CONTEXT));
    }

    @Test
    void typeShouldBePostgresql() {
        assertEquals("postgresql", newAlgorithm().getType());
        assertTrue(newAlgorithm().getMetaData().isSupportEquivalentFilter());
    }
}
