package cn.example.collector;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class LegacyCollectionResultLoader {
    private final Gson gson = new Gson();

    public Optional<LegacySnapshot> findLatest(Path outputRoot) {
        if (!Files.isDirectory(outputRoot)) return Optional.empty();
        try (Stream<Path> children = Files.list(outputRoot)) {
            List<Path> runDirectories = children
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("run-"))
                    .sorted(Comparator.comparingLong(LegacyCollectionResultLoader::summaryModifiedAt).reversed())
                    .toList();
            for (Path runDirectory : runDirectories) {
                Optional<LegacySnapshot> snapshot = readRun(runDirectory);
                if (snapshot.isPresent()) return snapshot;
            }
            return Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException("读取旧版本地采集结果失败。", e);
        }
    }

    private Optional<LegacySnapshot> readRun(Path runDirectory) throws Exception {
        Path summaryFile = runDirectory.resolve("summary.json");
        if (!Files.isRegularFile(summaryFile)) return Optional.empty();
        MoodleHomework.Summary summary = gson.fromJson(Files.readString(summaryFile), MoodleHomework.Summary.class);
        if (summary == null || !("completed".equals(summary.status()) || "partial".equals(summary.status()))) {
            return Optional.empty();
        }
        List<MoodleHomework.CourseResult> courses = new ArrayList<>();
        if (summary.courses() != null) {
            Path normalizedRunDirectory = runDirectory.toAbsolutePath().normalize();
            for (MoodleHomework.CourseIndex course : summary.courses()) {
                if (course.file() == null || course.file().isBlank()) continue;
                Path courseFile = runDirectory.resolve(course.file()).toAbsolutePath().normalize();
                if (!courseFile.startsWith(normalizedRunDirectory) || !Files.isRegularFile(courseFile)) continue;
                MoodleHomework.CourseResult result = gson.fromJson(
                        Files.readString(courseFile), MoodleHomework.CourseResult.class);
                if (result != null) courses.add(result);
            }
        }
        if (courses.isEmpty()) return Optional.empty();
        LocalDateTime collectedAt = OffsetDateTime.parse(summary.collectedAt()).toLocalDateTime();
        String message = "已恢复升级前保存的采集结果。";
        MoodleHomework.Progress progress = new MoodleHomework.Progress(summary.status(), message,
                summary.catalogPagesRead(), summary.courseCount(), summary.processedCourseCount(),
                summary.assignmentCount(), null, runDirectory.toAbsolutePath().toString(), List.copyOf(courses));
        return Optional.of(new LegacySnapshot(collectedAt, progress));
    }

    private static long summaryModifiedAt(Path runDirectory) {
        try {
            Path summary = runDirectory.resolve("summary.json");
            return Files.isRegularFile(summary) ? Files.getLastModifiedTime(summary).toMillis() : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    public record LegacySnapshot(LocalDateTime collectedAt, MoodleHomework.Progress progress) {}
}
