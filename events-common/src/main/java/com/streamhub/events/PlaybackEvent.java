package com.streamhub.events;

import java.time.Instant;

/**
 * One thing that happened during playback, published to {@link #TOPIC}
 * keyed by userId so one user's events stay in order.
 *
 * @param eventId          unique; consumers use it to recognise a redelivery
 * @param watchedSeconds   seconds actually watched since the previous event
 *                         of this session (computed by the playback service,
 *                         clamped to 0..60)
 * @param occurredAt       when it happened; consumers keep only the newest
 *                         progress, so late or repeated events can't move it back
 */
public record PlaybackEvent(String eventId, Type type, long userId, long titleId, String sessionId,
                            int positionSeconds, int durationSeconds, int watchedSeconds, Instant occurredAt) {

    public static final String TOPIC = "playback-events";
    public static final String DEAD_LETTERS = TOPIC + ".DLT";

    public enum Type { START, HEARTBEAT, STOP }
}
