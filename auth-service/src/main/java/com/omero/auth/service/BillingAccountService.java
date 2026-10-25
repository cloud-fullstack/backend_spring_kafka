package com.omero.auth.service;

import com.omero.auth.entity.BillingAccount;
import com.omero.auth.entity.User;
import com.omero.auth.repository.BillingAccountRepository;
import com.omero.auth.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BillingAccountService {

    private final BillingAccountRepository accounts;
    private final UserRepository users;

    public BillingAccountService(BillingAccountRepository accounts, UserRepository users) {
        this.accounts = accounts;
        this.users = users;
    }

    public User requireUser(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> new SecurityException("Invalid credentials"));
        if (!user.isActive() || user.getTenantId() == null) {
            throw new SecurityException("Invalid credentials");
        }
        return user;
    }

    @Transactional
    public BillingAccount ensure(UUID tenantId) {
        return accounts.findById(tenantId).orElseGet(() -> {
            BillingAccount a = new BillingAccount();
            a.setTenantId(tenantId);
            a.setSpendMonth(YearMonth.now(ZoneOffset.UTC).toString());
            return accounts.save(a);
        });
    }

    @Transactional
    public Map<String, Object> snapshot(UUID userId) {
        User user = requireUser(userId);
        BillingAccount a = ensure(user.getTenantId());
        rollMonth(a);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", user.getPlanId());
        m.put("balanceCents", a.getBalanceCents());
        m.put("spendMonthCents", a.getSpendMonthCents());
        m.put("currency", "eur");
        m.put("exhausted", a.getBalanceCents() <= 0 && !"demo".equalsIgnoreCase(user.getPlanId()));
        return m;
    }

    @Transactional
    public void addCredits(UUID tenantId, long cents) {
        ensure(tenantId);
        accounts.addBalance(tenantId, cents);
    }

    @Transactional
    public void setStripeCustomerIfAbsent(UUID tenantId, String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return;
        }
        ensure(tenantId);
        accounts.setStripeCustomerIfAbsent(tenantId, customerId);
    }

    private void rollMonth(BillingAccount a) {
        String now = YearMonth.now(ZoneOffset.UTC).toString();
        if (now.equals(a.getSpendMonth())) {
            return;
        }
        a.setSpendMonth(now);
        a.setSpendMonthCents(0);
    }
}
