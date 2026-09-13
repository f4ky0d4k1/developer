package ru.allstreets.developer.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Перехватчик логирует тело как текст в UTF-8 и НЕ ломает ответ: тело буферизуется и
 * отдаётся потребителю без изменений (иначе ответ читается один раз и «пропадает»).
 */
class LoggingClientHttpRequestInterceptorTest {

    @Test
    void preservesResponseBodyStatusAndHeaders() throws Exception {
        var request = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://localhost/x"));
        byte[] responseBody = "{\"ок\":true}".getBytes(StandardCharsets.UTF_8);
        var response = new MockClientHttpResponse(responseBody, HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        var result = new LoggingClientHttpRequestInterceptor()
                .intercept(request, "{\"запрос\":1}".getBytes(StandardCharsets.UTF_8), (req, body) -> response);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertArrayEquals(responseBody, StreamUtils.copyToByteArray(result.getBody()));
        assertEquals(MediaType.APPLICATION_JSON, result.getHeaders().getContentType());
    }
}
