package cn.example.collector;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** SCHOLAT 网页账号密码登录流程。 */
public final class ScholatLogin {
    static final String LOGIN_URL = "https://www.scholat.com/login.html?source=pc";
    private static final String FORM = "#loginForm";
    private static final String USERNAME_FIELDS = "#j_username:visible";
    private static final String PASSWORD_FIELDS = "#j_password_ext:visible";
    private static final String MY_COURSES = "a[href*='myCourses.html']:visible";
    private static final String HOMEWORK_TAB = "a[onclick*='getCourseHomeworkList']:visible, "
            + "a:visible:text-is('作业动态')";
    static final String HOMEWORK_BLOCKS = "#tabs_4 #homeworkList > div:has(table.homeworkList):visible";

    private ScholatLogin() {}

    static Page loginWithSavedCredential(Page page, String username, char[] password) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("学者网账号不能为空。");
        if (password == null || password.length == 0) throw new IllegalArgumentException("学者网密码不能为空。");

        page.navigate(LOGIN_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        Locator form = page.locator(FORM);
        form.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));

        Locator usernameField = form.locator(USERNAME_FIELDS);
        Locator passwordField = form.locator(PASSWORD_FIELDS);
        if (usernameField.count() != 1 || passwordField.count() != 1) {
            throw new IllegalStateException("学者网登录表单结构与已观察到的页面不一致：账号框="
                    + usernameField.count() + "，密码框=" + passwordField.count() + "。");
        }

        Locator captcha = page.locator("iframe[src*='captcha']:visible, img[src*='captcha']:visible, "
                + "input[name*='captcha' i]:visible, input[id*='captcha' i]:visible");
        if (captcha.count() > 0) {
            throw new IllegalStateException("学者网登录页出现验证码，请在浏览器中手动完成后再继续。");
        }

        Locator submit = form.locator("#login[type='submit']:visible");
        if (submit.count() != 1) {
            throw new IllegalStateException("学者网登录按钮 #login 未能唯一定位：找到 " + submit.count() + " 个。");
        }

        usernameField.fill(username);
        passwordField.fill(new String(password));
        submit.click();

        return waitForLogin(page, 60_000);
    }

    static Page waitForLogin(Page page, double timeoutMs) {
        // 登录可落到不同首页，也可能打开新标签页；只依据已登录功能入口判断。
        try {
            return waitForSitePage(page, candidate -> {
                if (candidate.locator(FORM).isVisible()) return false;
                dismissOptionalVerification(candidate);
                return candidate.locator(MY_COURSES).count() > 0
                    || candidate.locator(HOMEWORK_TAB).count() > 0
                    || candidate.locator(HOMEWORK_BLOCKS).count() > 0;
            }, timeoutMs);
        } catch (TimeoutError e) {
            List<String> errors = page.locator("[role='alert']:visible, .error:visible, .errorMsg:visible")
                    .allTextContents();
            throw new IllegalStateException(errors.isEmpty()
                    ? "学者网登录后未检测到我的课程或作业动态入口；如有验证提示，请先在采集浏览器中处理。"
                        + "当前页面：" + currentPaths(page)
                    : "学者网登录未完成：" + String.join("；", errors), e);
        }
    }

    static Page openHomeworkOverview(Page page) {
        return openHomeworkOverview(page, ignored -> {}, 60_000);
    }

    static Page openHomeworkOverview(Page page, Consumer<String> status, double timeoutMs) {
        Page current = page;
        try {
            current = waitForSitePage(current, candidate -> candidate.locator(HOMEWORK_BLOCKS).count() > 0
                    || candidate.locator(HOMEWORK_TAB).count() > 0
                    || candidate.locator(MY_COURSES).count() > 0, timeoutMs);
            if (current.locator(HOMEWORK_BLOCKS).count() > 0) return current;

            if (current.locator(HOMEWORK_TAB).count() == 0) {
                status.accept("已登录，正在进入我的课程；如有实名认证提示，可选择暂不认证。");
                dismissOptionalVerification(current);
                Locator courses = current.locator(MY_COURSES).first();
                String href = courses.getAttribute("href");
                URI destination = URI.create(current.url()).resolve(href);
                if (!isScholatUrl(destination.toString()) || !destination.getPath().endsWith("/myCourses.html")) {
                    throw new IllegalStateException("我的课程入口未指向学者网课程页。");
                }
                // 使用页面提供的真实链接，在同一登录会话进入，避免 target=_blank 留在旧标签页等待。
                current.navigate(destination.toString(), new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            }

            status.accept("正在打开作业动态；如提示遮挡页面，请关闭提示，程序会自动继续。");
            boolean[] clicked = {false};
            return waitForSitePage(current, candidate -> {
                if (candidate.locator(HOMEWORK_BLOCKS).count() > 0) return true;
                dismissOptionalVerification(candidate);
                if (!clicked[0] && candidate.locator(HOMEWORK_TAB).count() > 0) {
                    try {
                        candidate.locator(HOMEWORK_TAB).first().click(new Locator.ClickOptions().setTimeout(500));
                        clicked[0] = true;
                    } catch (TimeoutError blocked) {
                        // 提示晚于页面入口出现时，允许用户关闭提示或手动进入作业动态后继续。
                    }
                }
                return candidate.locator(HOMEWORK_BLOCKS).count() > 0;
            }, timeoutMs);
        } catch (TimeoutError e) {
            throw new IllegalStateException("未能读取学者网作业动态：未找到已显示的课程作业表格。"
                    + "请检查验证提示、课程权限或页面结构。当前页面：" + currentPaths(current), e);
        }
    }

    private static void dismissOptionalVerification(Page page) {
        Locator postponeVerification = page.getByText("暂不认证", new Page.GetByTextOptions().setExact(true));
        for (int i = 0; i < postponeVerification.count(); i++) {
            if (!postponeVerification.nth(i).isVisible()) continue;
            try {
                postponeVerification.nth(i).click(new Locator.ClickOptions().setTimeout(500));
            } catch (TimeoutError ignored) {
                // 仅操作网站提供的可选关闭按钮；其余验证由用户处理。
            }
            return;
        }
    }

    private static Page waitForSitePage(Page page, Predicate<Page> ready, double timeoutMs) {
        AtomicReference<Page> result = new AtomicReference<>();
        page.waitForCondition(() -> {
            // 新标签页优先；旧主页可能仍然保留我的课程入口。
            List<Page> pages = page.context().pages();
            for (int i = pages.size() - 1; i >= 0; i--) {
                Page candidate = pages.get(i);
                if (!candidate.isClosed() && isScholatUrl(candidate.url()) && ready.test(candidate)) {
                    result.set(candidate);
                    return true;
                }
            }
            return false;
        }, new Page.WaitForConditionOptions().setTimeout(timeoutMs));
        return result.get();
    }

    private static boolean isScholatUrl(String url) {
        URI uri = URI.create(url);
        return "https".equals(uri.getScheme())
                && ("www.scholat.com".equals(uri.getHost()) || "scholat.com".equals(uri.getHost()));
    }

    private static String currentPaths(Page page) {
        return page.context().pages().stream().filter(candidate -> !candidate.isClosed())
                .map(candidate -> URI.create(candidate.url()).getPath()).toList().toString();
    }
}
