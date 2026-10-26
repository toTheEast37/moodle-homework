package cn.example.collector;

import com.microsoft.playwright.*;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** 本地合成网站回归测试：不使用真实账号，也不向学者网发请求。 */
class ScholatLoginTest {
    static Playwright playwright;
    static Browser browser;
    BrowserContext context;
    Page page;
    static final String SITE = "https://www.scholat.com";
    static final String COURSES = "<a href='myCourses.html' target='_blank'>我的课程</a>";
    static final String BLOCKS = """
            <div id='tabs_4'><div id='homeworkList'>
              <input type='hidden' id='homeworkId'><input type='hidden' id='courseId'>
              <div><a target='_blank' href='course/demo'><img alt=''></a>
                <div><p>测试课程</p><p>教师信息</p></div></div>
              <div>
              <table class='homeworkList'><tr><td><a href='detail.html?homeworkId=34'>实验一</a></td>
                <td>布置日期</td><td>2026-11-06</td><td>未提交</td><td>查看</td></tr></table>
              <a href='more.html?courseId=12'>更多</a>
              </div><li>到底了！</li>
            </div></div>
            """;
    static final String TABS = """
            <a onclick='getCourseHomeworkList(); return false;' href='#tabs_4'>作业动态</a>
            <script>let clicks=0; function getCourseHomeworkList() {
              if (++clicks > 1) throw Error('不应重复点击');
              setTimeout(() => document.body.insertAdjacentHTML('beforeend', __BLOCKS__), 120);
            }</script>
            """.replace("__BLOCKS__", new com.google.gson.Gson().toJson(BLOCKS));

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

    @AfterEach void closeContext() { context.close(); }

    void serve(Map<String, String> pages) {
        context.route("**/*", route -> route.fulfill(new Route.FulfillOptions()
                .setContentType("text/html; charset=utf-8")
                .setBody(pages.getOrDefault(java.net.URI.create(route.request().url()).getPath(), "未知页面"))));
    }

    @Test void loginContinuesFromAlternateHomepageAndBlankTargetWithModal() {
        serve(Map.of("/login.html", """
                <form id='loginForm' action='/Auth.html'>
                  <input id='j_username'><input id='j_password_ext' type='password'>
                  <input id='login' type='submit' value='登录'>
                </form>
                """, "/Auth.html", "<script>location.href='/user/home.html'</script>",
                "/user/home.html", COURSES.replace("myCourses.html", "../myCourses.html") + """
                <div style='position:fixed;inset:0;background:white;z-index:99'>实名认证
                  <button onclick='this.parentElement.remove()'>暂不认证</button></div>
                """, "/myCourses.html", TABS));
        Page loggedIn = ScholatLogin.loginWithSavedCredential(page, "test-only", "fake-password".toCharArray());
        var messages = new ArrayList<String>();
        Page overview = ScholatLogin.openHomeworkOverview(loggedIn, messages::add, 3000);
        assertEquals(SITE + "/myCourses.html", overview.url());
        assertEquals(1, context.pages().size());
        assertTrue(messages.stream().anyMatch(message -> message.contains("我的课程")));
        var result = ScholatHomework.readCourse(overview, overview.locator(ScholatLogin.HOMEWORK_BLOCKS).first());
        assertEquals(12, result.courseId());
        assertEquals(1, result.assignmentCount());
        assertEquals("2026-11-06", result.assignments().get(0).deadline());
    }

    @Test void loginRecognizesUserNavigationDirectlyToCourses() {
        serve(Map.of("/Auth.html", "<script>setTimeout(()=>location.href='/myCourses.html',120)</script>",
                "/myCourses.html", TABS));
        page.navigate(SITE + "/Auth.html");
        Page loggedIn = ScholatLogin.waitForLogin(page, 3000);
        assertSame(page, loggedIn);
        assertEquals(1, ScholatLogin.openHomeworkOverview(loggedIn).locator(ScholatLogin.HOMEWORK_BLOCKS).count());
    }

    @Test void optionalVerificationCanBeClosedBeforeCourseEntryAppears() {
        serve(Map.of("/user/home.html", """
                <nav style='display:none'>__COURSES__</nav>
                <div>实名认证<button onclick="document.querySelector('nav').style.display='block';this.parentElement.remove()">暂不认证</button></div>
                """.replace("__COURSES__", COURSES)));
        page.navigate(SITE + "/user/home.html");
        assertSame(page, ScholatLogin.waitForLogin(page, 3000));
        assertTrue(page.locator("nav").isVisible());
    }

    @Test void loginSelectsCoursesInNewTabInsteadOfWaitingOnOriginalPage() {
        serve(Map.of("/Auth.html", "<body>登录跳转中</body>", "/myCourses.html", TABS));
        page.navigate(SITE + "/Auth.html");
        Page popup = context.newPage();
        popup.navigate(SITE + "/myCourses.html");
        Page loggedIn = ScholatLogin.waitForLogin(page, 3000);
        assertSame(popup, loggedIn);
        assertSame(popup, ScholatLogin.openHomeworkOverview(loggedIn));
    }

    @Test void alreadyOpenedOverviewDoesNotRequireHomepageOrTabClick() {
        serve(Map.of("/myCourses.html", BLOCKS));
        page.navigate(SITE + "/myCourses.html");
        assertSame(page, ScholatLogin.waitForLogin(page, 1500));
        assertSame(page, ScholatLogin.openHomeworkOverview(page));
    }

    @Test void delayedVerificationPromptOnCoursePageIsDismissed() {
        serve(Map.of("/myCourses.html", TABS + """
                <div style='position:fixed;inset:0;z-index:99;background:white'>实名认证
                <button style='display:none' id='postpone' onclick='this.parentElement.remove()'>暂不认证</button></div>
                <script>setTimeout(()=>document.querySelector('#postpone').style.display='block',150)</script>
                """));
        page.navigate(SITE + "/myCourses.html");
        assertSame(page, ScholatLogin.openHomeworkOverview(page, ignored -> {}, 3000));
        assertEquals(1, ((Number) page.evaluate("clicks")).intValue());
    }

    @Test void manualCloseOfUnrecognizedPromptAllowsAutomaticContinuation() {
        serve(Map.of("/myCourses.html", TABS + """
                <div id='prompt' style='position:fixed;inset:0;z-index:99;background:white'>请自行关闭提示</div>
                <script>setTimeout(()=>document.querySelector('#prompt').remove(),700)</script>
                """));
        page.navigate(SITE + "/myCourses.html");
        assertSame(page, ScholatLogin.openHomeworkOverview(page, ignored -> {}, 3000));
    }

    @Test void detectsNewOverviewTabOpenedWhileWaiting() {
        serve(Map.of("/myCourses.html", """
                <a href='/overview.html' target='_blank' onclick='getCourseHomeworkList()'>作业动态</a>
                <script>function getCourseHomeworkList() {}</script>
                """, "/overview.html", BLOCKS));
        page.navigate(SITE + "/myCourses.html");
        Page overview = ScholatLogin.openHomeworkOverview(page, ignored -> {}, 3000);
        assertNotSame(page, overview);
        assertEquals(SITE + "/overview.html", overview.url());
    }

    @Test void hiddenStaleTablesDoNotCountAsLoadedOverview() {
        serve(Map.of("/myCourses.html", "<div style='display:none'>" + BLOCKS + "</div>"));
        page.navigate(SITE + "/myCourses.html");
        var error = assertThrows(IllegalStateException.class,
                () -> ScholatLogin.openHomeworkOverview(page, ignored -> {}, 150));
        assertTrue(error.getMessage().contains("未找到已显示的课程作业表格"));
    }

    @Test void authOrPublicPageWithoutCourseEvidenceIsNotLoginSuccess() {
        serve(Map.of("/Auth.html", "登录处理中"));
        page.navigate(SITE + "/Auth.html?private=value");
        var error = assertThrows(IllegalStateException.class, () -> ScholatLogin.waitForLogin(page, 150));
        assertTrue(error.getMessage().contains("/Auth.html"));
        assertFalse(error.getMessage().contains("private=value"));
    }

    @Test void visibleLoginFormIsNotAcceptedEvenIfCourseLinkExists() {
        serve(Map.of("/login.html", "<form id='loginForm'>账号密码错误</form>" + COURSES));
        page.navigate(SITE + "/login.html");
        assertThrows(IllegalStateException.class, () -> ScholatLogin.waitForLogin(page, 150));
    }

    @Test void unrelatedSiteCannotProvideLoginEvidence() {
        serve(Map.of("/myCourses.html", BLOCKS));
        page.navigate("https://example.invalid/myCourses.html");
        assertThrows(IllegalStateException.class, () -> ScholatLogin.waitForLogin(page, 150));
    }

    @Test void siblingCourseHeadingsStayPairedWithTheirOwnTables() {
        String secondCourse = BLOCKS.substring(BLOCKS.indexOf("<input"), BLOCKS.indexOf("<li>"))
                .replace("测试课程", "第二门课程").replace("course/demo", "/course/second")
                .replace("courseId=12", "courseId=56").replace("homeworkId=34", "homeworkId=78")
                .replace("2026-11-06", "2026-11-07");
        serve(Map.of("/myCourses.html", BLOCKS.replace("<li>", secondCourse + "<li>")));
        page.navigate(SITE + "/myCourses.html");
        Locator blocks = page.locator(ScholatLogin.HOMEWORK_BLOCKS);
        assertEquals(2, blocks.count());
        // 证明旧代码在表格 div 内寻找课程标题必然找不到。
        assertEquals(0, blocks.first().locator("p a[href^='course/']").count());
        var first = ScholatHomework.readCourse(page, blocks.nth(0));
        var second = ScholatHomework.readCourse(page, blocks.nth(1));
        assertEquals("测试课程", first.courseName());
        assertEquals(12, first.courseId());
        assertEquals(34, first.assignments().get(0).id());
        assertEquals("第二门课程", second.courseName());
        assertEquals(SITE + "/course/second", second.courseUrl());
        assertEquals(56, second.courseId());
        assertEquals(78, second.assignments().get(0).id());
        assertEquals("2026-11-07", second.assignments().get(0).deadline());
    }

    @Test void missingHeadingCannotReusePreviousCoursesTable() {
        String tableOnly = "<div><table class='homeworkList'><tr><td>错误结构</td></tr></table></div>";
        serve(Map.of("/myCourses.html", BLOCKS.replace("<li>", tableOnly + "<li>")));
        page.navigate(SITE + "/myCourses.html");
        var error = assertThrows(IllegalStateException.class, () -> ScholatHomework.readCourse(page,
                page.locator(ScholatLogin.HOMEWORK_BLOCKS).nth(1)));
        assertTrue(error.getMessage().contains("未找到对应的课程标题区域"));
    }

    @Test void missingCourseIdReportsFieldInsteadOfWaitingForAbsentLink() {
        serve(Map.of("/myCourses.html", BLOCKS.replace("courseId=12", "other=12")));
        page.navigate(SITE + "/myCourses.html");
        var error = assertThrows(IllegalStateException.class, () -> ScholatHomework.readCourse(page,
                page.locator(ScholatLogin.HOMEWORK_BLOCKS).first()));
        assertTrue(error.getMessage().contains("测试课程"));
        assertTrue(error.getMessage().contains("缺少带 courseId 的链接"));
    }

    @Test void missingHomeworkLinkReportsCourseAndRowInsteadOfTimingOut() {
        serve(Map.of("/myCourses.html", BLOCKS.replace("homeworkId=34", "other=34")));
        page.navigate(SITE + "/myCourses.html");
        var error = assertThrows(IllegalStateException.class, () -> ScholatHomework.readCourse(page,
                page.locator(ScholatLogin.HOMEWORK_BLOCKS).first()));
        assertTrue(error.getMessage().contains("测试课程"));
        assertTrue(error.getMessage().contains("第 1 行缺少带 homeworkId"));
    }

    @Test void tdHeadingAndExplicitEmptyRowAreNotAssignments() {
        serve(Map.of("/myCourses.html", BLOCKS.replace("<table class='homeworkList'>", """
                <table class='homeworkList'>
                  <tr><td>作业名称</td><td>开始日期</td><td>截止日期</td><td>状态</td><td>操作</td></tr>
                  <tr><td colspan='5'>暂无作业</td></tr>
                """)));
        page.navigate(SITE + "/myCourses.html");
        var result = ScholatHomework.readCourse(page, page.locator(ScholatLogin.HOMEWORK_BLOCKS).first());
        assertEquals(1, result.assignmentCount());
        assertEquals(34, result.assignments().get(0).id());
    }
}
