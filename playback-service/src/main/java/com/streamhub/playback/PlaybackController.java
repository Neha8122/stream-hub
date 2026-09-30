package com.streamhub.playback;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** X-User-Id is set by the gateway after checking the token. */
@RestController
@RequestMapping("/playback")
public class PlaybackController {

    public record StartRequest(@Positive long titleId, @Min(0) int positionSeconds, @Positive int durationSeconds) { }
    public record MoveRequest(@NotBlank String sessionId, @Min(0) int positionSeconds) { }

    private final PlaybackService playback;

    public PlaybackController(PlaybackService playback) {
        this.playback = playback;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> start(@RequestHeader("X-User-Id") long userId,
                                                     @Valid @RequestBody StartRequest r) {
        return respond(playback.start(userId, r.titleId(), r.positionSeconds(), r.durationSeconds()), HttpStatus.CREATED);
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, String>> heartbeat(@RequestHeader("X-User-Id") long userId,
                                                         @Valid @RequestBody MoveRequest r) {
        return respond(playback.heartbeat(userId, r.sessionId(), r.positionSeconds()), HttpStatus.OK);
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stop(@RequestHeader("X-User-Id") long userId,
                                                    @Valid @RequestBody MoveRequest r) {
        return respond(playback.stop(userId, r.sessionId(), r.positionSeconds()), HttpStatus.OK);
    }

    private static ResponseEntity<Map<String, String>> respond(PlaybackService.Outcome o, HttpStatus ok) {
        return switch (o) {
            case PlaybackService.Outcome.Ok k -> ResponseEntity.status(ok).body(Map.of("sessionId", k.sessionId()));
            case PlaybackService.Outcome.NotFound n -> ResponseEntity.notFound().build();
            case PlaybackService.Outcome.Forbidden f -> ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        };
    }
}
