package com.bluemind.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

final class RuleEngine {
    private static final long HOUR_MS = 3_600_000L;

    @FunctionalInterface
    interface Count {
        int upTo(long fromInclusive, long toExclusive, int maximum);
    }

    private RuleEngine() {
    }

    static boolean isBlocked(Integer dailyLimit, List<Rule> rules, long now, ZoneId zone,
                             Count visits) {
        if (dailyLimit != null) {
            LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            long start = today.atStartOfDay(zone).toInstant().toEpochMilli();
            long end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            if (visits.upTo(start, end, dailyLimit) >= dailyLimit) {
                return true;
            }
        }
        for (Rule rule : rules) {
            if (rule.mode == Rule.Mode.LIMIT && rollingCount(rule, now, visits) >= rule.count) {
                return true;
            }
        }
        return false;
    }

    static List<Rule> remindersForNextVisit(List<Rule> rules, long now, Count visits) {
        List<Rule> reminders = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.mode == Rule.Mode.REMIND && rollingCount(rule, now, visits) == rule.count - 1) {
                reminders.add(rule);
            }
        }
        return reminders;
    }

    private static int rollingCount(Rule rule, long now, Count visits) {
        // A visit expires precisely when it is a full window old.
        return visits.upTo(now - rule.hours * HOUR_MS + 1, now + 1, rule.count);
    }
}
