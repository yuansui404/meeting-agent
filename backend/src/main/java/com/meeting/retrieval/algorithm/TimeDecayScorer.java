package com.meeting.retrieval.algorithm;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public class TimeDecayScorer {

    public record TimeDecayConfig(
            boolean enabled, int recentDays,
            double recentWeight, double normalWeight,
            double oldWeight, double archiveWeight
    ) {}

    public static double apply(double rrfScore, LocalDate meetingDate, TimeDecayConfig config) {
        if (!config.enabled() || meetingDate == null) return rrfScore;

        long daysOld = ChronoUnit.DAYS.between(meetingDate, LocalDate.now());
        double factor;
        if (daysOld <= config.recentDays()) factor = config.recentWeight();
        else if (daysOld <= 90)               factor = config.normalWeight();
        else if (daysOld <= 365)              factor = config.oldWeight();
        else                                  factor = config.archiveWeight();

        return rrfScore * factor;
    }
}
