package com.meeting.common.filter;

import com.meeting.common.TtlMdcAdapter;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Integer.MIN_VALUE)
public class TraceIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        String traceId = (request instanceof HttpServletRequest httpRequest)
                ? httpRequest.getHeader("X-Trace-Id") : null;
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        TtlMdcAdapter.setTraceId(traceId);
        TtlMdcAdapter.setLayer("API");

        if (response instanceof HttpServletResponse httpResponse) {
            httpResponse.setHeader("X-Trace-Id", traceId);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            TtlMdcAdapter.clear();
        }
    }
}
