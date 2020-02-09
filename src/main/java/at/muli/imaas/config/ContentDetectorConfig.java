package at.muli.imaas.config;

import at.muli.imaas.data.ContentType;
import at.muli.imaas.service.ContentDetector;
import at.muli.imaas.service.ContentDetectorFeign;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;

@Configuration
@Log4j2
public class ContentDetectorConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "eureka.client.enabled", matchIfMissing = true)
    public ContentDetector contentDetectorFeign(ContentDetectorFeign.ContentDetectorClient contentDetectorClient) {
        return new ContentDetectorFeign(contentDetectorClient);
    }

    @Bean
    @ConditionalOnMissingBean(ContentDetector.class)
    public ContentDetector contentDetectorLocal() {
        return b -> {
            log.info("using content detection fallback");
            try (InputStream is = new BufferedInputStream(new ByteArrayInputStream(b))) {
                String mimeType = URLConnection.guessContentTypeFromStream(is);
                return ContentType.builder().mediaType(mimeType).build();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }
}
