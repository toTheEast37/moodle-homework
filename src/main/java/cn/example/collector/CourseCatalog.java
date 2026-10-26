package cn.example.collector;

import com.microsoft.playwright.*;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** 只负责课程目录：切换分组、翻页、去重，不进入作业详情。 */
public final class CourseCatalog {
    static final String SITE = "https://moodle.scnu.edu.cn";
    static final String DASHBOARD = SITE + "/my/index.php";
    static final String ROOT = ".block_myoverview";
    static final String VIEW = ROOT + " [data-region='courses-view']";
    static final Pattern ID = Pattern.compile("(?:^|&)id=(\\d+)(?:&|$)");

    private CourseCatalog() {}

    public static Catalog findCourses(Page page) {
        Response response = page.navigate(DASHBOARD);
        requireHttpSuccess(response);
        page.locator(VIEW).waitFor();
        waitForCurrentPage(page);
        Locator view = page.locator(VIEW);
        String originalGroup = view.getAttribute("data-grouping");
        String originalSort = view.getAttribute("data-sort");
        String originalDisplay = view.getAttribute("data-display");
        Map<Long, Course> unique = new LinkedHashMap<>();
        int pagesRead = 0;
        try {
            // 目录排序保持稳定；先把目录读完，再逐门访问，避免“最近访问”排序改变分页。
            changeFilter(page, "sort", "fullname");
            changeFilter(page, "display", "card");
            // Moodle 的 all 不含归档课程，所以再读取 hidden；这里只切换视图，不取消归档。
            for (String group : List.of("all", "hidden")) {
                changeFilter(page, "grouping", group);
                goToFirstPage(page);
                Set<Integer> visitedPages = new HashSet<>();
                while (true) {
                    waitForCurrentPage(page);
                    int number = currentPageNumber(page);
                    if (!visitedPages.add(number) || visitedPages.size() > 1000) {
                        throw new IllegalStateException("课程分页重复或超过上限，停止以避免漏课。");
                    }
                    // 已翻过的页仍在 DOM 里，只读取当前页编号对应的容器。
                    Locator current = page.locator(VIEW).locator(
                            "[data-region='paged-content-page'][data-page='" + number + "']");
                    for (Course course : readCourses(current)) unique.putIfAbsent(course.id(), course);
                    pagesRead++;
                    System.out.println("读取课程目录：" + group + " 第 " + number
                            + " 页，累计 " + unique.size() + " 门课程");
                    Locator next = page.locator(VIEW).locator("[data-control='next']");
                    if (next.count() != 1) throw new IllegalStateException("课程下一页控件结构不符合预期。");
                    if (disabled(next)) break;
                    next.locator("a").click();
                    waitForPageChange(page, number);
                }
            }
            return new Catalog(List.copyOf(unique.values()), pagesRead);
        } finally {
            // 恢复用户原来的目录显示偏好；恢复失败不覆盖真正的采集错误。
            try {
                changeFilter(page, "grouping", originalGroup);
                changeFilter(page, "sort", originalSort);
                changeFilter(page, "display", originalDisplay);
            } catch (RuntimeException e) {
                System.err.println("提示：未能恢复课程概览的显示筛选，请手动检查。");
            }
        }
    }

    static List<Course> readCourses(Locator current) {
        List<Course> courses = new ArrayList<>();
        Locator cards = current.locator("[data-region='course-content'][data-course-id]");
        if (cards.count() == 0 && current.locator("[data-region='empty-message']").count() == 0) {
            throw new IllegalStateException("课程列表既没有课程也没有空列表标志，不能判定为零门课程。");
        }
        for (Locator card : cards.all()) {
            String href = card.locator("a[href*='/course/view.php']").first().getAttribute("href");
            URI uri = URI.create(DASHBOARD).resolve(href);
            var matcher = ID.matcher(Objects.requireNonNullElse(uri.getRawQuery(), ""));
            if (!"moodle.scnu.edu.cn".equals(uri.getHost()) || !"/course/view.php".equals(uri.getPath())
                    || !matcher.find()) throw new IllegalStateException("课程链接格式不符合预期。");
            long id = Long.parseLong(matcher.group(1));
            if (id <= 0 || !Long.toString(id).equals(card.getAttribute("data-course-id"))) {
                throw new IllegalStateException("课程卡片编号和链接编号不一致。");
            }
            String name = card.locator(".coursename .multiline[title]").first().getAttribute("title");
            if (name == null || name.isBlank()) throw new IllegalStateException("课程名称为空。");
            courses.add(new Course(id, name.strip(), SITE + "/course/view.php?id=" + id));
        }
        return courses;
    }

    static void changeFilter(Page page, String kind, String value) {
        String attribute = switch (kind) {
            case "grouping" -> "data-grouping";
            case "sort" -> "data-sort";
            case "display" -> "data-display";
            default -> throw new IllegalArgumentException("未知筛选类型");
        };
        Locator view = page.locator(VIEW);
        if (value.equals(view.getAttribute(attribute))) return;
        String before = view.locator("[data-region='paged-content-container']").getAttribute("id");
        String button = switch (kind) {
            case "grouping" -> "#groupingdropdown";
            case "sort" -> "#sortingdropdown";
            default -> "#displaydropdown";
        };
        page.locator(ROOT).locator(button).click();
        String option = kind.equals("display") ? "[data-display-option='display']" : "[data-filter='" + kind + "']";
        page.locator(ROOT).locator(option + "[data-value='" + value + "']").click();
        // 属性会先改变，列表稍后异步更新；必须等新的列表容器出现。
        page.waitForFunction("""
                args => {
                  const v = document.querySelector(args.selector);
                  const container = v?.querySelector('[data-region="paged-content-container"]');
                  return v?.getAttribute(args.attribute) === args.value && container && container.id !== args.before;
                }
                """, Map.of("selector", VIEW, "attribute", attribute, "value", value, "before", before));
        waitForCurrentPage(page);
    }

    static void waitForCurrentPage(Page page) {
        page.waitForFunction("""
                selector => {
                  const v = document.querySelector(selector);
                  const nav = v?.querySelector('[data-region="paging-bar"]');
                  if (!nav) return false;
                  const number = nav.getAttribute('data-active-page-number');
                  const current = v.querySelector('[data-region="paged-content-page"][data-page="' + number + '"]');
                  return !!current && !current.hidden && !current.classList.contains('hidden') &&
                    !current.classList.contains('d-none') &&
                    !!current.querySelector('[data-region="course-content"], [data-region="empty-message"]');
                }
                """, VIEW);
    }

    static int currentPageNumber(Page page) {
        return Integer.parseInt(page.locator(VIEW).locator("[data-region='paging-bar']")
                .getAttribute("data-active-page-number"));
    }

    static void goToFirstPage(Page page) {
        for (int count = 0; currentPageNumber(page) != 1; count++) {
            if (count >= 1000) throw new IllegalStateException("无法回到课程第一页。");
            int previousNumber = currentPageNumber(page);
            page.locator(VIEW).locator("[data-control='previous'] a").click();
            waitForPageChange(page, previousNumber);
        }
    }

    static void waitForPageChange(Page page, int oldNumber) {
        page.waitForFunction("""
                args => {
                  const nav = document.querySelector(args.selector + ' [data-region="paging-bar"]');
                  return !!nav && nav.getAttribute('data-active-page-number') !== String(args.oldNumber);
                }
                """, Map.of("selector", VIEW, "oldNumber", oldNumber));
        waitForCurrentPage(page);
    }

    static boolean disabled(Locator control) {
        return "true".equals(control.getAttribute("aria-disabled"))
                || Arrays.asList(Objects.requireNonNullElse(control.getAttribute("class"), "").split("\\s+"))
                .contains("disabled");
    }

    static void requireHttpSuccess(Response response) {
        if (response == null || !response.ok()) throw new IllegalStateException(
                "网页访问失败，HTTP 状态：" + (response == null ? "无响应" : response.status()));
    }

    public record Course(long id, String name, String url) {}
    public record Catalog(List<Course> courses, int pagesRead) {}
}
