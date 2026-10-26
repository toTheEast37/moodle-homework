package cn.example.security;

import java.net.URI;
import java.util.Objects;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.FORBIDDEN;

@Component
public class SameOriginGuard {
    public void requireSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) throw new ResponseStatusException(FORBIDDEN);
        try {
            URI parsed = URI.create(origin);
            int originPort = effectivePort(parsed.getScheme(), parsed.getPort());
            int requestPort = effectivePort(request.getScheme(), request.getServerPort());
            boolean same = Objects.equals(parsed.getScheme(), request.getScheme())
                    && parsed.getHost() != null
                    && parsed.getHost().equalsIgnoreCase(request.getServerName())
                    && originPort == requestPort;
            if (!same) throw new ResponseStatusException(FORBIDDEN);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(FORBIDDEN);
        }
    }

    private static int effectivePort(String scheme, int port) {
        if (port != -1) return port;
        if ("https".equalsIgnoreCase(scheme)) return 443;
        if ("http".equalsIgnoreCase(scheme)) return 80;
        return -1;
    }
}
