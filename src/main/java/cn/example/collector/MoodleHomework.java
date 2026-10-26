package cn.example.collector;

import com.google.gson.GsonBuilder;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/** Playwright 采集流程：使用已保存的登录凭据登录 → 获取全部课程 → 逐门采集 → 分文件保存。 */
public class MoodleHomework {
    public static void run(Consumer<Progress> progressListener) throws Exception {
        run(null, progressListener);
    }

    public static void run(String accountKey, Consumer<Progress> progressListener) throws Exception {
        run(accountKey, null, null, progressListener);
    }

    public static void run(String accountKey, String loginUsername, char[] loginPassword,
                    Consumer<Progress> progressListener) throws Exception {
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                     .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(
                     new BrowserType.LaunchOptions().setChannel("msedge").setHeadless(false));
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setLocale("zh-CN").setTimezoneId(AssignmentCollector.TIME_ZONE.getId()))) {
            Page page = context.newPage();
            page.setDefaultTimeout(20_000);
            page.setDefaultNavigationTimeout(60_000);
            publish(progressListener, "opening_browser", "正在打开 Edge 登录窗口。", 0, 0,
                    List.of(), null, null);
            page.navigate(CourseCatalog.DASHBOARD, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            if (!isLoggedIn(page)) {
                if (loginUsername != null && loginPassword != null) {
                    publish(progressListener, "waiting_login", "正在通过学校统一身份认证登录。",
                            0, 0, List.of(), null, null);
                    loginWithSavedCredential(page, loginUsername, loginPassword);
                    waitForManualLogin(page, 60_000);
                } else {
                    System.out.println("请在新弹出的 Edge 中手动登录砺儒云课堂。");
                    System.out.println("请在 5 分钟内手动登录；进入砺儒云课堂首页或个人主页后会自动继续，无需按回车。");
                    publish(progressListener, "waiting_login", "请在新打开的 Edge 窗口手动登录；出现砺儒云课堂首页或个人课程页后会自动继续。",
                            0, 0, List.of(), null, null);
                    waitForManualLogin(page, 300_000);
                }
            }
            System.out.println("已检测到登录状态，开始读取课程目录。");

            // 1. 先完成整个课程目录的分页读取，再进入任何一门课程。
            publish(progressListener, "reading_courses", "已登录，正在读取课程目录和分页。",
                    0, 0, List.of(), null, null);
            CourseCatalog.Catalog catalog = CourseCatalog.findCourses(page);
            String timestamp = LocalDateTime.now(AssignmentCollector.TIME_ZONE)
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            Path outputRoot = accountKey == null ? Path.of("output") : Path.of("output", accountKey);
            Path output = outputRoot.resolve("run-" + timestamp).toAbsolutePath();
            Files.createDirectories(output);
            List<CourseResult> results = new ArrayList<>();
            publish(progressListener, "collecting", "课程目录读取完成，开始采集作业。",
                    catalog.pagesRead(), catalog.courses().size(), results, null, output.toString());
            for (CourseCatalog.Course course : catalog.courses()) {
                System.out.println("采集课程：" + course.name());
                publish(progressListener, "collecting", "正在采集课程：" + course.name(),
                        catalog.pagesRead(), catalog.courses().size(), results, course.name(), output.toString());
                CourseResult result;
                try {
                    // 2. 每门课程独立处理；某门访问失败不阻断后面的课程。
                    var assignments = AssignmentCollector.collect(page, course);
                    result = new CourseResult(course.id(), course.name(), course.url(),
                            AssignmentCollector.sourceUrl(course), collectedAt(),
                            AssignmentCollector.TIME_ZONE.getId(), assignments.isEmpty() ? "empty" : "ok",
                            assignments.size(), assignments, null);
                } catch (RuntimeException e) {
                    result = new CourseResult(course.id(), course.name(), course.url(),
                            AssignmentCollector.sourceUrl(course), collectedAt(),
                            AssignmentCollector.TIME_ZONE.getId(), "error", null, null, e.getMessage());
                }
                // 3. 文件名仅使用课程编号，避开课程名中的 Windows 非法文件字符。
                writeJson(output.resolve("course-" + course.id() + ".json"), result);
                results.add(result);
                writeSummary(output, catalog, results, "running");
                System.out.println("  " + result.status() + "，作业数：" + result.assignmentCount());
                publish(progressListener, "collecting", "已完成课程：" + course.name(),
                        catalog.pagesRead(), catalog.courses().size(), results, course.name(), output.toString());
            }
            long failed = results.stream().filter(r -> r.status().equals("error")).count();
            String finalStatus = failed == 0 ? "completed" : "partial";
            writeSummary(output, catalog, results, finalStatus);
            System.out.println("已处理 " + results.size() + " 门课程，结果目录：" + output);
            publish(progressListener, finalStatus,
                    failed == 0 ? "全部课程已采集完成。" : failed + " 门课程采集失败；其他课程结果已保存。",
                    catalog.pagesRead(), catalog.courses().size(), results, null, output.toString());
            if (failed > 0) throw new IllegalStateException(failed + " 门课程采集失败，其他课程结果已保存。");
        }
    }

    static void loginWithSavedCredential(Page page, String username, char[] password) {
        page.navigate(CourseCatalog.SITE + "/login/index.php",
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        Locator ssoLink = page.locator("#ssobtn");
        ssoLink.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        ssoLink.click();
        page.waitForFunction("() => location.hostname === 'sso.scnu.edu.cn' && document.readyState !== 'loading'", null,
                new Page.WaitForFunctionOptions().setTimeout(60_000));

        if (page.url().contains("/AccountService/user/login.html")) {
            Frame loginFrame = null;
            List<String> frameDiagnostics = List.of();
            long formDeadline = System.currentTimeMillis() + 20_000;
            do {
                List<Frame> candidateFrames = new ArrayList<>();
                List<String> observedFrames = new ArrayList<>();
                for (int i = 0; i < page.frames().size(); i++) {
                    Frame frame = page.frames().get(i);
                    Locator accountFields = accountFields(frame);
                    Locator passwordFields = passwordFields(frame);
                    long accountCount = accountFields.count();
                    long passwordCount = passwordFields.count();
                    observedFrames.add("区域" + i + "(账号框=" + accountCount
                            + ",密码框=" + passwordCount + ",可见控件=" + visibleFieldDiagnostics(frame) + ")");
                    if (accountCount == 1 && passwordCount == 1) candidateFrames.add(frame);
                }
                frameDiagnostics = observedFrames;
                if (candidateFrames.size() == 1) loginFrame = candidateFrames.get(0);
                else if (System.currentTimeMillis() < formDeadline) page.waitForTimeout(250);
            } while (loginFrame == null && System.currentTimeMillis() < formDeadline);

            if (loginFrame == null) {
                throw new IllegalStateException("学校统一认证登录表单未能唯一定位账号和密码框。"
                        + String.join("；", frameDiagnostics));
            }
            Locator accountField = accountFields(loginFrame);
            Locator passwordField = passwordFields(loginFrame);
            accountField.fill(username);
            passwordField.fill(new String(password));
            Locator signIn = loginFrame.locator("#btn-password-login:visible, button:visible:has-text('登录 Sign in'), "
                    + "input[type='submit']:visible, [role='button']:visible:has-text('登录 Sign in')");
            if (signIn.count() != 1) {
                Locator loginForm = passwordField.locator("xpath=ancestor::form[1]");
                Locator formSubmit = loginForm.locator("#btn-password-login:visible, button:visible:has-text('登录 Sign in'), "
                        + "input[type='submit']:visible, [role='button']:visible:has-text('登录 Sign in')");
                if (formSubmit.count() == 1) signIn = formSubmit;
            }
            if (signIn.count() != 1) {
                Locator visibleSignInText = loginFrame.locator(":text-is('登录 Sign in'):visible");
                if (visibleSignInText.count() == 1) signIn = visibleSignInText;
            }
            if (signIn.count() != 1) {
                throw new IllegalStateException("学校统一认证登录按钮未能唯一定位。可见提交按钮="
                        + signIn.count() + "，登录表单数=" + loginFrame.locator("form:visible").count());
            }
            signIn.click();
            page.waitForFunction("""
                    () => (location.hostname === 'sso.scnu.edu.cn' && location.pathname === '/AccountService/openapi/auth.html')
                       || location.hostname === 'moodle.scnu.edu.cn'
                    """, null, new Page.WaitForFunctionOptions().setTimeout(60_000));
        }

        if (page.url().contains("/AccountService/openapi/auth.html")) {
            // SSO 页面会同时渲染桌面和响应式确认链接；重复链接指向同一动作，优先选可见的第一个。
            Locator confirmLogin = page.locator("a[href='javascript:gotoApp();']:visible").first();
            confirmLogin.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
            confirmLogin.click();
        }
    }

    private static Locator accountFields(Frame frame) {
        return frame.locator("#account:visible, input[type='text']:visible, input[type='email']:visible, input[type='tel']:visible, "
                + "input:not([type]):visible, input[autocomplete='username']:visible");
    }

    private static Locator passwordFields(Frame frame) {
        return frame.locator("input[type='password']:visible, [role='textbox'][autocomplete='current-password']:visible");
    }

    private static String visibleFieldDiagnostics(Frame frame) {
        Object fields = frame.locator("input:visible, [role='textbox']:visible, [contenteditable='true']:visible, "
                        + "#account:visible, #btn-password-login:visible")
                .evaluateAll("elements => elements.map(element => ({"
                        + "tag: element.tagName.toLowerCase(),"
                        + "id: element.id || '',"
                        + "type: element.getAttribute('type') || '',"
                        + "role: element.getAttribute('role') || '',"
                        + "name: element.getAttribute('name') || '',"
                        + "autocomplete: element.getAttribute('autocomplete') || '',"
                        + "ariaLabel: element.getAttribute('aria-label') || '',"
                        + "placeholder: element.getAttribute('placeholder') || ''"
                        + "}))");
        return String.valueOf(fields);
    }

    private static boolean isLoggedIn(Page page) {
        return page.locator("body#page-my-index " + CourseCatalog.ROOT).count() > 0
                || page.locator("body#page-site-index #user-menu-toggle").count() > 0;
    }

    private static void publish(Consumer<Progress> listener, String stage, String message,
                                int catalogPagesRead, int totalCourses, List<CourseResult> results,
                                String currentCourse, String outputDirectory) {
        int assignments = results.stream().filter(r -> r.assignmentCount() != null)
                .mapToInt(CourseResult::assignmentCount).sum();
        listener.accept(new Progress(stage, message, catalogPagesRead, totalCourses, results.size(),
                assignments, currentCourse, outputDirectory, List.copyOf(results)));
    }

    static void waitForManualLogin(Page page, double timeoutMillis) {
        try {
            // SSO 可能把登录后的用户送回 Moodle 首页；用户菜单是该首页的登录态标记。
            // CourseCatalog.findCourses 随后会打开真正的个人课程页并验证课程概览。
            page.locator("body#page-my-index " + CourseCatalog.ROOT
                    + ", body#page-site-index #user-menu-toggle").waitFor(
                    new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED)
                            .setTimeout(timeoutMillis));
        } catch (TimeoutError e) {
            throw new IllegalStateException("等待登录超时。请在打开的 Edge 中登录，停留在砺儒云课堂首页或进入个人主页。", e);
        }
    }

    static void writeSummary(Path output, CourseCatalog.Catalog catalog, List<CourseResult> results,
                             String status) throws Exception {
        List<CourseIndex> courses = results.stream().map(r -> new CourseIndex(r.courseId(), r.courseName(),
                r.status(), r.assignmentCount(), "course-" + r.courseId() + ".json", r.error())).toList();
        int assignments = results.stream().filter(r -> r.assignmentCount() != null)
                .mapToInt(CourseResult::assignmentCount).sum();
        long empty = results.stream().filter(r -> r.status().equals("empty")).count();
        long failed = results.stream().filter(r -> r.status().equals("error")).count();
        writeJson(output.resolve("summary.json"), new Summary(status, collectedAt(),
                "课程概览：全部及已归档课程；仅采集当前账号可访问的作业活动", catalog.pagesRead(),
                catalog.courses().size(), results.size(), assignments, empty, failed, courses));
    }

    static void writeJson(Path output, Object value) throws Exception {
        String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                .serializeNulls().create().toJson(value);
        // 先完整写入临时文件，再替换目标，避免中途退出留下半份 JSON。
        Path temp = output.resolveSibling(output.getFileName() + ".tmp");
        Files.writeString(temp, json + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String collectedAt() {
        return OffsetDateTime.now(AssignmentCollector.TIME_ZONE).toString();
    }

    public record CourseResult(long courseId, String courseName, String courseUrl, String sourceUrl,
                               String collectedAt, String timeZone, String status, Integer assignmentCount,
                               List<AssignmentCollector.Assignment> assignments, String error) {}
    record CourseIndex(long courseId, String courseName, String status, Integer assignmentCount,
                       String file, String error) {}
    record Summary(String status, String collectedAt, String scope, int catalogPagesRead, int courseCount,
                   int processedCourseCount, int assignmentCount, long emptyCourseCount,
                   long failedCourseCount, List<CourseIndex> courses) {}
    public record Progress(String stage, String message, int catalogPagesRead, int totalCourses,
                           int processedCourses, int assignmentCount, String currentCourse,
                           String outputDirectory, List<CourseResult> courses) {}
}
