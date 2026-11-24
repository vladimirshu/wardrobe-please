package com.ecomassistant.mcp;

import com.ecomassistant.limits.SearchAdmission;
import com.ecomassistant.limits.SearchRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpEndpointTest {
    private static final String TOKEN = "external-client-token-01234567890123456789";
    private final CatalogRetrieval retrieval = mock(CatalogRetrieval.class);
    private final SearchAdmission admission = mock(SearchAdmission.class);
    private final CatalogMcpController controller = new CatalogMcpController(new McpProperties(true, false,
            "https://catalog.test/api/v1/mcp", "https://api.openai.com/v1/responses", 1200,
            Map.of("external", TOKEN)), new SearchLeases(), retrieval, admission);
    @Test void onlyAuthorizedClientsCanDiscoverOrCallAndInvalidOriginsAreForbidden() throws Exception {
        var unauthorized = request("tools/list", Map.of()); unauthorized.removeHeader("Authorization");
        assertThat(controller.receive(unauthorized).getStatusCode().value()).isEqualTo(401);
        var origin = request("tools/list", Map.of()); origin.addHeader("Origin", "https://attacker.test");
        assertThat(controller.receive(origin).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.noStream(request("tools/list", Map.of())).getStatusCode().value()).isEqualTo(405);
        assertThat(controller.receive(request("tools/list", Map.of())).getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(admission, retrieval);
    }
    @Test void externalRetrievalIsAdmittedOnceUnderItsConfiguredIdentity() throws Exception {
        doThrow(new SearchRejectedException(SearchRejectedException.CODE_RATE_LIMITED, 60)).when(admission).admit("mcp:external");
        var response = controller.receive(request("tools/call", Map.of("name", "search_catalog", "arguments", Map.of("searchQuery", "jacket"))));
        var body = CatalogMcpController.JSON.valueToTree(response.getBody());
        assertThat(body.toString()).contains("ADMISSION_REJECTED");
        assertThat(body.at("/result/isError").asBoolean()).isTrue();
        assertThat(body.at("/result/content/0/text").asString()).isEqualTo("ADMISSION_REJECTED");
        verify(admission, times(1)).admit("mcp:external"); verifyNoInteractions(retrieval);
    }
    @Test void arbitraryFiltersAndMalformedTypesCannotReachAdmissionOrRetrieval() throws Exception {
        for (var args : java.util.List.of(Map.of("searchQuery", "jacket", "filter", "price > 0"),
                Map.of("searchQuery", "jacket", "sortByLowestPrice", "true"),
                Map.of("searchQuery", "jacket", "priceConstraint", Map.of("operator", "LESS_THAN", "value", "100")))) {
            var response = controller.receive(request("tools/call", Map.of("name", "search_catalog", "arguments", args)));
            assertThat(CatalogMcpController.JSON.valueToTree(response.getBody()).at("/error/code").asInt()).isEqualTo(-32602);
        }
        verifyNoInteractions(admission, retrieval);
    }
    private static MockHttpServletRequest request(String method, Map<String, ?> params) {
        var request = new MockHttpServletRequest("POST", "/api/v1/mcp");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.addHeader("Accept", "application/json, text/event-stream");
        request.setContent(CatalogMcpController.JSON.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params)));
        return request;
    }
}
