package ma.mobility.abrid.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configuration du client HTTP vers OpenTripPlanner.
 * Le bean {@code otpClient} n'est créé que si {@code app.otp.enabled=true}.
 */
@Configuration
@ConditionalOnProperty(name = "app.otp.enabled", havingValue = "true")
public class OtpConfig {

    @Value("${app.otp.base-url:http://otp:8080}")
    private String otpBaseUrl;

    @Value("${app.otp.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${app.otp.read-timeout-ms:10000}")
    private int readTimeoutMs;

    /**
     * {@link RestClient} configuré avec les timeouts OTP.
     * Si OTP ne répond pas dans le délai, une exception est levée et le
     * circuit breaker déclenche le fallback SQL.
     */
    @Bean
    public RestClient otpClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return RestClient.builder()
            .baseUrl(otpBaseUrl)
            .requestFactory(factory)
            .defaultHeader("Accept", "application/json")
            .build();
    }
}
