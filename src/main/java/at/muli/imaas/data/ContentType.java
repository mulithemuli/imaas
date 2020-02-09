package at.muli.imaas.data;

import lombok.Builder;
import lombok.Value;

@Builder
@Value
public class ContentType {

    private String mediaType;
}
