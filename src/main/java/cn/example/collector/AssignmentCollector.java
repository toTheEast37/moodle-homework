package cn.example.collector;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/** 从整门课程的“活动 → 作业”汇总表读取数据，避免遗漏其他章节。 */
public final class AssignmentCollector {
    static final ZoneId TIME_ZONE = ZoneId.of("Asia/Shanghai");

    private AssignmentCollector() {}

    public static List<Assignment> collect(Page page, CourseCatalog.Course course) {
        Response response = page.navigate(sourceUrl(course));
        CourseCatalog.requireHttpSuccess(response);
        page.locator("body#page-course-overview #course-overview-page.stateready").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED));
        // 先验证确实进入目标课程的活动页，再允许返回“没有作业”。
        URI current = URI.create(page.url());
        var id = CourseCatalog.ID.matcher(Objects.requireNonNullElse(current.getRawQuery(), ""));
        if (!"moodle.scnu.edu.cn".equals(current.getHost()) || !"/course/overview.php".equals(current.getPath())
                || !id.find() || Long.parseLong(id.group(1)) != course.id()) {
            throw new IllegalStateException("页面没有停留在目标课程的活动页，可能登录已失效或无权限。");
        }
        if (page.locator("#assign_overview_collapsible").count() == 0) return List.of();
        Locator group = page.locator("#assign_overview_collapsible");
        Locator toggle = group.locator("[aria-controls='assign_overview']");
        if (!"true".equals(toggle.getAttribute("aria-expanded"))) toggle.click();
        // 展开分组会触发异步加载，不能把暂时没有行当作没有作业。
        group.locator("#assign_overview table").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED));
        group.locator("[data-region='loading-icon-container']").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
        if (group.locator("[data-region='paging-bar']").count() != 0) {
            throw new IllegalStateException("作业汇总表新增了分页结构，需要适配后才能保证完整采集。");
        }
        return readAssignments(group);
    }

    static List<Assignment> readAssignments(Locator group) {
        Map<Long, Assignment> unique = new LinkedHashMap<>();
        if (group.locator("table [data-mdl-overview-column='name']").count() != 1
                || (group.locator("tbody tr").count() > 0
                && group.locator("tbody tr[data-mdl-overview-cmid]").count() == 0)) {
            throw new IllegalStateException("作业表格结构变化，不能将解析失败记录为空作业。");
        }
        for (Locator row : group.locator("tbody tr[data-mdl-overview-cmid]").all()) {
            long id = Long.parseLong(row.getAttribute("data-mdl-overview-cmid"));
            Locator name = row.locator("[data-mdl-overview-item='name']");
            String title = name.getAttribute("data-mdl-overview-value");
            String href = name.locator("a.activityname").getAttribute("href");
            if (id <= 0 || title == null || title.isBlank() || href == null || href.isBlank()) {
                throw new IllegalStateException("作业行缺少名称、编号或详情链接。");
            }
            URI url = URI.create(CourseCatalog.SITE).resolve(href);
            var linkId = CourseCatalog.ID.matcher(Objects.requireNonNullElse(url.getRawQuery(), ""));
            if (!"moodle.scnu.edu.cn".equals(url.getHost()) || !"/mod/assign/view.php".equals(url.getPath())
                    || !linkId.find() || Long.parseLong(linkId.group(1)) != id) {
                throw new IllegalStateException("作业编号和详情链接不一致。");
            }
            Locator due = row.locator("[data-mdl-overview-item='duedate']");
            String raw = null;
            String deadline = null;
            if (due.count() == 1) {
                raw = due.innerText().strip();
                if (raw.isEmpty() || raw.equals("-")) raw = null;
                String seconds = due.getAttribute("data-mdl-overview-value");
                if (seconds != null && !seconds.isBlank() && !seconds.equals("0")) {
                    // 显示文字可能省略年份；读取服务端给出的秒级时间戳，不推测年份。
                    long epoch = Long.parseLong(seconds);
                    if (epoch < 0) throw new IllegalStateException("截止时间戳不合法。");
                    deadline = Instant.ofEpochSecond(epoch).atZone(TIME_ZONE).toOffsetDateTime().toString();
                }
            }
            unique.putIfAbsent(id, new Assignment(id, title.strip(),
                    CourseCatalog.SITE + "/mod/assign/view.php?id=" + id, raw, deadline));
        }
        return List.copyOf(unique.values());
    }

    static String sourceUrl(CourseCatalog.Course course) {
        return CourseCatalog.SITE + "/course/overview.php?id=" + course.id() + "&expand%5B%5D=assign";
    }

    public record Assignment(long id, String title, String url, String deadlineRaw, String deadline) {}
}
