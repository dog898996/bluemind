package com.bluemind.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class RuleEngineTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long HOUR_MS = 3_600_000L;

    @Test
    public void dailyLimitResetsAtLocalMidnight() {
        long midnight = at("2026-11-21T15:00:00Z");
        RuleEngine.Count history = history(midnight - 1);
        assertTrue(RuleEngine.isBlocked(1, Collections.emptyList(), midnight - 1, SEOUL, history));
        assertFalse(RuleEngine.isBlocked(1, Collections.emptyList(), midnight, SEOUL, history));
        assertTrue(RuleEngine.isBlocked(1, Collections.emptyList(), midnight, SEOUL, history(midnight)));
    }

    @Test
    public void dayBoundariesUseCalendarDatesAcrossDaylightSaving() {
        ZoneId newYork = ZoneId.of("America/New_York");
        long springNextDay = at("2026-03-09T04:00:00Z");
        long autumnNextDay = at("2026-11-02T05:00:00Z");
        assertTrue(RuleEngine.isBlocked(1, Collections.emptyList(), springNextDay - 1, newYork,
                history(springNextDay - 23 * HOUR_MS)));
        assertFalse(RuleEngine.isBlocked(1, Collections.emptyList(), springNextDay, newYork,
                history(springNextDay - 1)));
        assertTrue(RuleEngine.isBlocked(1, Collections.emptyList(), autumnNextDay - 1, newYork,
                history(autumnNextDay - 25 * HOUR_MS)));
        assertFalse(RuleEngine.isBlocked(1, Collections.emptyList(), autumnNextDay, newYork,
                history(autumnNextDay - 1)));
    }

    @Test
    public void rollingLimitSurvivesMidnightAndExpiresExactlyOnBoundary() {
        long midnight = at("2026-11-21T15:00:00Z");
        long previousVisit = midnight - 12 * 60_000L;
        Rule rule = new Rule(1, 1, 1, Rule.Mode.LIMIT);
        List<Rule> rules = Collections.singletonList(rule);
        RuleEngine.Count history = history(previousVisit);
        assertTrue(RuleEngine.isBlocked(null, rules, midnight, SEOUL, history));
        assertTrue(RuleEngine.isBlocked(null, rules, previousVisit + HOUR_MS - 1, SEOUL, history));
        assertFalse(RuleEngine.isBlocked(null, rules, previousVisit + HOUR_MS, SEOUL, history));
    }

    @Test
    public void simultaneousOverlappingLimitsEachEnforceTheirOwnThreshold() {
        long now = at("2026-11-22T05:00:00Z");
        Rule hourly = new Rule(1, 1, 2, Rule.Mode.LIMIT);
        Rule threeHourly = new Rule(2, 3, 3, Rule.Mode.LIMIT);
        List<Rule> rules = Arrays.asList(hourly, threeHourly);
        assertTrue(RuleEngine.isBlocked(null, rules, now, SEOUL, history(now - 1_000L, now)));
        assertTrue(RuleEngine.isBlocked(null, rules, now, SEOUL,
                history(now - 2 * HOUR_MS, now - HOUR_MS, now)));
        assertFalse(RuleEngine.isBlocked(null, rules, now, SEOUL,
                history(now - HOUR_MS, now)));
    }

    @Test
    public void remindersCrossThresholdOnceAndRearmOnExpiry() {
        Rule remind = new Rule(1, 1, 2, Rule.Mode.REMIND);
        List<Rule> rules = Collections.singletonList(remind);
        long first = at("2026-11-22T05:00:00Z");
        long second = first + 1_000;
        long third = first + 2_000;
        assertEquals(Collections.emptyList(), RuleEngine.remindersForNextVisit(rules, first, history()));
        assertEquals(rules, RuleEngine.remindersForNextVisit(rules, second, history(first)));
        assertEquals(Collections.emptyList(),
                RuleEngine.remindersForNextVisit(rules, third, history(first, second)));
        assertEquals(rules, RuleEngine.remindersForNextVisit(rules, first + HOUR_MS,
                history(first, second)));
        assertFalse(RuleEngine.isBlocked(null, rules, third, SEOUL, history(first, second)));
    }

    @Test
    public void countsAlreadyPersistedAtTheSameMillisecond() {
        long now = at("2026-11-22T05:00:00Z");
        Rule remind = new Rule(1, 1, 1, Rule.Mode.REMIND);
        List<Rule> rules = Collections.singletonList(remind);
        assertEquals(rules, RuleEngine.remindersForNextVisit(rules, now, history()));
        assertEquals(Collections.emptyList(), RuleEngine.remindersForNextVisit(rules, now, history(now)));
    }

    @Test
    public void unlimitedModeWithoutLimitBlocksNeverReadsCounts() {
        Rule remind = new Rule(1, 1, 5, Rule.Mode.REMIND);
        assertFalse(RuleEngine.isBlocked(null, Collections.singletonList(remind), 0, SEOUL,
                (from, to, max) -> {
                    throw new AssertionError("Reminder rules do not take part in blocking.");
                }));
    }

    @Test
    public void allRemindersCrossingOnOneAdmissionAreReturned() {
        long now = at("2026-11-22T05:00:00Z");
        List<Rule> rules = Arrays.asList(new Rule(1, 1, 2, Rule.Mode.REMIND),
                new Rule(2, 3, 3, Rule.Mode.REMIND));
        assertEquals(rules, RuleEngine.remindersForNextVisit(rules, now,
                history(now - 2 * HOUR_MS, now - 1_000)));
    }

    private static long at(String time) {
        return Instant.parse(time).toEpochMilli();
    }

    private static RuleEngine.Count history(long... timestamps) {
        List<Long> visits = new ArrayList<>();
        for (long timestamp : timestamps) {
            visits.add(timestamp);
        }
        return (from, to, maximum) -> (int) visits.stream()
                .filter(visit -> visit >= from && visit < to)
                .limit(maximum)
                .count();
    }
}
