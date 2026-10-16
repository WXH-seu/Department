package edu.seu.department.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded, process-local attempt budget for the single-server deployment. */
public class LoginAttempts {
    private static final int LIMIT = 5;
    private static final int MAX_KEYS = 10_000;
    private static final long WINDOW = Duration.ofMinutes(15).toMillis();
    private final Clock clock;
    private final Map<String, Budget> budgets = new HashMap<>();

    public LoginAttempts(Clock clock) { this.clock = clock; }

    public static String key(String username) {
        if (username == null || username.length() > 64) return "<invalid>";
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /** Reserves before password verification, so concurrent attempts share the same budget. */
    public synchronized long reserve(String key) {
        long now = clock.millis();
        budgets.entrySet().removeIf(entry -> entry.getValue().expires <= now);
        Budget budget = budgets.get(key);
        if (budget == null) {
            if (budgets.size() >= MAX_KEYS) {
                long earliest = budgets.values().stream().mapToLong(value -> value.expires).min().orElse(now + WINDOW);
                return Math.max(1, (earliest - now + 999) / 1000);
            }
            budget = new Budget(now + WINDOW);
            budgets.put(key, budget);
        }
        if (budget.count >= LIMIT) return Math.max(1, (budget.expires - now + 999) / 1000);
        budget.count++;
        return 0;
    }

    public synchronized void succeeded(String key) { budgets.remove(key); }

    private static class Budget {
        final long expires;
        int count;
        Budget(long expires) { this.expires = expires; }
    }
}
