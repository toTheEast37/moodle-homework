package cn.example.collector;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 读取学者网「我的课程 → 作业动态」中当前可见课程的作业。 */
public final class ScholatHomework {
    private ScholatHomework() {}

    public static void run(String accountKey, String username, char[] password,
                    Consumer<MoodleHomework.Progress> progressListener) throws Exception {
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                     .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(
                     new BrowserType.LaunchOptions().setChannel("msedge").setHeadless(false));
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setLocale("zh-CN").setTimezoneId(AssignmentCollector.TIME_ZONE.getId()))) {
            Page page = context.newPage();
            page.setDefaultTimeout(20_000);
            page.setDefaultNavigationTimeout(60_000);
            progressListener.accept(progress("waiting_login", "正在登录学者网；如有额外提示，请在采集浏览器中处理，程序会自动继续。",
                    0, 0, List.of(), null, null));
            page = ScholatLogin.loginWithSavedCredential(page, username, password);
            progressListener.accept(progress("reading_courses", "已登录，正在打开作业动态。",
                    0, 0, List.of(), null, null));
            page = ScholatLogin.openHomeworkOverview(page, message -> progressListener.accept(
                    progress("reading_courses", message, 0, 0, List.of(), null, null)), 60_000);

            Locator blocks = page.locator(ScholatLogin.HOMEWORK_BLOCKS);
            int totalCourses = blocks.count();
            if (totalCourses == 0) {
                throw new IllegalStateException("作业动态已打开，但没有找到课程作业表格。");
            }
            String timestamp = LocalDateTime.now(AssignmentCollector.TIME_ZONE)
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            Path output = Path.of("output", accountKey, "run-" + timestamp).toAbsolutePath();
            Files.createDirectories(output);
            List<MoodleHomework.CourseResult> results = new ArrayList<>();
            progressListener.accept(progress("collecting", "正在读取学者网作业截止日期。",
                    totalCourses, 0, results, null, output.toString()));
            for (int i = 0; i < totalCourses; i++) {
                Locator block = blocks.nth(i);
                String currentCourse = "第 " + (i + 1) + " 门课程";
                progressListener.accept(progress("collecting", "正在读取学者网" + currentCourse + "。",
                        totalCourses, results.size(), results, currentCourse, output.toString()));
                MoodleHomework.CourseResult result;
                try {
                    result = readCourse(page, block);
                } catch (RuntimeException e) {
                    throw new IllegalStateException(currentCourse + "读取失败：" + e.getMessage(), e);
                }
                results.add(result);
                MoodleHomework.writeJson(output.resolve("course-" + result.courseId() + ".json"), result);
                progressListener.accept(progress("collecting", "已读取：" + result.courseName(),
                        totalCourses, results.size(), results, result.courseName(), output.toString()));
            }
            MoodleHomework.Progress completed = progress("completed", "学者网作业截止日期已读取。",
                    totalCourses, results.size(), results, null, output.toString());
            MoodleHomework.writeJson(output.resolve("summary.json"), completed);
            progressListener.accept(completed);
        }
    }

    static MoodleHomework.CourseResult readCourse(Page page, Locator block) {
        // 真实页面按“标题 div + 表格 div”交替排列，两者并不在同一个课程容器内。
        Locator heading = block.locator("xpath=preceding-sibling::*[1][self::div]");
        if (heading.count() != 1 || heading.locator("table.homeworkList").count() != 0) {
            throw new IllegalStateException("作业表格前未找到对应的课程标题区域，页面结构与预期不符。");
        }
        Locator courseLink = heading.locator("a[href*='course/']").first();
        Locator courseTitle = heading.locator("p").first();
        requirePresent(courseLink, "课程标题区域缺少课程链接（course/）。");
        requirePresent(courseTitle, "课程标题区域缺少课程名称（p 标签）。");
        String courseName = courseTitle.innerText().strip();
        if (courseName.isBlank()) throw new IllegalStateException("课程标题区域的课程名称为空。");
        String courseUrl = resolve(page.url(), courseLink.getAttribute("href"));
        Locator moreLink = block.locator("a[href*='courseId=']").first();
        if (moreLink.count() == 0) moreLink = heading.locator("a[href*='courseId=']").first();
        requirePresent(moreLink, "课程“" + courseName + "”的标题和表格区域均缺少带 courseId 的链接。");
        String moreHref = moreLink.getAttribute("href");
        long courseId = queryId(moreHref, "courseId");
        Locator rows = block.locator("table.homeworkList tr:has(td)");
        List<AssignmentCollector.Assignment> assignments = new ArrayList<>();
        for (int i = 0; i < rows.count(); i++) {
            Locator cells = rows.nth(i).locator("td");
            String rowText = rows.nth(i).innerText().replaceAll("\\s+", "");
            if (rowText.equals("暂无作业") || rowText.equals("暂无作业！") || rowText.equals("暂无作业!")) continue;
            // 部分页面使用 td 而非 th 渲染表头，不能将其当作作业去等待详情链接。
            if (cells.nth(0).innerText().strip().equals("作业名称")
                    && rows.nth(i).locator("a[href*='homeworkId=']").count() == 0) continue;
            if (cells.count() < 5) {
                throw new IllegalStateException("课程“" + courseName + "”的作业表第 " + (i + 1) + " 行列数不足。");
            }
            Locator titleLink = cells.nth(0).locator("a[href*='homeworkId=']").first();
            requirePresent(titleLink, "课程“" + courseName + "”的作业表第 " + (i + 1)
                    + " 行缺少带 homeworkId 的作业链接。");
            String detailHref = titleLink.getAttribute("href");
            long homeworkId = queryId(detailHref, "homeworkId");
            String title = titleLink.getAttribute("title");
            if (title == null || title.isBlank()) title = titleLink.innerText().strip();
            String dueDate = cells.nth(2).innerText().strip();
            String normalizedDueDate = null;
            if (!dueDate.isBlank() && !"无".equals(dueDate) && !"未设置".equals(dueDate)) {
                try {
                    normalizedDueDate = LocalDate.parse(dueDate).toString();
                } catch (java.time.format.DateTimeParseException e) {
                    throw new IllegalStateException("课程“" + courseName + "”的截止日期格式无法识别：" + dueDate, e);
                }
            }
            assignments.add(new AssignmentCollector.Assignment(homeworkId, title,
                    resolve(page.url(), detailHref), dueDate, normalizedDueDate));
        }
        return new MoodleHomework.CourseResult(courseId, courseName, courseUrl, page.url(),
                MoodleHomework.collectedAt(), AssignmentCollector.TIME_ZONE.getId(),
                assignments.isEmpty() ? "empty" : "ok", assignments.size(), assignments, null);
    }

    private static void requirePresent(Locator locator, String message) {
        if (locator.count() == 0) throw new IllegalStateException(message);
    }

    private static long queryId(String href, String name) {
        if (href == null) throw new IllegalStateException("作业链接缺少 " + name + "。");
        String query = URI.create(href).getRawQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2 && name.equals(pair[0])) {
                    try {
                        return Long.parseLong(pair[1]);
                    } catch (NumberFormatException e) {
                        throw new IllegalStateException("作业链接中的 " + name + " 不是数字：" + href, e);
                    }
                }
            }
        }
        throw new IllegalStateException("作业链接缺少 " + name + "：" + href);
    }

    private static String resolve(String pageUrl, String href) {
        return URI.create(pageUrl).resolve(href).toString();
    }

    private static MoodleHomework.Progress progress(String stage, String message, int totalCourses,
                                                     int processedCourses,
                                                     List<MoodleHomework.CourseResult> results,
                                                     String currentCourse, String outputDirectory) {
        int assignmentCount = results.stream().mapToInt(result -> result.assignmentCount() == null
                ? 0 : result.assignmentCount()).sum();
        return new MoodleHomework.Progress(stage, message, 1, totalCourses, processedCourses,
                assignmentCount, currentCourse, outputDirectory, List.copyOf(results));
    }
}
