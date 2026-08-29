package it.alessandropezzali.navguard.integrity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Result of one integrity evaluation: the score, its band, why, and the components behind it. */
public final class IntegrityAssessment {
    public final boolean available;
    public final int score;
    public final IntegrityLevel level;
    public final List<String> reasons;
    public final SubScore signalQuality;
    public final SubScore satelliteQuality;
    public final SubScore positionConsistency;
    public final SubScore motionConsistency;
    public final SubScore rawAvailability;
    public final MotionState motionState;
    public final List<AnomalyEvent> newEvents;

    public IntegrityAssessment(boolean available, int score, IntegrityLevel level, List<String> reasons,
                               SubScore signalQuality, SubScore satelliteQuality,
                               SubScore positionConsistency, SubScore motionConsistency,
                               SubScore rawAvailability, MotionState motionState,
                               List<AnomalyEvent> newEvents) {
        this.available = available;
        this.score = score;
        this.level = level;
        this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
        this.signalQuality = signalQuality;
        this.satelliteQuality = satelliteQuality;
        this.positionConsistency = positionConsistency;
        this.motionConsistency = motionConsistency;
        this.rawAvailability = rawAvailability;
        this.motionState = motionState;
        this.newEvents = Collections.unmodifiableList(new ArrayList<>(newEvents));
    }

    public List<SubScore> subScores() {
        List<SubScore> out = new ArrayList<>();
        out.add(signalQuality);
        out.add(satelliteQuality);
        out.add(positionConsistency);
        out.add(motionConsistency);
        out.add(rawAvailability);
        return out;
    }
}
