package com.med.qa.controller;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedChatStreamProperties;
import com.med.qa.controller.dto.ChatStreamRequest;
import com.med.qa.common.ratelimit.annotation.RateLimit;
import com.med.qa.security.annotation.RequireDept;
import com.med.qa.service.ChatStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * Server-Sent Events endpoint for streaming AI consultation.
 *
 * <p>A {@code POST} opens a {@code text/event-stream} and pushes the assistant's answer chunk by
 * chunk. A heartbeat comment is pushed on a fixed cadence so proxies and the browser keep a slow or
 * quiet connection open; the connection is closed cleanly when the model finishes, errors, or the
 * client disappears. On client disconnect the underlying reactor subscription is disposed and the
 * heartbeat scheduler is shut down, so a dropped connection never leaves a dangling model call or a
 * stuck timer behind.</p>
 *
 * <p>The actual model call and RAG retrieval are delegated to {@link ChatStreamService}; this
 * controller owns only transport concerns (SSE framing, heartbeat, lifecycle cleanup).</p>
 *
 * <p>{@link RequireDept} guards the endpoint against anonymous use: the department id lives inside the
 * JSON body, which the interceptor must not consume, so the declaration uses {@code required = false} —
 * authentication is still mandatory, and the body scope keeps driving the RAG tenant/department/patient
 * metadata filters.</p>
 */
@RestController
@RequestMapping("/api/chat")
@EnableConfigurationProperties(MedChatStreamProperties.class)
@Tag(name = "Consultation Chat", description = "Streaming AI consultation over Server-Sent Events. "
        + "Requires an API key; the patient/tenant/dept scope from the request body drives the RAG "
        + "tenant/department/patient metadata filters.")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatStreamService chatStreamService;

    private final MedChatStreamProperties streamProperties;

    /**
     * Creates the streaming consultation controller.
     *
     * @param chatStreamService the streaming orchestration service, must not be {@code null}
     * @param streamProperties  SSE tuning (heartbeat / timeout), must not be {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public ChatController(ChatStreamService chatStreamService, MedChatStreamProperties streamProperties) {
        org.springframework.util.Assert.notNull(chatStreamService, "chatStreamService must not be null");
        org.springframework.util.Assert.notNull(streamProperties, "streamProperties must not be null");
        this.chatStreamService = chatStreamService;
        this.streamProperties = streamProperties;
    }

    /**
     * Streams a consultation answer as Server-Sent Events.
     *
     * <p>A well-formed request opens a {@code text/event-stream}. A malformed one (missing identity
     * triple or blank question) is rejected with a {@code 400} and a single SSE error event carrying
     * the error code before the long-lived stream is opened, so the caller gets a clean, parseable
     * rejection rather than a half-open connection.</p>
     *
     * @param request  the consultation request, must carry tenant/dept/session and a non-blank message
     * @param response the servlet response, used to set a {@code 400} status on a rejected request
     * @return the SSE emitter the container flushes to the client
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequireDept(required = false)
    @RateLimit(rate = 5, durationSeconds = 1)
    @Operation(summary = "Stream a consultation answer",
            description = "Opens a text/event-stream and pushes the assistant's answer chunk by chunk, "
                    + "with a periodic heartbeat. The tenant/dept/session identity and the patient's "
                    + "question drive RAG retrieval. A malformed request is rejected with a single SSE "
                    + "error event and HTTP 400 before the stream opens.")
    public SseEmitter streamConsultation(@RequestBody ChatStreamRequest request, HttpServletResponse response) {
        try {
            request.validate();
        } catch (BizException ex) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            SseEmitter emitter = new SseEmitter();
            safeSend(emitter, SseEmitter.event()
                    .name("error")
                    .data(ex.getErrorCode().getCode() + " " + ex.getErrorCode().getMessage()));
            emitter.complete();
            return emitter;
        }
        long timeoutMillis = TimeUnit.SECONDS.toMillis(streamProperties.getSseTimeoutSeconds());
        SseEmitter emitter = new SseEmitter(timeoutMillis);

        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "chat-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long interval = streamProperties.getHeartbeatIntervalSeconds();
        ScheduledFuture<?> heartbeatFuture = heartbeat.scheduleAtFixedRate(
                () -> {
                    try {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    } catch (IOException ex) {
                        // client already gone; the error/timeout callback disposes the stream
                    }
                },
                interval, interval, TimeUnit.SECONDS);

        StringBuilder fullAnswer = new StringBuilder();
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        Runnable cleanup = () -> {
            heartbeatFuture.cancel(true);
            heartbeat.shutdownNow();
        };

        Disposable disposable = chatStreamService.streamConsultation(request)
                .doOnNext(chunk -> {
                    if (chunk != null) {
                        fullAnswer.append(chunk);
                        try {
                            emitter.send(SseEmitter.event().name("message").data(chunk));
                        } catch (IOException ex) {
                            Disposable current = subscription.get();
                            if (current != null) {
                                current.dispose();
                            }
                        }
                    }
                })
                .doOnError(error -> {
                    log.warn("consultation stream failed: {}", error.getMessage());
                    cleanup.run();
                    safeSend(emitter, SseEmitter.event().name("error").data(errorMessage(error)));
                    try {
                        emitter.complete();
                    } catch (IllegalStateException ignore) {
                        // already completed by a concurrent timeout/completion callback
                    }
                })
                .doOnComplete(() -> {
                    cleanup.run();
                    safeSend(emitter, SseEmitter.event().name("done").data(fullAnswer.toString()));
                    try {
                        emitter.complete();
                    } catch (IllegalStateException ignore) {
                        // already completed by a concurrent timeout/error callback
                    }
                })
                .subscribe();
        subscription.set(disposable);

        emitter.onTimeout(() -> {
            disposable.dispose();
            cleanup.run();
            try {
                emitter.complete();
            } catch (IllegalStateException ignore) {
                // already completed
            }
        });
        emitter.onError(throwable -> {
            disposable.dispose();
            cleanup.run();
        });
        emitter.onCompletion(cleanup);

        return emitter;
    }

    /**
     * Sends an SSE event, swallowing the error raised when the client has already disconnected.
     *
     * @param emitter the SSE emitter
     * @param event   the event to send
     */
    private static void safeSend(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException ignore) {
            // connection already closed
        }
    }

    /**
     * Maps a streaming failure to a client-safe message.
     *
     * @param error the failure, must not be {@code null}
     * @return a short, non-internal message
     */
    private static String errorMessage(Throwable error) {
        if (error instanceof BizException biz) {
            return biz.getErrorCode().getMessage();
        }
        return "consultation stream error";
    }
}
