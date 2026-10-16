package edu.seu.department.auth;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

public class AccountPrincipal extends User {
    private final long id;
    private final long sessionVersion;
    public AccountPrincipal(long id, long sessionVersion, String username, String password, boolean enabled,
            Collection<? extends GrantedAuthority> authorities) {
        super(username,password,enabled,true,true,true,authorities);
        this.id=id; this.sessionVersion=sessionVersion;
    }
    public long id() { return id; }
    public long sessionVersion() { return sessionVersion; }
}
