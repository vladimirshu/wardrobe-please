package com.ecomassistant.support;

import java.util.LinkedHashMap;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

public final class SearchRequests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SearchRequests() {}

    public static MockHttpServletRequestBuilder jsonSearch(Object... fields) {
        var body = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.length; i += 2) {
            body.put((String) fields[i], fields[i + 1]);
        }
        return post("/api/v1/search").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body));
    }
}
