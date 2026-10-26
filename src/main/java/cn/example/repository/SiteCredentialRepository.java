package cn.example.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class SiteCredentialRepository {
    private final JdbcTemplate jdbcTemplate;

    public SiteCredentialRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CredentialSummary> findAllByOwner(long ownerUserId) {
        return jdbcTemplate.query("""
                SELECT id, site_code, display_name, login_username, updated_at
                FROM site_login_credential
                WHERE owner_user_id = ?
                ORDER BY updated_at DESC, id DESC
                """, (rs, rowNum) -> new CredentialSummary(rs.getLong("id"), rs.getString("site_code"),
                rs.getString("display_name"), rs.getString("login_username"),
                rs.getTimestamp("updated_at").toLocalDateTime()), ownerUserId);
    }

    public Optional<StoredCredential> findOwnedById(long ownerUserId, long id) {
        return jdbcTemplate.query("""
                SELECT id, site_code, display_name, login_username, password_nonce, password_ciphertext
                FROM site_login_credential
                WHERE owner_user_id = ? AND id = ?
                """, (rs, rowNum) -> new StoredCredential(rs.getLong("id"), rs.getString("site_code"),
                rs.getString("display_name"), rs.getString("login_username"),
                rs.getBytes("password_nonce"), rs.getBytes("password_ciphertext")), ownerUserId, id)
                .stream().findFirst();
    }

    public Optional<CredentialSummary> findSummaryOwnedById(long ownerUserId, long id) {
        return jdbcTemplate.query("""
                SELECT id, site_code, display_name, login_username, updated_at
                FROM site_login_credential WHERE owner_user_id = ? AND id = ?
                """, (rs, rowNum) -> new CredentialSummary(rs.getLong("id"), rs.getString("site_code"),
                rs.getString("display_name"), rs.getString("login_username"),
                rs.getTimestamp("updated_at").toLocalDateTime()), ownerUserId, id).stream().findFirst();
    }

    public long insert(long ownerUserId, String siteCode, String displayName, String loginUsername,
                       byte[] nonce, byte[] ciphertext) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator creator = connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO site_login_credential
                        (owner_user_id, site_code, display_name, login_username, password_nonce, password_ciphertext)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, ownerUserId);
            statement.setString(2, siteCode);
            statement.setString(3, displayName);
            statement.setString(4, loginUsername);
            statement.setBytes(5, nonce);
            statement.setBytes(6, ciphertext);
            return statement;
        };
        jdbcTemplate.update(creator, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("保存作业网站账号失败。");
        return key.longValue();
    }

    public boolean updateOwned(long ownerUserId, long id, String siteCode, String displayName,
                               String loginUsername, byte[] nonce, byte[] ciphertext) {
        int changed = jdbcTemplate.update("""
                UPDATE site_login_credential
                SET site_code = ?, display_name = ?, login_username = ?,
                    password_nonce = COALESCE(?, password_nonce),
                    password_ciphertext = COALESCE(?, password_ciphertext)
                WHERE owner_user_id = ? AND id = ?
                """, siteCode, displayName, loginUsername, nonce, ciphertext, ownerUserId, id);
        return changed > 0;
    }

    public boolean deleteOwned(long ownerUserId, long id) {
        return jdbcTemplate.update("DELETE FROM site_login_credential WHERE owner_user_id = ? AND id = ?",
                ownerUserId, id) > 0;
    }

    public record CredentialSummary(long id, String siteCode, String displayName,
                                    String loginUsername, LocalDateTime updatedAt) {}

    public record StoredCredential(long id, String siteCode, String displayName, String loginUsername,
                                   byte[] nonce, byte[] ciphertext) {}
}
