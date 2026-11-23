package com.ecomassistant.search;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Starts before JSON binding, validation and admission; excludes every non-search route. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SearchBudgetFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !(request.getContextPath() + "/api/v1/search").equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var buffered = new org.springframework.web.util.ContentCachingResponseWrapper(response);
        try (var budget = new SearchBudget()) {
            try {
                chain.doFilter(request, buffered);
                budget.check();
            } catch (Exception ex) {
                boolean timeout = ex instanceof SearchTimeoutException;
                try { budget.check(); } catch (SearchTimeoutException expired) { timeout = true; }
                if (!timeout) {
                    if (ex instanceof IOException io) throw io;
                    if (ex instanceof ServletException servlet) throw servlet;
                    if (ex instanceof RuntimeException runtime) throw runtime;
                    throw new ServletException(ex);
                }
                if (!buffered.isCommitted()) {
                    buffered.resetBuffer();
                    buffered.setStatus(504);
                    buffered.setContentType("application/json");
                    buffered.getWriter().write("{\"error\":\"Search timed out\"}");
                }
            }
        }
        buffered.copyBodyToResponse();
    }
}
