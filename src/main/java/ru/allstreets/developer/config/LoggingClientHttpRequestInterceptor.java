package ru.allstreets.developer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Логирует HTTP-запрос и ответ как <b>текст в UTF-8</b> — читаемо, в отличие от wire-дампа
 * байтов Apache HttpClient ({@code [0xffffffd0]…}). Ставится на {@code RestClient}.
 * <p>
 * Тело ответа буферизуется и отдаётся дальше как есть (обёртка {@link ClientHttpResponse}),
 * поэтому потребитель читает его без изменений. Логирует на DEBUG, тела обрезаются до
 * {@link #MAX_BODY} символов, чтобы не раздувать лог.
 */
public class LoggingClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingClientHttpRequestInterceptor.class);
    private static final int MAX_BODY = 4000;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        log.debug("HTTP >> {} {}\n{}", request.getMethod(), request.getURI(), asText(body));
        ClientHttpResponse response = execution.execute(request, body);
        byte[] responseBody = StreamUtils.copyToByteArray(response.getBody());
        log.debug("HTTP << {} {} {}\n{}", response.getStatusCode().value(), request.getMethod(), request.getURI(),
                asText(responseBody));
        return new BufferedClientHttpResponse(response, responseBody);
    }

    private static String asText(byte[] body) {
        if (body == null || body.length == 0) {
            return "(empty)";
        }
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() > MAX_BODY ? text.substring(0, MAX_BODY) + "…(" + text.length() + " chars)" : text;
    }

    /** Ответ с уже прочитанным (буферизованным) телом — потребитель получает его без изменений. */
    private static final class BufferedClientHttpResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final byte[] body;

        private BufferedClientHttpResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getRawStatusCode() throws IOException {
            return delegate.getRawStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }
    }
}
