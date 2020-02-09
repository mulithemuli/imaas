package at.muli.imaas.service;

import at.muli.imaas.data.ContentType;

public interface ContentDetector {

    ContentType getContentType(byte[] data);
}
