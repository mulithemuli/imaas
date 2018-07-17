package at.muli.imaas;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import javax.imageio.ImageIO;

import org.imgscalr.Scalr;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.google.common.collect.ImmutableMap;

import at.muli.imaas.ImaasApplication.ContentDetectorClient.ContentType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;

@SpringBootApplication
@RestController
@EnableDiscoveryClient
@EnableFeignClients
@Log4j2
public class ImaasApplication {

	private static final Map<String, String> SUPPORTED_IMAGE_TYPES = ImmutableMap.<String, String>builder()
			.put("image/jpeg", "JPG")
			.put("image/png", "PNG")
			.build();
	
	@Autowired
	private ContentDetectorClient contentDetectorClient;
	
	@Autowired
	private ImageMetadataServiceClient imageMetadataServiceClient;
    
    public static void main(String[] args) {
		SpringApplication.run(ImaasApplication.class, args);
	}
	
	/**
	 * Turns the image to the right orientation and optionally performs transformations on it.
	 * 
	 * @param image
	 * @param maxHeight
	 * @param maxWidth
	 * @param fitTo
	 * @return
	 */
	@RequestMapping(path = "transform", method = RequestMethod.POST)
	public TransformedImage transform(@RequestBody byte[] image,
			@RequestParam(name = "maxHeight", required = false) Integer maxHeight,
			@RequestParam(name = "maxWidth", required = false) Integer maxWidth,
			@RequestParam(name = "fitTo", required = false) String fitTo) {
		ContentType contentType = contentDetectorClient.getContentType(image);
		if (!SUPPORTED_IMAGE_TYPES.containsKey(contentType.getMediaType())) {
			throw new MediaTypeNotSupportedException();
		}
		try (ByteArrayInputStream bis = new ByteArrayInputStream(image);
				ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
			Metadata metadata = readMetadata(bis);
			bis.reset();
			BufferedImage bImage = ImageIO.read(bis);
			for (Scalr.Rotation rotation : calculateRotation(metadata)) {
				bImage = Scalr.rotate(bImage, rotation);
			}
			
			if (maxHeight != null && maxWidth != null) {
				Scalr.Mode mode = Scalr.Mode.FIT_TO_HEIGHT;
				if (fitTo != null) {
					try {
						mode = Scalr.Mode.valueOf(fitTo);
					} catch (IllegalArgumentException e) {
						// invalid mode - stick to default
					}
				}
				bImage = Scalr.resize(bImage, Scalr.Method.QUALITY, mode, maxWidth, maxHeight, Scalr.OP_ANTIALIAS);
			}
			
			ImageIO.write(bImage, SUPPORTED_IMAGE_TYPES.get(contentType.getMediaType()), byteArrayOutputStream);
			return new TransformedImage(byteArrayOutputStream.toByteArray(), contentType.getMediaType());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	@RequestMapping(path = "transform", method = RequestMethod.GET)
	public ResponseEntity<byte[]> transformFromPath(@RequestParam("imagePath") String imagePath,
			@RequestParam(name = "maxHeight", required = false) Integer maxHeight,
			@RequestParam(name = "maxWidth", required = false) Integer maxWidth,
			@RequestParam(name = "fitTo", required = false) String fitTo) {
		Path path = Paths.get(imagePath);
		if (!Files.exists(path)) {
			throw new NotFoundException();
		}
		try {
			byte[] image = Files.readAllBytes(path);
			TransformedImage transformedImage = imageMetadataServiceClient.transform(image, maxHeight, maxWidth, fitTo);
			return ResponseEntity.status(HttpStatus.OK).contentType(MediaType.parseMediaType(transformedImage.getMediaType())).body(transformedImage.getImage());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	@FeignClient("ImageMetadataService")
	public static interface ImageMetadataServiceClient {
		
		@RequestMapping(value = "/transform", method = RequestMethod.POST)
		TransformedImage transform(@RequestBody byte[] image, @RequestParam("maxHeight") Integer maxHeight, @RequestParam("maxWidth") Integer maxWidth, @RequestParam("fitTo") String fitTo);
	}
	
    @FeignClient(value = "ContentDetectionService")
    public static interface ContentDetectorClient {
    	
    	@RequestMapping(value = "/content", method = RequestMethod.POST)
    	ContentType getContentType(byte[] data);
    	
    	@Getter
    	public static class ContentType {
    		private String mediaType;
    	}
    }
    
    private Metadata readMetadata(InputStream imageInputStream) {
        try {
            return ImageMetadataReader.readMetadata(imageInputStream);
        } catch (IOException | ImageProcessingException e) {
            throw new RuntimeException("unable to read metadata for image", e);
        }
    }

	private Scalr.Rotation[] calculateRotation(Metadata metadata) {
		int orientation = readImageOrientation(metadata);
		switch (orientation) {
		case 1:
			return new Scalr.Rotation[] {};
		case 2:
			return new Scalr.Rotation[] { Scalr.Rotation.FLIP_HORZ };
		case 3:
			return new Scalr.Rotation[] { Scalr.Rotation.CW_180 };
		case 4:
			return new Scalr.Rotation[] { Scalr.Rotation.FLIP_VERT };
		case 5:
			return new Scalr.Rotation[] { Scalr.Rotation.CW_90, Scalr.Rotation.FLIP_HORZ };
		case 6:
			return new Scalr.Rotation[] { Scalr.Rotation.CW_90 };
		case 7:
			return new Scalr.Rotation[] { Scalr.Rotation.CW_90, Scalr.Rotation.FLIP_VERT };
		case 8:
			return new Scalr.Rotation[] { Scalr.Rotation.CW_270 };
		default:
			return new Scalr.Rotation[] {};
		}
	}
	
    private int readImageOrientation(Metadata metadata) {
        if (metadata == null) {
            return 1;
        }
        Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (directory == null) {
            return 1;
        }

        try {
            return directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
        } catch (MetadataException me) {
            log.warn(String.format("Could not get orientation for image"));
            return 1;
        }
    }
    
    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    public static class TransformedImage {
    	
    	private byte[] image;
    	
    	private String mediaType;
    }
	
	@ResponseStatus(value = HttpStatus.NOT_FOUND, reason = "the image cannot be found")
	public static class NotFoundException extends RuntimeException {

		private static final long serialVersionUID = 1L;
	}
	
	@ResponseStatus(value = HttpStatus.UNSUPPORTED_MEDIA_TYPE, reason = "the requested media type is not supported")
	public static class MediaTypeNotSupportedException extends RuntimeException {

		private static final long serialVersionUID = 1L;
	}
}
