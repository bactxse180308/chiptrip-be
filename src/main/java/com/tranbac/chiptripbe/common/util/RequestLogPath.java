package com.tranbac.chiptripbe.common.util;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

public final class RequestLogPath {
    private static final Pattern CAPABILITY = Pattern.compile("(/trips/(?:shared|invite)/)[^/;?]+");
    private RequestLogPath() { }

    public static String of(HttpServletRequest request) {
        return CAPABILITY.matcher(request.getRequestURI()).replaceAll("$1[REDACTED]");
    }
}
