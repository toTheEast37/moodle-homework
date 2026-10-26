package cn.example.web;

import cn.example.domain.AppUser;
import cn.example.domain.AuthSession;
import cn.example.repository.AppUserRepository;
import cn.example.security.PasswordHasher;
import cn.example.security.SameOriginGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AccountController {
    private final AppUserRepository users;
    private final PasswordHasher passwordHasher;
    private final SameOriginGuard originGuard;

    public AccountController(AppUserRepository users, PasswordHasher passwordHasher, SameOriginGuard originGuard) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.originGuard = originGuard;
    }

    @GetMapping("/session")
    public SessionResponse session(HttpServletRequest request) {
        AuthSession user = authenticatedUser(request.getSession(false));
        return new SessionResponse(user != null, user == null ? null : user.username());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        originGuard.requireSameOrigin(request);
        if (body == null || body.username() == null || body.password() == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("请输入账号和密码。"));
        }
        String username = body.username().trim();
        AppUser user = users.findEnabledByUsername(username).orElse(null);
        if (user == null || !passwordHasher.matches(body.password(), user.passwordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("账号或密码不正确。"));
        }
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(AuthSession.class.getName(), new AuthSession(user.id(), user.username()));
        System.out.println("页面账号登录成功：" + user.username());
        return ResponseEntity.ok(new SessionResponse(true, user.username()));
    }

    @PostMapping("/logout")
    public SessionResponse logout(HttpServletRequest request) {
        originGuard.requireSameOrigin(request);
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        return new SessionResponse(false, null);
    }

    private static AuthSession authenticatedUser(HttpSession session) {
        if (session == null) return null;
        Object value = session.getAttribute(AuthSession.class.getName());
        return value instanceof AuthSession user ? user : null;
    }

    public record LoginRequest(String username, String password) {}
    public record SessionResponse(boolean authenticated, String username) {}
    public record ErrorResponse(String message) {}
}
