package com.huber.searchjob.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Defesa de um app local sem login: só aceita Host de loopback (barra DNS rebinding) e exige Origin/Referer
 * de loopback em qualquer requisição que altere estado (barra CSRF vindo de outras páginas abertas no navegador).
 */
@Component
public class OrigemLocalFilter extends OncePerRequestFilter {
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final Set<String> SEGUROS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!LOOPBACK.contains(req.getServerName().toLowerCase())) {
            res.sendError(403, "host não permitido");
            return;
        }
        if (!SEGUROS.contains(req.getMethod())) {
            String origem = req.getHeader("Origin");
            if (origem == null || origem.isBlank()) origem = req.getHeader("Referer");
            if (!loopback(origem)) {
                res.sendError(403, "origem não permitida");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    static boolean loopback(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            String host = URI.create(url).getHost();
            return host != null && LOOPBACK.contains(host.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
