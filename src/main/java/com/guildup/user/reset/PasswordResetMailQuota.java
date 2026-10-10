package com.guildup.user.reset;

import jakarta.persistence.*;
import java.time.*;

/** Singleton DB lock makes the reset-only SMTP budget persistent and atomic across instances/restarts. */
@Entity
@Table(name = "password_reset_mail_quota")
public class PasswordResetMailQuota {
    @Id private Long id = 1L;
    @Column(name = "budget_day", nullable = false) private LocalDate budgetDay;
    @Column(name = "budget_month", nullable = false) private LocalDate budgetMonth;
    @Column(name = "daily_count", nullable = false) private int dailyCount;
    @Column(name = "monthly_count", nullable = false) private int monthlyCount;
    protected PasswordResetMailQuota() {}
    public PasswordResetMailQuota(LocalDate day) { budgetDay = day; budgetMonth = day.withDayOfMonth(1); }
    public boolean reserve(LocalDate day, PasswordResetProperties properties) {
        // Never roll a budget backwards when server clocks disagree.
        if (day.isBefore(budgetDay)) return false;
        if (!day.equals(budgetDay)) { budgetDay = day; dailyCount = 0; }
        if (!day.withDayOfMonth(1).equals(budgetMonth)) { budgetMonth = day.withDayOfMonth(1); monthlyCount = 0; }
        if (dailyCount >= properties.mailDailyBudget() || monthlyCount >= properties.mailMonthlyBudget()) return false;
        dailyCount++; monthlyCount++; return true;
    }
}
