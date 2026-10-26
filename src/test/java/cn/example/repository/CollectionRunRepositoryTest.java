package cn.example.repository;

import cn.example.collector.MoodleHomework;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CollectionRunRepositoryTest {
    JdbcTemplate jdbc;
    CollectionRunRepository repository;

    @BeforeEach void database() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE site_login_credential (id BIGINT PRIMARY KEY, owner_user_id BIGINT, display_name VARCHAR(100))");
        jdbc.execute("""
                CREATE TABLE site_collection_run (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_user_id BIGINT, site_credential_id BIGINT,
                  site_code VARCHAR(64), run_mode VARCHAR(16), status VARCHAR(32),
                  started_at TIMESTAMP, finished_at TIMESTAMP,
                  progress_json CLOB, result_json CLOB, error_message VARCHAR(1000))
                """);
        repository = new CollectionRunRepository(jdbc);
    }

    void saved(long id, long owner, Long credential, String site, String status, String time) {
        jdbc.update("""
                INSERT INTO site_collection_run
                  (id,owner_user_id,site_credential_id,site_code,run_mode,status,finished_at,result_json)
                VALUES (?,?,?,?, 'auto', ?, ?, ?)
                """, id, owner, credential, site, status, time,
                status.equals("error") ? null : "{\"stage\":\"" + status + "\",\"courses\":[]}");
    }

    @Test void aggregatesLatestPerAccountAndManualSourceWithoutLeakingOtherUsers() {
        jdbc.update("INSERT INTO site_login_credential VALUES (10,1,'Moodle A'),(11,1,'Scholat'),(12,1,'Moodle B'),(20,2,'Other user')");
        saved(1, 1, 10L, "scnu-moodle", "completed", "2026-10-25 10:00:00");
        saved(2, 1, 10L, "scnu-moodle", "partial", "2026-10-25 10:00:00");
        saved(3, 1, 10L, "scnu-moodle", "error", "2026-10-26 10:00:00");
        saved(4, 1, 11L, "scholat", "completed", "2026-10-25 10:00:00");
        saved(5, 1, 12L, "scnu-moodle", "completed", "2026-10-25 10:00:00");
        saved(6, 1, null, "scnu-moodle", "completed", "2026-10-25 10:00:00");
        saved(7, 2, 20L, "scholat", "completed", "2026-10-26 10:00:00");
        saved(8, 1, 999L, "scholat", "completed", "2026-10-26 10:00:00");
        var results = repository.findLatestResults(1);
        assertEquals(4, results.size());
        assertEquals(List.of(2L, 4L, 5L, 6L), results.stream().map(CollectionRunRepository.ResultSnapshot::runId).sorted().toList());
        assertEquals("partial", results.stream().filter(r -> Long.valueOf(10).equals(r.siteCredentialId())).findFirst().orElseThrow().status());
        assertEquals(List.of(7L), repository.findLatestResults(2).stream().map(CollectionRunRepository.ResultSnapshot::runId).toList());
        assertTrue(repository.findLatestResults(3).isEmpty());
    }

    @Test void successfulEmptyCollectionReplacesPreviousSnapshotAndErrorsKeepIt() {
        jdbc.update("INSERT INTO site_login_credential VALUES (10,1,'Moodle')");
        long id = repository.create(1, 10L, "scnu-moodle", "auto");
        repository.updateProgress(1, id, new MoodleHomework.Progress(
                "completed", "没有课程", 0, 0, 0, 0, null, null, List.of()));
        var empty = repository.findLatestResults(1).get(0);
        assertTrue(empty.progress().courses().isEmpty());
        assertEquals(0, empty.progress().assignmentCount());
        long failedId = repository.create(1, 10L, "scnu-moodle", "auto");
        repository.updateProgress(1, failedId, new MoodleHomework.Progress(
                "error", "登录失败", 0, 0, 0, 0, null, null, List.of()));
        assertEquals(id, repository.findLatestResults(1).get(0).runId());
    }
}
