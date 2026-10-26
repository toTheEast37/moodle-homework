package cn.example.web;

import cn.example.domain.AuthSession;
import cn.example.repository.SiteCredentialRepository;
import cn.example.security.SameOriginGuard;
import cn.example.service.SiteCredentialService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/credentials")
public class SiteCredentialController {
    private final SiteCredentialService credentials;
    private final SameOriginGuard originGuard;

    public SiteCredentialController(SiteCredentialService credentials, SameOriginGuard originGuard) {
        this.credentials = credentials;
        this.originGuard = originGuard;
    }

    @GetMapping
    public List<SiteCredentialRepository.CredentialSummary> list(HttpServletRequest request) {
        return credentials.list(requireUser(request).userId());
    }

    @PostMapping
    public SiteCredentialRepository.CredentialSummary save(@RequestBody SiteCredentialService.SaveRequest body,
                                                            HttpServletRequest request) {
        originGuard.requireSameOrigin(request);
        return credentials.save(requireUser(request).userId(), body);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") long id, HttpServletRequest request) {
        originGuard.requireSameOrigin(request);
        credentials.delete(requireUser(request).userId(), id);
    }

    private static AuthSession requireUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(AuthSession.class.getName());
        if (value instanceof AuthSession user) return user;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录本机账号。");
    }
}
