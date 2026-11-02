package com.nn0101.specstore.core.config.jdbc;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.shardingsphere.encrypt.spi.EncryptAlgorithm;
import org.apache.shardingsphere.encrypt.spi.EncryptAlgorithmMetaData;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.infra.algorithm.core.context.AlgorithmSQLContext;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Properties;

/**
 * 数据库字段加密算法（算法类型 {@value #TYPE}），面向 PostgreSQL。
 *
 * <p>加密规则：
 * <ul>
 *   <li>密钥派生：把 {@code aes.key.value} 的 <b>US-ASCII</b> 字节逐字节异或折叠到 16 字节缓冲区，
 *       作为 AES 的 SecretKey（不经过摘要算法）；</li>
 *   <li>加密算法：{@code Cipher.getInstance("AES")}，即 JDK 默认的 {@code AES/ECB/PKCS5Padding}；</li>
 *   <li>密文编码：<b>十六进制小写字符串</b>（{@link Hex#encodeHexString}），不是 Base64。</li>
 * </ul>
 *
 * <p>ShardingSphere 5.x 不再内置按数据库类型命名的加密器，因此这里实现
 * {@link EncryptAlgorithm} SPI 并通过
 * {@code META-INF/services/org.apache.shardingsphere.encrypt.spi.EncryptAlgorithm} 注册，
 * 配置里写 {@code algorithm-type: postgresql} 即可被 {@code TypedSPILoader} 找到。
 *
 * <p>PostgreSQL 下物理列使用 {@code varchar} / {@code text}（十六进制密文长度是明文的两倍），
 * 密文按字符串比较。ECB 是确定性加密（相同明文永远得到相同密文），因此支持等值查询（{@code =}），
 * 但不支持 {@code LIKE}。
 */
@Slf4j
public final class PostgresqlAesEncryptAlgorithm implements EncryptAlgorithm {

    /**
     * 算法类型名，配置里通过它引用本算法。
     */
    public static final String TYPE = "postgresql";

    /**
     * 原始密钥的配置键。
     */
    public static final String AES_KEY_PROPERTY = "aes.key.value";

    /**
     * AES 密钥长度（字节）。
     */
    private static final int AES_KEY_LENGTH = 16;

    /**
     * AES 算法名。
     */
    private static final String AES_ALGORITHM = "AES";

    private final EncryptAlgorithmMetaData metaData = new EncryptAlgorithmMetaData(true, true, false);

    /**
     * 初始化时传入的属性，{@link #toConfiguration()} 需要原样带回。
     */
    @Getter
    private Properties props;

    private SecretKeySpec secretKey;

    @Override
    public void init(Properties props) {
        this.props = props;
        String aesKey = props == null ? null : props.getProperty(AES_KEY_PROPERTY);
        if (aesKey == null || aesKey.isEmpty()) {
            throw new IllegalStateException("加密算法 [" + TYPE + "] 缺少密钥配置项 " + AES_KEY_PROPERTY);
        }
        this.secretKey = generateAesKey(aesKey);
        log.info("加密算法 [{}] 已初始化（AES/ECB，十六进制密文）", TYPE);
    }

    @Override
    public Object encrypt(Object plaintext, AlgorithmSQLContext context) {
        if (plaintext == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] cipherBytes = cipher.doFinal(plaintext.toString().getBytes(StandardCharsets.UTF_8));
            return Hex.encodeHexString(cipherBytes);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("加密字段 [" + context.getColumnName() + "] 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Object decrypt(Object ciphertext, AlgorithmSQLContext context) {
        if (ciphertext == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            byte[] plainBytes = cipher.doFinal(Hex.decodeHex(ciphertext.toString()));
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | DecoderException e) {
            throw new IllegalStateException("解密字段 [" + context.getColumnName() + "] 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public EncryptAlgorithmMetaData getMetaData() {
        return metaData;
    }

    @Override
    public AlgorithmConfiguration toConfiguration() {
        return new AlgorithmConfiguration(TYPE, props);
    }

    @Override
    public String getType() {
        return TYPE;
    }

    /**
     * 密钥派生：把密钥的 US-ASCII 字节循环异或折叠进 16 字节，得到 AES-128 密钥。
     */
    private static SecretKeySpec generateAesKey(String key) {
        byte[] finalKey = new byte[AES_KEY_LENGTH];
        int index = 0;
        for (byte b : key.getBytes(StandardCharsets.US_ASCII)) {
            finalKey[index++ % AES_KEY_LENGTH] ^= b;
        }
        return new SecretKeySpec(finalKey, AES_ALGORITHM);
    }
}
