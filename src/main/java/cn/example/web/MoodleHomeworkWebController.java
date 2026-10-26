package cn.example.web;

import cn.example.collector.MoodleHomework;
import cn.example.domain.AuthSession;
import cn.example.repository.CollectionRunRepository;
import cn.example.security.SameOriginGuard;
import cn.example.service.CollectorCoordinator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class MoodleHomeworkWebController {
    private final CollectorCoordinator collector;
    private final SameOriginGuard originGuard;

    public MoodleHomeworkWebController(CollectorCoordinator collector, SameOriginGuard originGuard) {
        this.collector = collector;
        this.originGuard = originGuard;
    }

    @GetMapping("/status")
    public ApiSnapshot status(@RequestParam(name = "credentialId", required = false) Long credentialId,
                              HttpServletRequest request) {
        return collector.status(requireUser(request), credentialId);
    }

    @PostMapping("/start")
    public ApiSnapshot start(@RequestParam(name = "credentialId", required = false) Long credentialId,
                             @RequestParam(name = "mode", defaultValue = "auto") String mode,
                             HttpServletRequest request) {
        originGuard.requireSameOrigin(request);
        if ("manual".equals(mode)) {
            return collector.start(requireUser(request), credentialId, mode);
        }
        if ("auto".equals(mode) && credentialId != null) {
            return collector.start(requireUser(request), credentialId, mode);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择手动登录或一个已绑定账号自动登录。");
    }

    private static AuthSession requireUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(AuthSession.class.getName());
        if (value instanceof AuthSession user) return user;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录本机账号。");
    }

    public record ApiSnapshot(boolean running, Long activeCredentialId, MoodleHomework.Progress progress,
                              CollectionRunRepository.ResultSnapshot latestResult,
                              java.util.List<CollectionRunRepository.ResultSnapshot> results) {}
}
