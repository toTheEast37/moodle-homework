package cn.example.repository;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExtractorCredentialKeyRepository {
    private static final int KEY_BYTES = 32;

    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom random = new SecureRandom();

    public ExtractorCredentialKeyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public byte[] getOrCreate(long ownerUserId) {
        byte[] candidate = new byte[KEY_BYTES];
        random.nextBytes(candidate);
        try {
            jdbcTemplate.update("""
                    INSERT IGNORE INTO extractor_credential_key (owner_user_id, encryption_key)
                    VALUES (?, ?)
                    """, ownerUserId, candidate);
        } finally {
            Arrays.fill(candidate, (byte) 0);
        }
        return find(ownerUserId).orElseThrow(
                () -> new IllegalStateException("无法读取当前提取器账号的密码加密密钥。"));
    }

    public Optional<byte[]> find(long ownerUserId) {
        return jdbcTemplate.query("""
                SELECT encryption_key
                FROM extractor_credential_key
                WHERE owner_user_id = ?
                """, (rs, rowNum) -> rs.getBytes("encryption_key"), ownerUserId)
                .stream().findFirst();
    }
}
