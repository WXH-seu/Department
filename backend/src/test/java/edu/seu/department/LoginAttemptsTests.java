package edu.seu.department;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.time.Clock;
import edu.seu.department.auth.LoginAttempts;
import org.junit.jupiter.api.Test;

class LoginAttemptsTests {
    @Test
    void limitsAndRecoversWithoutExtendingWindow() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(0L);
        var attempts = new LoginAttempts(clock);
        for (int i = 0; i < 5; i++) assertThat(attempts.reserve("member")).isZero();
        assertThat(attempts.reserve("member")).isEqualTo(900);
        when(clock.millis()).thenReturn(899_000L);
        assertThat(attempts.reserve("member")).isEqualTo(1);
        when(clock.millis()).thenReturn(900_000L);
        assertThat(attempts.reserve("member")).isZero();
    }

    @Test
    void successResetsBudgetAndAccountsAreIndependent() {
        var attempts = new LoginAttempts(Clock.systemUTC());
        for (int i = 0; i < 5; i++) attempts.reserve("member");
        assertThat(attempts.reserve("member")).isPositive();
        assertThat(attempts.reserve("other")).isZero();
        attempts.succeeded("member");
        assertThat(attempts.reserve("member")).isZero();
        assertThat(LoginAttempts.key(" MEMBER ")).isEqualTo(LoginAttempts.key("member"));
    }
}
