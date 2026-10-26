package cn.example.service;

import cn.example.collector.LegacyCollectionResultLoader;
import cn.example.collector.MoodleHomework;
import cn.example.collector.ScholatHomework;
import cn.example.domain.AuthSession;
import cn.example.repository.CollectionRunRepository;
import cn.example.repository.SiteCredentialRepository;
import cn.example.web.MoodleHomeworkWebController;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CollectorCoordinator {
    private final ConcurrentHashMap<Long, UserState> states = new ConcurrentHashMap<>();
    private final SiteCredentialService credentials;
    private final CollectionRunRepository collectionRuns;
    private final LegacyCollectionResultLoader legacyResults;

    public CollectorCoordinator(SiteCredentialService credentials, CollectionRunRepository collectionRuns,
                                LegacyCollectionResultLoader legacyResults) {
        this.credentials = credentials;
        this.collectionRuns = collectionRuns;
        this.legacyResults = legacyResults;
    }

    public MoodleHomeworkWebController.ApiSnapshot status(AuthSession user, Long siteCredentialId) {
        UserState state = stateFor(user);
        String siteCode = siteCredentialId == null
                ? SiteCredentialService.MOODLE_SITE_CODE
                : credentials.requireOwnedSummary(user.userId(), siteCredentialId).siteCode();
        CollectionRunRepository.ResultSnapshot latestResult = collectionRuns
                .findLatestResult(user.userId(), siteCredentialId, siteCode).orElse(null);
        if (latestResult == null && SiteCredentialService.MOODLE_SITE_CODE.equals(siteCode)) {
            importLegacyResultIfUnambiguous(user.userId(), siteCredentialId, siteCode);
            latestResult = collectionRuns.findLatestResult(user.userId(), siteCredentialId, siteCode).orElse(null);
        }
        return new MoodleHomeworkWebController.ApiSnapshot(state.running.get(), state.activeCredentialId,
                state.progress.get(), latestResult, collectionRuns.findLatestResults(user.userId()));
    }

    public MoodleHomeworkWebController.ApiSnapshot start(AuthSession user, Long siteCredentialId, String mode) {
        UserState state = stateFor(user);
        if (!state.running.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有一轮采集正在运行。");
        }
        SiteCredentialService.LoginCredential loginCredential = null;
        try {
            SiteCredentialRepository.CredentialSummary account = siteCredentialId == null
                    ? null : credentials.requireOwnedSummary(user.userId(), siteCredentialId);
            if ("auto".equals(mode)) {
                if (siteCredentialId == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "自动登录需要选择一个已绑定的平台账号。");
                }
                if (!SiteCredentialService.MOODLE_SITE_CODE.equals(account.siteCode())
                        && !SiteCredentialService.SCHOLAT_SITE_CODE.equals(account.siteCode())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前版本暂不支持采集该作业平台。");
                }
                loginCredential = credentials.requireForLogin(user.userId(), siteCredentialId);
            } else if ("manual".equals(mode) && account != null
                    && !SiteCredentialService.MOODLE_SITE_CODE.equals(account.siteCode())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "学者网目前只支持已绑定账号自动登录。");
            } else if (!"manual".equals(mode)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择手动登录或自动登录。");
            }

            String siteCode = account == null ? SiteCredentialService.MOODLE_SITE_CODE : account.siteCode();
            long runId = collectionRuns.create(user.userId(), siteCredentialId, siteCode, mode);
            MoodleHomework.Progress initialProgress = new MoodleHomework.Progress("opening_browser", "正在启动 Edge。",
                    0, 0, 0, 0, null, null, List.of());
            state.activeCredentialId = siteCredentialId;
            state.progress.set(initialProgress);
            collectionRuns.updateProgress(user.userId(), runId, initialProgress);

            SiteCredentialService.LoginCredential selected = loginCredential;
            Thread collector = new Thread(() -> runCollector(user, state, selected, siteCode,
                            siteCredentialId, runId),
                    "moodle-collector-user-" + user.userId());
            collector.setDaemon(true);
            collector.start();
            return status(user, siteCredentialId);
        } catch (RuntimeException e) {
            state.running.set(false);
            if (loginCredential != null) loginCredential.close();
            throw e;
        }
    }

    private void runCollector(AuthSession user, UserState state,
                              SiteCredentialService.LoginCredential credential,
                              String siteCode, Long siteCredentialId, long runId) {
        String outputKey = "user-" + user.userId() + (siteCredentialId == null
                ? "-manual" : "-platform-account-" + siteCredentialId);
        Consumer<MoodleHomework.Progress> progressListener = progress -> {
            state.progress.set(progress);
            collectionRuns.updateProgress(user.userId(), runId, progress);
        };
        try {
            if (SiteCredentialService.SCHOLAT_SITE_CODE.equals(siteCode)) {
                ScholatHomework.run(outputKey, credential.username(), credential.password(), progressListener);
            } else if (credential == null) {
                MoodleHomework.run(outputKey, progressListener);
            } else {
                MoodleHomework.run(outputKey, credential.username(), credential.password(), progressListener);
            }
        } catch (Exception e) {
            MoodleHomework.Progress previous = state.progress.get();
            boolean partial = previous.stage().equals("partial");
            String message = partial ? previous.message() : "采集未能完成：" + safeMessage(e);
            String stage = partial ? "partial" : "error";
            MoodleHomework.Progress failed = new MoodleHomework.Progress(stage, message,
                    previous.catalogPagesRead(), previous.totalCourses(), previous.processedCourses(),
                    previous.assignmentCount(), previous.currentCourse(), previous.outputDirectory(),
                    previous.courses());
            state.progress.set(failed);
            try {
                collectionRuns.updateProgress(user.userId(), runId, failed);
            } catch (RuntimeException persistenceError) {
                e.addSuppressed(persistenceError);
            }
            System.err.println("账号 " + user.username() + "：" + message);
        } finally {
            if (credential != null) credential.close();
            state.running.set(false);
        }
    }

    private UserState stateFor(AuthSession user) {
        return states.computeIfAbsent(user.userId(), ignored -> new UserState());
    }

    private void importLegacyResultIfUnambiguous(long ownerUserId, Long siteCredentialId, String siteCode) {
        List<SiteCredentialRepository.CredentialSummary> platformAccounts = credentials.list(ownerUserId).stream()
                .filter(account -> siteCode.equals(account.siteCode())).toList();
        boolean safeAccountMatch = siteCredentialId == null
                ? platformAccounts.isEmpty()
                : platformAccounts.size() == 1 && platformAccounts.get(0).id() == siteCredentialId;
        if (!safeAccountMatch) return;
        try {
            legacyResults.findLatest(Path.of("output", "user-" + ownerUserId))
                    .ifPresent(snapshot -> collectionRuns.importLegacySnapshot(
                            ownerUserId, siteCredentialId, siteCode, snapshot));
        } catch (RuntimeException e) {
            System.err.println("升级前的作业结果暂时无法导入数据库：" + safeMessage(e));
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        return message.length() > 240 ? message.substring(0, 240) + "…" : message;
    }

    private static final class UserState {
        private final AtomicBoolean running = new AtomicBoolean();
        private volatile Long activeCredentialId;
        private final AtomicReference<MoodleHomework.Progress> progress = new AtomicReference<>(
                new MoodleHomework.Progress("idle", "展开“账号与采集”，选择平台账号更新作业。", 0, 0, 0,
                        0, null, null, List.of()));
    }
}
