package com.eventledger.gateway.filter;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class TracingFilter implements Filter {

    private final Tracer tracer;
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String traceId = httpRequest.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString();
        }

        String spanName = httpRequest.getMethod() + " " + httpRequest.getRequestURI();

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("http.method", httpRequest.getMethod())
                .setAttribute("http.url", httpRequest.getRequestURI())
                .setAttribute("trace.id", traceId)
                .setAttribute("service.name", "gateway-service")
                .startSpan();

        MDC.put(TRACE_ID_MDC_KEY, traceId);
        httpResponse.setHeader(TRACE_ID_HEADER, traceId);

        try (Scope scope = span.makeCurrent()) {
            httpRequest.setAttribute(TRACE_ID_HEADER, traceId);
            chain.doFilter(request, response);

            span.setAttribute("http.status_code", httpResponse.getStatus());
            if (httpResponse.getStatus() >= 400) {
                span.setStatus(StatusCode.ERROR);
            }
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }
}
