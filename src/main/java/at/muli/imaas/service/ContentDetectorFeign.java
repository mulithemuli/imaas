package at.muli.imaas.service;

import at.muli.imaas.data.ContentType;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

public class ContentDetectorFeign implements ContentDetector {

    private ContentDetectorClient contentDetectorClient;

    public ContentDetectorFeign(ContentDetectorClient contentDetectorClient) {
        this.contentDetectorClient = contentDetectorClient;
    }

    @Override
    public ContentType getContentType(byte[] data) {
        return contentDetectorClient.getContentType(data);
    }

    @FeignClient(value = "ContentDetectionService")
    public interface ContentDetectorClient {

        @RequestMapping(value = "/content", method = RequestMethod.POST)
        ContentType getContentType(byte[] data);
    }
}
