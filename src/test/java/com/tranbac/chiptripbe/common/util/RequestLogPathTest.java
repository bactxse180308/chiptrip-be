package com.tranbac.chiptripbe.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestLogPathTest {
    @Test void redactsBothCapabilityRoutesWithoutChangingRegularRoutes() {
        for (String route : new String[]{"shared", "invite"}) {
            assertEquals("/api/v1/trips/" + route + "/[REDACTED]",
                    RequestLogPath.of(new MockHttpServletRequest("GET", "/api/v1/trips/" + route + "/secret")));
        }
        assertEquals("/api/v1/trips/1/public", RequestLogPath.of(new MockHttpServletRequest("GET", "/api/v1/trips/1/public")));
    }
}
