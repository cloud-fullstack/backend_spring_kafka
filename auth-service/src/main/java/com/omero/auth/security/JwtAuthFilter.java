package com.omero.auth.security;

import com.omero.auth.service.PlanAccessService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtTokenProvider jwt;
    private final PlanAccessService planAccess;

    public JwtAuthFilter(JwtTokenProvider jwt, PlanAccessService planAccess) {
        this.jwt = jwt;
        this.planAccess = planAccess;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (h != null && h.startsWith("Bearer ")) {
            jwt.parseUserId(h.substring(7))
                    .filter(planAccess::isActiveUser)
                    .ifPresent(uid -> SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(uid, null, List.of())));
        }
        chain.doFilter(req, res);
    }
}
