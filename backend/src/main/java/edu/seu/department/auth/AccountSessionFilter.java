package edu.seu.department.auth;

import java.io.IOException;
import java.util.HashSet;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/** Revalidates existing sessions against account state before endpoint authorization. */
public class AccountSessionFilter extends OncePerRequestFilter {
    private final DatabaseUserDetailsService users;

    public AccountSessionFilter(DatabaseUserDetailsService users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            boolean valid;
            boolean mustChangePassword = false;
            try {
                var current = users.loadUserByUsername(authentication.getName());
                valid = current.isEnabled()
                    && new HashSet<>(current.getAuthorities()).equals(new HashSet<>(authentication.getAuthorities()));
                if (authentication.getPrincipal() instanceof AccountPrincipal previous && current instanceof AccountPrincipal latest) {
                    valid = valid && previous.id() == latest.id() && previous.sessionVersion() == latest.sessionVersion();
                    mustChangePassword = latest.mustChangePassword();
                }
            } catch (UsernameNotFoundException deleted) {
                valid = false;
            }
            if (!valid) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            String path = request.getRequestURI();
            if (mustChangePassword && path.startsWith("/api/v1/")
                    && !path.equals("/api/v1/auth/me") && !path.equals("/api/v1/auth/password")
                    && !path.equals("/api/v1/auth/logout") && !path.equals("/api/v1/auth/csrf")) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "请先修改临时密码");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
