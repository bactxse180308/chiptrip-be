package com.tranbac.chiptripbe.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@Order(1)
public class RequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(req, res);
        } finally {
            long ms = System.currentTimeMillis() - start;
            int status = res.getStatus();
            String pattern = "{} {} → {} ({}ms)";
            if (status >= 500) {
                log.error(pattern, req.getMethod(), req.getRequestURI(), status, ms);
            } else if (status >= 400) {
                log.warn(pattern, req.getMethod(), req.getRequestURI(), status, ms);
            } else {
                log.debug(pattern, req.getMethod(), req.getRequestURI(), status, ms);
            }
        }
    }
}