package edu.seu.department.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import java.time.Clock;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean
    LoginAttempts loginAttempts() { return new LoginAttempts(Clock.systemUTC()); }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, DatabaseUserDetailsService users, LoginAttempts attempts, org.springframework.jdbc.core.JdbcTemplate jdbc) throws Exception {
        return http
            .addFilterBefore(new LoginAttemptFilter(attempts), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new AccountSessionFilter(users), AuthorizationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/workspace", "/admin", "/admin/**", "/assets/**", "/favicon.ico",
                    "/api/v1/health", "/api/v1/auth/csrf").permitAll()
                .requestMatchers("/api/v1/auth/**").authenticated()
                .anyRequest().hasAnyRole("ADMIN","MINISTER","LEADER","MEMBER"))
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) ->
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                .accessDeniedHandler((request, response, error) ->
                    response.sendError(HttpServletResponse.SC_FORBIDDEN)))
            .formLogin(login -> login
                .loginProcessingUrl("/api/v1/auth/login")
                .successHandler((request, response, authentication) -> {
                    attempts.succeeded(LoginAttempts.key(request.getParameter("username")));
                    jdbc.update("UPDATE app_user SET last_login_at=CURRENT_TIMESTAMP WHERE username=?",authentication.getName());
                    jdbc.update("INSERT INTO audit_event(actor,action,target) VALUES (?,'LOGIN_SUCCEEDED',?)",authentication.getName(),authentication.getName());
                    response.setStatus(204);
                })
                .failureHandler((request, response, exception) -> {
                    String name=LoginAttempts.key(request.getParameter("username"));
                    jdbc.update("INSERT INTO audit_event(actor,action,target) VALUES (?,'LOGIN_FAILED',?)",name,name);
                    response.sendError(401);
                })
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/api/v1/auth/logout")
                .logoutSuccessHandler((request, response, authentication) -> {
                    if(authentication!=null) jdbc.update("INSERT INTO audit_event(actor,action,target) VALUES (?,'LOGOUT',?)",authentication.getName(),authentication.getName());
                    response.setStatus(204);
                }))
            .build();
    }
}
