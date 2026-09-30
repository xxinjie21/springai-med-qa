package com.med.qa.controller;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedChatStreamProperties;
import com.med.qa.controller.dto.ChatStreamRequest;
import com.med.qa.common.ratelimit.annotation.RateLimit;
import com.med.qa.security.MedSecurityContext;
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
 * <h2>Where the identity comes from (D41)</h2>
 * <p>The tenant / department / patient of a turn is the authenticated {@link
 * com.med.qa.security.MedPrincipal} bound to this request by the API-key filter, and the controller
 * hands that principal to the service explicitly. The identity fields in the JSON body are demoted to
 * consistency claims: the service refuses a request whose claims contradict the principal with
 * {@code 403}. Before D41 the body alone decided the scope, so a caller could read and write another
 * patient's transcript.</p>
 *
 * <p>{@link RequireDept} therefore keeps {@code required = false}: the department now lives in the
 * principal rather than in the body, and an interceptor must not consume the body anyway. The
 * annotation still guarantees authentication and role before the handler runs, and the authoritative
 * department check is performed by the service against the principal.</p>
 *
 * <h2>Every synchronous failure is answered before the stream opens</h2>
 * <p>A malformed request ({@code 400}), an authorization or lookup refusal ({@code 403} / {@code 404}), a
 * missing model ({@code 503}) and a caller-side scope error ({@code 400}) are all raised synchronously by
 * the service and are all answered as a short-lived SSE emitter carrying a single {@code error} event,
 * with the matching HTTP status set. They are deliberately not left to {@code GlobalExceptionHandler},
 * which maps every {@link BizException} onto HTTP {@code 200} with a JSON business envelope — a body this
 * endpoint's {@code text/event-stream} contract cannot render, so the failure would surface as an opaque
 * content-negotiation error instead of the caller's actual problem. A failure <em>inside</em> an
 * already-open stream still travels as an SSE {@code error} event on the live connection.</p>
 */
@RestController
@RequestMapping("/api/chat")
@EnableConfigurationProperties(MedChatStreamProperties.class)
@Tag(name = "Consultation Chat", description = "Streaming AI consultation over Server-Sent Events. "
        + "Requires an API key: the authenticated principal — not the request body — decides the "
        + "tenant/department/patient scope that drives the RAG metadata filters.")
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
     * <p>A well-formed request opens a {@code text/event-stream}. A request that fails validation, that
     * claims an identity the caller is not authenticated for, or that names a session the caller may not
     * write to is rejected <em>before</em> the long-lived stream is opened: the response carries the
     * matching HTTP status and a single SSE {@code error} event holding the business code, so the caller
     * gets a clean, parseable refusal instead of a half-open connection.</p>
     *
     * @param request  the consultation request; must carry a non-blank {@code session} and {@code message}
     * @param response the servlet response, used to set the refusal status
     * @return the SSE emitter the container flushes to the client
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequireDept(required = false)
    @RateLimit(rate = 5, durationSeconds = 1)
    @Operation(summary = "Stream a consultation answer",
            description = "Opens a text/event-stream and pushes the assistant's answer chunk by chunk, "
                    + "with a periodic heartbeat. The consultation runs in the scope of the authenticated "
                    + "API key; identity fields in the body are only cross-checked against it and a "
                    + "contradiction is refused with 403. A malformed request is rejected with a single SSE "
                    + "error event and HTTP 400 before the stream opens.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "The request claims a tenant/department/patient the caller is not "
                    + "authenticated for, or the session belongs to another patient")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "The session does not exist in the caller's tenant/department")
    public SseEmitter streamConsultation(@RequestBody ChatStreamRequest request, HttpServletResponse response) {
        Flux<String> content;
        try {
            request.validate();
            content = chatStreamService.streamConsultation(request, MedSecurityContext.getPrincipal());
        } catch (BizException ex) {
            return refuse(response, statusFor(ex.getErrorCode()), describe(ex.getErrorCode()));
        } catch (IllegalArgumentException ex) {
            // A caller error raised while assembling the scope/retrieval combination (e.g. a
            // department-wide scope that excludes shared documents). Mapped here for the same reason as
            // the business errors: the alternative is an ApiResult the SSE content negotiation cannot
            // render, which surfaces as an opaque 406 instead of a readable 400.
            return refuse(response, HttpStatus.BAD_REQUEST,
                    ErrorCode.BAD_REQUEST.getCode() + " " + ex.getMessage());
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

        Disposable disposable = content
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
     * Answers a refusal with its HTTP status and a single SSE {@code error} event, without opening a
     * long-lived stream.
     *
     * @param response the servlet response receiving the status
     * @param status   the HTTP status matching the business error
     * @param payload  the text carried by the {@code error} event
     * @return an already completed emitter carrying the error event
     */
    private static SseEmitter refuse(HttpServletResponse response, HttpStatus status, String payload) {
        response.setStatus(status.value());
        SseEmitter emitter = new SseEmitter();
        safeSend(emitter, SseEmitter.event().name("error").data(payload));
        emitter.complete();
        return emitter;
    }

    /**
     * Renders a business error the way the SSE {@code error} event carries it.
     *
     * @param errorCode the business error code, must not be {@code null}
     * @return {@code "<code> <message>"}
     */
    private static String describe(ErrorCode errorCode) {
        return errorCode.getCode() + " " + errorCode.getMessage();
    }

    /**
     * Maps every business error this endpoint can raise synchronously onto an HTTP status.
     *
     * <p>The mapping is deliberately <em>total</em>. This handler produces
     * {@code text/event-stream}, and the global advice answers a {@link BizException} with an
     * {@code ApiResult} JSON body that no converter can render as an event stream — so letting a
     * synchronous failure reach the advice would surface as an opaque content-negotiation error rather
     * than as the caller's actual problem. Every error therefore gets a status and a parseable SSE
     * {@code error} event.</p>
     *
     * @param errorCode the business error code, must not be {@code null}
     * @return the HTTP status a caller should see
     */
    private static HttpStatus statusFor(ErrorCode errorCode) {
        return switch (errorCode) {
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case LLM_SERVICE_ERROR, STORAGE_ERROR -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
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
