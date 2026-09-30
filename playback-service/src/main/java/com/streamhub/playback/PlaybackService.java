package com.streamhub.playback;

import com.streamhub.events.PlaybackEvent;
import com.streamhub.events.PlaybackEvent.Type;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class PlaybackService {

    /** What the controller turns into an HTTP status. */
    public sealed interface Outcome {
        record Ok(String sessionId) implements Outcome { }
        record NotFound() implements Outcome { }
        record Forbidden() implements Outcome { }
    }

    private final SessionStore sessions;
    private final KafkaTemplate<String, PlaybackEvent> kafka;
    private final Duration ttl;
    private final int maxWatched;
    private final Clock clock;

    public PlaybackService(SessionStore sessions, KafkaTemplate<String, PlaybackEvent> kafka,
                           @Value("${playback.session-ttl}") Duration ttl,
                           @Value("${playback.max-watched-per-event}") Duration maxWatched) {
        this.sessions = sessions;
        this.kafka = kafka;
        this.ttl = ttl;
        this.maxWatched = (int) maxWatched.toSeconds();
        this.clock = Clock.systemUTC();
    }

    public Outcome start(long userId, long titleId, int position, int duration) {
        SessionStore.Session s = sessions.open(userId, titleId, position, duration, ttl);
        publish(Type.START, s, 0);
        return new Outcome.Ok(s.id());
    }

    public Outcome heartbeat(long userId, String sessionId, int position) {
        return move(userId, sessionId, position, Type.HEARTBEAT);
    }

    public Outcome stop(long userId, String sessionId, int position) {
        Outcome o = move(userId, sessionId, position, Type.STOP);
        if (o instanceof Outcome.Ok) {
            sessions.close(sessionId);
        }
        return o;
    }

    private Outcome move(long userId, String sessionId, int position, Type type) {
        return switch (sessions.move(sessionId, userId, position, ttl)) {
            case SessionStore.Move.NotFound n -> new Outcome.NotFound();
            case SessionStore.Move.NotYours n -> new Outcome.Forbidden();
            case SessionStore.Move.Moved m -> {
                // A retried heartbeat repeats the same position: 0 s. Seeking
                // backwards: 0 s. A jump forward counts at most maxWatched.
                int watched = Math.max(0, Math.min(maxWatched, position - m.previousPosition()));
                publish(type, m.session(), watched);
                yield new Outcome.Ok(sessionId);
            }
        };
    }

    /** Waits for the broker to confirm (acks=all), so a 2xx means the event is stored. */
    private void publish(Type type, SessionStore.Session s, int watched) {
        PlaybackEvent e = new PlaybackEvent(UUID.randomUUID().toString(), type, s.userId(), s.titleId(), s.id(),
                s.positionSeconds(), s.durationSeconds(), watched, clock.instant());
        try {
            kafka.send(PlaybackEvent.TOPIC, Long.toString(s.userId()), e).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("could not publish playback event", ex);
        }
    }
}
