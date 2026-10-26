package cn.example;

import com.google.gson.*;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real page scripts and styles with local API fixtures; never visits either school platform. */
class DashboardTest {
    static final Gson GSON = new Gson();
    static Playwright playwright;
    static Browser browser;
    BrowserContext context;
    Page page;
    JsonObject snapshot;
    List<String> errors;
    static final String CREDENTIALS = """
            [{"id":10,"siteCode":"scnu-moodle","displayName":"我的云课堂","loginUsername":"demo-moodle"},
             {"id":11,"siteCode":"scholat","displayName":"我的学者网","loginUsername":"demo-scholat"}]
            """;
    static final String STATUS = """
            {"running":false,"now":"2026-10-26T12:00:00+08:00","activeCredentialId":null,
             "progress":{"stage":"idle","message":"展开账号与采集，更新平台作业。","totalCourses":0,"processedCourses":0,"courses":[]},
             "results":[
               {"runId":1,"siteCredentialId":10,"siteCode":"scnu-moodle","displayName":"我的云课堂","status":"completed","collectedAt":"2026-10-26T10:20:00",
                "progress":{"totalCourses":2,"processedCourses":2,"assignmentCount":3,"courses":[
                  {"courseId":101,"courseName":"软件工程","status":"ok","assignmentCount":3,"assignments":[
                    {"id":1,"title":"实验三：需求分析与建模","deadline":"2026-10-28T23:59:00+08:00","url":"https://moodle.example.test/assign/1"},
                    {"id":2,"title":"课程项目：系统原型","deadline":"2026-11-05T23:59:00+08:00"},
                    {"id":3,"title":"往期作业","deadline":"2026-10-20T23:59:00+08:00"}]},
                  {"courseId":102,"courseName":"Java 程序设计","status":"empty","assignmentCount":0,"assignments":[]}]}},
               {"runId":2,"siteCredentialId":11,"siteCode":"scholat","displayName":"我的学者网","status":"partial","collectedAt":"2026-10-26T10:25:00",
                "progress":{"totalCourses":2,"processedCourses":2,"assignmentCount":3,"courses":[
                  {"courseId":101,"courseName":"数据库原理","status":"ok","assignmentCount":3,"assignments":[
                    {"id":1,"title":"第六章 SQL 查询练习","deadline":"2026-10-26"},
                    {"id":2,"title":"数据库课程设计","deadline":null},
                    {"id":3,"title":"函数依赖与范式","deadline":"2026-11-02"}]},
                  {"courseId":103,"courseName":"计算机网络","status":"error","error":"课程页面暂时未能读取","assignments":null}]}}
             ]}
            """;

    @BeforeAll static void openBrowser() {
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setChannel("msedge").setHeadless(true));
    }
    @AfterAll static void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }
    @BeforeEach void openPage() {
        context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1440, 1000));
        page = context.newPage();
        page.setDefaultTimeout(4000);
        errors = new ArrayList<>();
        page.onPageError(errors::add);
        snapshot = JsonParser.parseString(STATUS).getAsJsonObject();
        context.route("**/*", route -> {
            String path = java.net.URI.create(route.request().url()).getPath();
            String body;
            String type = "application/json";
            try {
                switch (path) {
                    case "/api/session" -> body = "{\"authenticated\":true,\"username\":\"演示同学\"}";
                    case "/api/credentials" -> body = CREDENTIALS;
                    case "/api/status" -> body = GSON.toJson(snapshot);
                    case "/api/logout" -> body = "{}";
                    case "/", "/app.js", "/styles.css" -> {
                        body = Files.readString(Path.of("src/main/resources/web", path.equals("/") ? "index.html" : path.substring(1)));
                        type = path.endsWith(".js") ? "application/javascript" : path.endsWith(".css") ? "text/css" : "text/html";
                    }
                    default -> { route.abort(); return; }
                }
                route.fulfill(new Route.FulfillOptions().setContentType(type + "; charset=utf-8").setBody(body));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        page.navigate("http://dashboard.test/");
        page.waitForCondition(() -> page.locator("#assignmentMetric").textContent().equals("6"));
    }
    @AfterEach void closePage() {
        context.close();
        assertTrue(errors.isEmpty(), errors.toString());
    }
    void refresh() {
        page.evaluate("async () => { while (polling) await new Promise(resolve => setTimeout(resolve, 5)); await refresh(); }");
    }

    @Test void combinesBothPlatformsAndDoesNotDeduplicateCollidingIdentifiers() {
        assertEquals(5, page.locator(".todo-item").count());
        assertEquals("4", page.locator("#courseMetric").textContent());
        assertEquals("3", page.locator("#weekMetric").textContent());
        assertEquals("1", page.locator("#fortnightMetric").textContent());
        assertEquals("第六章 SQL 查询练习", page.locator(".todo-title").first().textContent());
        assertTrue(page.locator(".todo-item").first().textContent().contains("今天截止"));
        assertFalse(page.locator(".todo-item").first().textContent().contains("00:00"));
        assertEquals(2, page.locator(".platform-item").count());
        assertTrue(page.locator("#platformSummary").textContent().contains("部分完成"));
    }

    @Test void platformSearchAndDeadlineFiltersWorkTogether() {
        page.locator("[data-site='scholat']").click();
        assertEquals(3, page.locator(".todo-item").count());
        page.locator("#todoSearch").fill("SQL");
        assertEquals(1, page.locator(".todo-item").count());
        page.locator("#todoSearch").fill("没有这个作业");
        assertEquals(0, page.locator(".todo-item").count());
        assertTrue(page.locator("#todoList").textContent().contains("当前条件下没有作业"));
        page.locator("#todoSearch").fill("");
        page.locator("[data-site='all']").click();
        page.locator("#todoDeadlineFilter").selectOption("overdue");
        assertEquals(1, page.locator(".todo-item").count());
        assertTrue(page.locator("#todoList").textContent().contains("往期作业"));
        page.locator("#todoDeadlineFilter").selectOption("none");
        assertTrue(page.locator("#todoList").textContent().contains("数据库课程设计"));
    }

    @Test void collectionAccountSelectionDoesNotHideOtherPlatformAndCoursesStayDistinct() {
        page.locator("#collectShortcut").click();
        assertTrue(page.locator("#accounts").getAttribute("open") != null);
        page.locator("#credentialSelect").selectOption("10");
        refresh();
        assertEquals(5, page.locator(".todo-item").count());
        page.locator(".course-item").filter(new Locator.FilterOptions().setHasText("数据库原理")).click();
        assertEquals("数据库原理", page.locator("#assignmentHeading").textContent());
        assertTrue(page.locator("#assignmentContent").textContent().contains("SQL"));
        assertFalse(page.locator("#assignmentContent").textContent().contains("需求分析"));
        page.locator(".course-item").filter(new Locator.FilterOptions().setHasText("软件工程")).click();
        assertTrue(page.locator("#assignmentContent").textContent().contains("需求分析"));
    }

    @Test void runningAndFailedCollectionKeepSavedResultsFromOtherAccounts() {
        snapshot.addProperty("running", true);
        snapshot.addProperty("activeCredentialId", 10);
        snapshot.add("progress", JsonParser.parseString("""
                {"stage":"collecting","message":"正在更新","totalCourses":2,"processedCourses":1,"courses":[
                  {"courseId":101,"courseName":"软件工程","status":"ok","assignments":[
                    {"id":99,"title":"本轮新作业","deadline":"2026-10-29"}]}]}
                """));
        refresh();
        assertTrue(page.locator("#todoList").textContent().contains("本轮新作业"));
        assertTrue(page.locator("#todoList").textContent().contains("SQL"));
        assertEquals(4, page.locator(".course-item").count());
        snapshot.addProperty("running", false);
        snapshot.getAsJsonObject("progress").addProperty("stage", "error");
        refresh();
        assertTrue(page.locator("#todoList").textContent().contains("需求分析"));
        assertTrue(page.locator("#todoList").textContent().contains("SQL"));
    }

    @Test void successfulEmptySnapshotsAndNoSnapshotsHaveDifferentEmptyStates() {
        for (JsonElement result : snapshot.getAsJsonArray("results")) result.getAsJsonObject().getAsJsonObject("progress").add("courses", new JsonArray());
        refresh();
        assertEquals("0", page.locator("#assignmentMetric").textContent());
        assertEquals("0", page.locator("#weekMetric").textContent());
        assertTrue(page.locator("#todoList").textContent().contains("已保存的课程中没有"));
        snapshot.add("results", new JsonArray());
        refresh();
        assertEquals("—", page.locator("#assignmentMetric").textContent());
        assertTrue(page.locator("#todoList").textContent().contains("绑定并采集平台账号"));
    }

    @Test void sameSiteDifferentAccountsRemainSeparateAndUnsafeLinksAreNotClickable() {
        var duplicate = snapshot.getAsJsonArray("results").get(0).deepCopy().getAsJsonObject();
        duplicate.addProperty("siteCredentialId", 12);
        duplicate.addProperty("displayName", "另一个云课堂账号");
        duplicate.getAsJsonObject("progress").getAsJsonArray("courses").get(0).getAsJsonObject()
                .getAsJsonArray("assignments").get(0).getAsJsonObject().addProperty("url", "javascript:alert(1)");
        snapshot.getAsJsonArray("results").add(duplicate);
        refresh();
        assertEquals(6, page.locator(".course-item").count());
        assertEquals(7, page.locator(".todo-item").count());
        assertEquals(0, page.locator("a[href^='javascript:']").count());
    }

    @Test void fitsMobileAndTabletWithAccountPanelExpanded() {
        page.locator("#collectShortcut").click();
        for (int width : List.of(320, 390, 760, 900, 1100, 1440)) {
            page.setViewportSize(width, 900);
            assertEquals(Boolean.TRUE, page.evaluate("document.documentElement.scrollWidth <= innerWidth"), "Overflow at " + width);
        }
    }

    @Test void logoutClearsDashboardAndCredentials() {
        page.locator("#logoutButton").click();
        page.waitForCondition(() -> page.locator("#loginScreen").isVisible());
        assertEquals(0, page.locator(".todo-item").count());
        assertEquals(0, page.locator(".credential-item").count());
    }

    @Test void exportsIsolatedDemoForVisualReview() throws Exception {
        Path preview = Path.of("target/dashboard-preview");
        Files.createDirectories(preview);
        Files.writeString(preview.resolve("status.json"), STATUS);
        Files.writeString(preview.resolve("credentials.json"), CREDENTIALS);
        Path screenshots = Path.of("target/dashboard-screenshots");
        Files.createDirectories(screenshots);
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("desktop-overview.png")));
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("desktop.png")).setFullPage(true));
        page.setViewportSize(390, 844);
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("mobile.png")).setFullPage(true));
    }
}
