package edu.seu.department.auth;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

public class LoginAttemptFilter extends OncePerRequestFilter {
    private final LoginAttempts attempts;
    public LoginAttemptFilter(LoginAttempts attempts) { this.attempts = attempts; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if ("POST".equals(request.getMethod())
                && (request.getContextPath() + "/api/v1/auth/login").equals(request.getRequestURI())) {
            long retry = attempts.reserve(LoginAttempts.key(request.getParameter("username")));
            if (retry > 0) {
                response.setHeader("Retry-After", Long.toString(retry));
                response.setStatus(429);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
