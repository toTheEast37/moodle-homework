package cn.example.repository;

import cn.example.domain.AppUser;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AppUserRepository {
    private final JdbcTemplate jdbcTemplate;

    public AppUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AppUser> findEnabledByUsername(String username) {
        return jdbcTemplate.query(
                "SELECT id, username, password_hash, enabled FROM app_user WHERE username = ? AND enabled = TRUE",
                (rs, rowNum) -> new AppUser(rs.getLong("id"), rs.getString("username"),
                        rs.getString("password_hash"), rs.getBoolean("enabled")),
                username).stream().findFirst();
    }

    public boolean existsByUsername(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    public void insert(String username, String passwordHash) {
        jdbcTemplate.update("INSERT INTO app_user (username, password_hash, enabled) VALUES (?, ?, TRUE)",
                username, passwordHash);
    }
}
