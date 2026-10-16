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
            try {
                var current = users.loadUserByUsername(authentication.getName());
                valid = current.isEnabled()
                    && new HashSet<>(current.getAuthorities()).equals(new HashSet<>(authentication.getAuthorities()));
                if (authentication.getPrincipal() instanceof AccountPrincipal previous && current instanceof AccountPrincipal latest) {
                    valid = valid && previous.id() == latest.id() && previous.sessionVersion() == latest.sessionVersion();
                }
            } catch (UsernameNotFoundException deleted) {
                valid = false;
            }
            if (!valid) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
