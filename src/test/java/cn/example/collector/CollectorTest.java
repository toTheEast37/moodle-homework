package cn.example.collector;

import com.google.gson.*;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 所有网络请求均在本地返回合成页面，测试不访问学校，也不使用账号。 */
class CollectorTest {
    static Playwright playwright;
    static Browser browser;
    BrowserContext context;
    Page page;

    @BeforeAll static void openBrowser() {
        playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setChannel("msedge").setHeadless(true));
    }

    @AfterAll static void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach void newPage() {
        context = browser.newContext();
        page = context.newPage();
        page.setDefaultTimeout(1500);
    }

    @AfterEach void closePage() { context.close(); }

    void serve(String html, int status) {
        context.route("**/*", route -> route.fulfill(new Route.FulfillOptions()
                .setStatus(status).setContentType("text/html; charset=utf-8").setBody(html)));
    }

    @Test void paginatesAndDeduplicatesCachedPagesAndArchivedCourses() {
        serve(dashboard(false, false), 200);
        var result = CourseCatalog.findCourses(page);
        assertEquals(List.of(101L, 102L, 103L, 104L), result.courses().stream().map(CourseCatalog.Course::id).toList());
        assertEquals(3, result.pagesRead());
        assertEquals("课程101", result.courses().get(0).name());
        assertEquals("all", page.locator(CourseCatalog.VIEW).getAttribute("data-grouping"));
        assertEquals(1, CourseCatalog.currentPageNumber(page));
    }

    @Test void manualLoginContinuesWhenDashboardAppearsAfterNavigation() {
        context.route("**/*", route -> {
            String html = route.request().url().contains("/login/")
                    ? "<body id='page-login-index'>登录中<script>setTimeout(()=>location.href='/my/index.php',120)</script></body>"
                    : "<body id='page-my-index'><section class='block_myoverview'>课程概览</section></body>";
            route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(html));
        });
        page.navigate(CourseCatalog.SITE + "/login/index.php");
        MoodleHomework.waitForManualLogin(page, 1500);
        assertEquals(CourseCatalog.DASHBOARD, page.url());
        assertEquals("page-my-index", page.locator("body").getAttribute("id"));
    }

    @Test void manualLoginDoesNotTreatPublicHomepageAsDashboard() {
        serve("<body id='page-site-index'><section class='block_myoverview'>不是真正的个人主页</section></body>", 200);
        page.navigate(CourseCatalog.SITE);
        assertThrows(IllegalStateException.class, () -> MoodleHomework.waitForManualLogin(page, 150));
    }

    @Test void explicitEmptyCourseListsAreValid() {
        serve(dashboard(true, false), 200);
        var result = CourseCatalog.findCourses(page);
        assertTrue(result.courses().isEmpty());
        assertEquals(2, result.pagesRead());
    }

    @Test void stalledNextPageFailsInsteadOfReturningIncompleteCatalog() {
        serve(dashboard(false, true), 200);
        assertThrows(TimeoutError.class, () -> CourseCatalog.findCourses(page));
    }

    @Test void unknownCourseListStructureIsNotAnEmptyList() {
        serve("<div>not a course list</div>", 200);
        page.navigate(CourseCatalog.DASHBOARD);
        assertThrows(IllegalStateException.class, () -> CourseCatalog.readCourses(page.locator("body")));
    }

    @Test void delayedAssignmentTableAndUnixDeadlineAreReadCorrectly() {
        String table = table(row(1001, "实验7", "1793894400"));
        serve(overview("<div id='assign_overview_collapsible'><a aria-controls='assign_overview' aria-expanded='true'></a>"
                + "<div id='assign_overview'><div data-region='loading-icon-container'>loading</div></div></div>")
                + "<script>setTimeout(()=>document.querySelector('#assign_overview').innerHTML="
                + new Gson().toJson(table) + ",120)</script>", 200);
        var result = AssignmentCollector.collect(page, course());
        assertEquals(1, result.size());
        assertEquals("2026-11-06T00:00+08:00", result.get(0).deadline());
        assertEquals("11月6日 星期五, 00:00", result.get(0).deadlineRaw());
        assertEquals("https://moodle.scnu.edu.cn/mod/assign/view.php?id=1001", result.get(0).url());
    }

    @Test void assignmentIdsAreDeduplicatedAndMissingDatesStayNull() {
        serve(assignmentPage(table(row(1002, "无截止", "0") + row(1002, "无截止", "0")
                + row(1003, "无日期列", null))), 200);
        var result = AssignmentCollector.collect(page, course());
        assertEquals(2, result.size());
        assertNull(result.get(0).deadline());
        assertNull(result.get(1).deadline());
    }

    @Test void missingAssignmentGroupOnAReadyCourseMeansEmpty() {
        serve(overview("<div id='quiz_overview_collapsible'>测验</div>"), 200);
        assertTrue(AssignmentCollector.collect(page, course()).isEmpty());
    }

    @Test void httpFailureIsNotRecordedAsEmpty() {
        serve("<body>Forbidden</body>", 403);
        assertThrows(IllegalStateException.class, () -> AssignmentCollector.collect(page, course()));
    }

    @Test void loginPageIsNotRecordedAsEmpty() {
        serve("<body id='page-login-index'>请登录</body>", 200);
        assertThrows(TimeoutError.class, () -> AssignmentCollector.collect(page, course()));
    }

    @Test void assignmentLoadingFailureIsNotRecordedAsEmpty() {
        serve(assignmentPage("<div data-region='loading-icon-container'>loading forever</div>"), 200);
        assertThrows(TimeoutError.class, () -> AssignmentCollector.collect(page, course()));
    }

    @Test void changedAssignmentTableStructureFailsClearly() {
        serve(assignmentPage("<table><tbody><tr><td>changed</td></tr></tbody></table>"), 200);
        assertThrows(IllegalStateException.class, () -> AssignmentCollector.collect(page, course()));
    }

    @Test void invalidTimestampFailsInsteadOfGuessingDate() {
        serve(assignmentPage(table(row(1001, "实验", "invalid"))), 200);
        assertThrows(NumberFormatException.class, () -> AssignmentCollector.collect(page, course()));
    }

    @Test void writesSeparateJsonAndPreservesErrorVersusEmpty(@TempDir Path output) throws Exception {
        var assignment = new AssignmentCollector.Assignment(1001, "作业", "/assign", null, null);
        List<MoodleHomework.CourseResult> results = List.of(
                result(101, "ok", 1, List.of(assignment), null),
                result(102, "empty", 0, List.of(), null),
                result(103, "error", null, null, "access denied"));
        for (var r : results) MoodleHomework.writeJson(output.resolve("course-" + r.courseId() + ".json"), r);
        MoodleHomework.writeSummary(output,
                new CourseCatalog.Catalog(List.of(course(), course(), course()), 3), results, "partial");
        JsonObject summary = JsonParser.parseString(Files.readString(output.resolve("summary.json"))).getAsJsonObject();
        assertEquals(3, summary.get("courseCount").getAsInt());
        assertEquals(1, summary.get("assignmentCount").getAsInt());
        assertEquals(1, summary.get("failedCourseCount").getAsInt());
        assertEquals(1, summary.get("emptyCourseCount").getAsInt());
        JsonObject failed = JsonParser.parseString(Files.readString(output.resolve("course-103.json"))).getAsJsonObject();
        assertTrue(failed.get("assignmentCount").isJsonNull());
        assertTrue(failed.get("assignments").isJsonNull());
        assertEquals(0, JsonParser.parseString(Files.readString(output.resolve("course-102.json")))
                .getAsJsonObject().get("assignmentCount").getAsInt());
        try (var files = Files.list(output)) {
            assertEquals(4, files.count()); // 临时文件已经被完整 JSON 替换。
        }
    }

    static CourseCatalog.Course course() { return new CourseCatalog.Course(101, "示例课程", CourseCatalog.SITE + "/course/view.php?id=101"); }
    static MoodleHomework.CourseResult result(long id, String status, Integer count,
                                               List<AssignmentCollector.Assignment> assignments, String error) {
        return new MoodleHomework.CourseResult(id, "课程" + id, "/course", "/overview", "now",
                "Asia/Shanghai", status, count, assignments, error);
    }
    static String overview(String content) {
        return "<body id='page-course-overview'><div id='course-overview-page' class='stateready'>" + content + "</div></body>";
    }
    static String assignmentPage(String content) {
        return overview("<div id='assign_overview_collapsible'><a aria-controls='assign_overview' aria-expanded='true'></a>"
                + "<div id='assign_overview'>" + content + "</div></div>");
    }
    static String table(String rows) {
        return "<table><thead><tr><th data-mdl-overview-column='name'>名称</th></tr></thead><tbody>" + rows + "</tbody></table>";
    }
    static String row(long id, String title, String seconds) {
        return "<tr data-mdl-overview-cmid='" + id + "'><td data-mdl-overview-item='name' data-mdl-overview-value='" + title
                + "'><a class='activityname' href='/mod/assign/view.php?id=" + id + "'>" + title + "</a></td>"
                + (seconds == null ? "" : "<td data-mdl-overview-item='duedate' data-mdl-overview-value='" + seconds
                + "'>11月6日 星期五, 00:00</td>") + "</tr>";
    }

    static String dashboard(boolean empty, boolean stalled) {
        // 保留缓存的隐藏第一页；下一页先变页号，再延迟插入内容，模拟真实 Moodle。
        return """
            <style>.hidden { display:none }</style>
            <section class="block_myoverview">
              <button id="groupingdropdown">分组</button>
              <a data-filter="grouping" data-value="all" href="#" onclick="changeGroup('all');return false">全部</a>
              <a data-filter="grouping" data-value="hidden" href="#" onclick="changeGroup('hidden');return false">归档</a>
              <div data-region="courses-view" data-grouping="all" data-sort="fullname" data-display="card"></div>
            </section>
            <script>
              const EMPTY = __EMPTY__, STALLED = __STALLED__;
              let serial=0, grouping='all';
              function card(id) { return `<div data-region="course-content" data-course-id="${id}">
                <a href="/course/view.php?id=${id}">图片</a>
                <a class="coursename" href="/course/view.php?id=${id}"><span class="multiline" title="课程${id}">课程${id}</span></a></div>`; }
              function contents(number) {
                if (EMPTY) return '<div data-region="empty-message">没有课程</div>';
                return (grouping==='hidden' ? [104,101] : number===1 ? [101,101,102] : [102,103]).map(card).join('');
              }
              function render(number, loading=false) {
                const nextDisabled=EMPTY || grouping==='hidden' || number===2;
                const cache=number===2 ? `<div data-region="paged-content-page" data-page="1" class="hidden">${contents(1)}</div>` : '';
                document.querySelector('[data-region="courses-view"]').innerHTML=`
                  <div data-region="paged-content-container" id="container-${++serial}">
                    ${cache}<div data-region="paged-content-page" data-page="${number}">
                      ${loading ? '<div data-region="loading-icon-container">loading</div>' : contents(number)}
                    </div>
                    <nav data-region="paging-bar" data-active-page-number="${number}">
                      <li data-control="previous" aria-disabled="${number===1}"><a href="#" onclick="move(1);return false">上一页</a></li>
                      <li data-control="next" class="${nextDisabled?'disabled':''}" aria-disabled="${nextDisabled}">
                        <a href="#" onclick="move(2);return false">下一页</a></li>
                    </nav>
                  </div>`;
              }
              function move(number) {
                if(STALLED)return;
                render(number,true);setTimeout(()=>render(number),120);
              }
              function changeGroup(group) {
                grouping=group;document.querySelector('[data-region="courses-view"]').setAttribute('data-grouping',group);
                setTimeout(()=>render(1),120);
              }
              render(1);
            </script>
            """.replace("__EMPTY__", String.valueOf(empty)).replace("__STALLED__", String.valueOf(stalled));
    }
}
