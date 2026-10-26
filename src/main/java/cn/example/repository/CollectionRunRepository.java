package cn.example.repository;

import cn.example.collector.LegacyCollectionResultLoader;
import cn.example.collector.MoodleHomework;
import com.google.gson.Gson;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class CollectionRunRepository {
    private final JdbcTemplate jdbcTemplate;
    private final Gson gson = new Gson();

    public CollectionRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long create(long ownerUserId, Long siteCredentialId, String siteCode, String mode) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator creator = connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO site_collection_run (owner_user_id, site_credential_id, site_code, run_mode, status)
                    VALUES (?, ?, ?, ?, 'opening_browser')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, ownerUserId);
            if (siteCredentialId == null) statement.setNull(2, Types.BIGINT);
            else statement.setLong(2, siteCredentialId);
            statement.setString(3, siteCode);
            statement.setString(4, mode);
            return statement;
        };
        jdbcTemplate.update(creator, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("创建采集记录失败。");
        return key.longValue();
    }

    public void updateProgress(long ownerUserId, long runId, MoodleHomework.Progress progress) {
        boolean terminal = progress.stage().equals("completed") || progress.stage().equals("partial")
                || progress.stage().equals("error");
        String resultJson = (progress.stage().equals("completed") || progress.stage().equals("partial"))
                ? gson.toJson(progress) : null;
        String errorMessage = progress.stage().equals("error") || progress.stage().equals("partial")
                ? progress.message() : null;
        int updated = jdbcTemplate.update("""
                UPDATE site_collection_run
                SET status = ?, progress_json = ?, result_json = COALESCE(?, result_json),
                    error_message = ?, finished_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE finished_at END
                WHERE id = ? AND owner_user_id = ?
                """, progress.stage(), gson.toJson(progress), resultJson, errorMessage, terminal, runId, ownerUserId);
        if (updated != 1) throw new IllegalStateException("更新采集记录失败。");
    }

    public synchronized void importLegacySnapshot(long ownerUserId, Long siteCredentialId, String siteCode,
                                                  LegacyCollectionResultLoader.LegacySnapshot snapshot) {
        if (findLatestResult(ownerUserId, siteCredentialId, siteCode).isPresent()) return;
        Timestamp collectedAt = Timestamp.valueOf(snapshot.collectedAt());
        jdbcTemplate.update("""
                INSERT INTO site_collection_run
                    (owner_user_id, site_credential_id, site_code, run_mode, status, started_at, finished_at,
                     progress_json, result_json)
                VALUES (?, ?, ?, 'legacy', ?, ?, ?, ?, ?)
                """, ownerUserId, siteCredentialId, siteCode, snapshot.progress().stage(), collectedAt,
                collectedAt, gson.toJson(snapshot.progress()), gson.toJson(snapshot.progress()));
    }

    public Optional<ResultSnapshot> findLatestResult(long ownerUserId, Long siteCredentialId, String siteCode) {
        String credentialFilter = siteCredentialId == null
                ? "r.site_credential_id IS NULL" : "r.site_credential_id = ?";
        String sql = """
                SELECT r.id, r.site_credential_id, r.site_code, r.status, r.finished_at,
                       COALESCE(c.display_name,
                           CASE WHEN r.run_mode = 'legacy' THEN '旧版结果（未关联平台账号）'
                                ELSE '手动登录（未绑定平台账号）' END) AS display_name,
                       r.result_json
                FROM site_collection_run r
                LEFT JOIN site_login_credential c
                  ON c.id = r.site_credential_id AND c.owner_user_id = r.owner_user_id
                WHERE r.owner_user_id = ? AND r.site_code = ?
                  AND %s AND r.result_json IS NOT NULL
                ORDER BY r.finished_at DESC, r.id DESC
                LIMIT 1
                """.formatted(credentialFilter);
        var rows = siteCredentialId == null
                ? jdbcTemplate.query(sql, (rs, rowNum) -> new ResultSnapshot(
                        rs.getLong("id"), nullableLong(rs, "site_credential_id"), rs.getString("site_code"),
                        rs.getString("display_name"), rs.getString("status"),
                        toLocalDateTime(rs.getTimestamp("finished_at")),
                        gson.fromJson(rs.getString("result_json"), MoodleHomework.Progress.class)),
                        ownerUserId, siteCode)
                : jdbcTemplate.query(sql, (rs, rowNum) -> new ResultSnapshot(
                        rs.getLong("id"), nullableLong(rs, "site_credential_id"), rs.getString("site_code"),
                        rs.getString("display_name"), rs.getString("status"),
                        toLocalDateTime(rs.getTimestamp("finished_at")),
                        gson.fromJson(rs.getString("result_json"), MoodleHomework.Progress.class)),
                        ownerUserId, siteCode, siteCredentialId);
        return rows.stream().findFirst();
    }

    /** One saved snapshot per owned platform account (plus unbound manual results). */
    public List<ResultSnapshot> findLatestResults(long ownerUserId) {
        return jdbcTemplate.query("""
                SELECT r.id, r.site_credential_id, r.site_code, r.status, r.finished_at, r.result_json,
                       COALESCE(c.display_name, '手动登录（未绑定平台账号）') AS display_name
                FROM site_collection_run r
                LEFT JOIN site_login_credential c
                  ON c.id = r.site_credential_id AND c.owner_user_id = r.owner_user_id
                WHERE r.owner_user_id = ? AND r.result_json IS NOT NULL
                  AND (r.site_credential_id IS NULL OR c.id IS NOT NULL)
                  AND NOT EXISTS (
                    SELECT 1 FROM site_collection_run newer
                    WHERE newer.owner_user_id = r.owner_user_id AND newer.site_code = r.site_code
                      AND (newer.site_credential_id = r.site_credential_id
                           OR (newer.site_credential_id IS NULL AND r.site_credential_id IS NULL))
                      AND newer.result_json IS NOT NULL
                      AND (newer.finished_at > r.finished_at
                           OR (newer.finished_at = r.finished_at AND newer.id > r.id))
                  )
                ORDER BY r.site_code, r.site_credential_id
                """, (rs, rowNum) -> new ResultSnapshot(
                rs.getLong("id"), nullableLong(rs, "site_credential_id"), rs.getString("site_code"),
                rs.getString("display_name"), rs.getString("status"),
                toLocalDateTime(rs.getTimestamp("finished_at")),
                gson.fromJson(rs.getString("result_json"), MoodleHomework.Progress.class)), ownerUserId);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime toLocalDateTime(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    public record ResultSnapshot(long runId, Long siteCredentialId, String siteCode, String displayName,
                                 String status, LocalDateTime collectedAt, MoodleHomework.Progress progress) {}
}
