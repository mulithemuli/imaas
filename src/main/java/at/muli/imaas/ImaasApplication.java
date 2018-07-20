package at.muli.imaas;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.apache.commons.io.IOUtils;
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
import com.drew.metadata.exif.ExifSubIFDDirectory;
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
	
    public static void main(String[] args) {
		SpringApplication.run(ImaasApplication.class, args);
	}
	
	/**
	 * Turns the image to the right orientation and optionally performs transformations on it.
	 * 
	 * @param image
	 * @param height
	 * @param width
	 * @param fitTo
	 * @return
	 */
	@RequestMapping(path = "transform", method = RequestMethod.POST)
	public TransformedImage transform(@RequestBody byte[] image,
			@RequestParam(name = "height", required = false) Integer height,
			@RequestParam(name = "width", required = false) Integer width,
			@RequestParam(name = "fit_to", required = false) String fitTo) {
		ImageMetadata imageMetadata = readMetadata(image);
		try (ByteArrayInputStream bis = new ByteArrayInputStream(image);
				ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
			BufferedImage bImage = ImageIO.read(bis);
			for (Scalr.Rotation rotation : calculateRotation(imageMetadata.metadata)) {
				bImage = Scalr.rotate(bImage, rotation);
			}
			
			Ratio ratio = new Ratio(bImage, height, width);
			if (ratio.hasRatio()) {
				Scalr.Mode mode = Scalr.Mode.FIT_TO_HEIGHT;
				if (fitTo != null) {
					try {
						mode = Scalr.Mode.valueOf(fitTo);
					} catch (IllegalArgumentException e) {
						// invalid mode - stick to default
					}
				}
				bImage = Scalr.resize(bImage, Scalr.Method.QUALITY, mode, ratio.getWidth(), ratio.getHeight(), Scalr.OP_ANTIALIAS);
			}
			
			ImageIO.write(bImage, SUPPORTED_IMAGE_TYPES.get(imageMetadata.contentType), byteArrayOutputStream);
			return new TransformedImage(byteArrayOutputStream.toByteArray(), imageMetadata.contentType, bImage.getHeight(), bImage.getWidth());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	@RequestMapping(path = "metadata", method = RequestMethod.POST)
	public List<ImageExif> extractMetadata(@RequestBody byte[] image) {
		ImageMetadata imageMetadata = readMetadata(image);
		return Arrays.asList(ImageMetadataKeys.values()).parallelStream()
				.map(k -> new ImageExif(k.name, k.extractor.apply(imageMetadata.metadata)))
				.collect(Collectors.toList());
	}
	
	@RequestMapping(path = "transform", method = RequestMethod.GET)
	public ResponseEntity<byte[]> transformFromPath(@RequestParam("imagePath") String imagePath,
			@RequestParam(name = "height", required = false) Integer height,
			@RequestParam(name = "width", required = false) Integer width,
			@RequestParam(name = "fit_to", required = false) String fitTo) {
		byte[] image = readFromUrl(imagePath);
		TransformedImage transformedImage = transform(image, height, width, fitTo);
		Base64.getEncoder().encodeToString(image);
		return ResponseEntity.status(HttpStatus.OK).contentType(MediaType.parseMediaType(transformedImage.getMediaType())).body(transformedImage.getImage());
	}
	
	@RequestMapping(path = "transform", method = RequestMethod.GET, produces = "application/json")
	public TransformedImage transformFromPathAsJson(@RequestParam("imagePath") String imagePath,
			@RequestParam(name = "height", required = false) Integer height,
			@RequestParam(name = "width", required = false) Integer width,
			@RequestParam(name = "fit_to", required = false) String fitTo) {
		ResponseEntity<byte[]> image = transformFromPath(imagePath, height, width, fitTo);
		return new TransformedImage(image.getBody(), image.getHeaders().getContentType().toString(), 0, 0);
	}
	
	@RequestMapping(path = "metadata", method = RequestMethod.GET)
	public List<ImageExif> extractMetadata(@RequestParam("imagePath") String imagePath) {
		return extractMetadata(readFromUrl(imagePath));
	}
	
	private byte[] readFromUrl(String resource) {
		try {
			URL url = new URL(resource);
			return IOUtils.toByteArray(url.openStream());
		} catch (Exception e) {
			throw new NotFoundException();
		}
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
    
	/**
	 * Extracts the {@link Metadata} information and content type from the given image.
	 * 
	 * @param image
	 * @return
	 * @throws MediaTypeNotSupportedException when the content type of the image is not in {@link #SUPPORTED_IMAGE_TYPES}.
	 */
	private ImageMetadata readMetadata(byte[] image) throws MediaTypeNotSupportedException {
		ContentType contentType = contentDetectorClient.getContentType(image);
		if (!SUPPORTED_IMAGE_TYPES.containsKey(contentType.getMediaType())) {
			throw new MediaTypeNotSupportedException();
		}
		try (ByteArrayInputStream imageInputStream = new ByteArrayInputStream(image)) {
			return new ImageMetadata(contentType.getMediaType(), ImageMetadataReader.readMetadata(imageInputStream));
		} catch (ImageProcessingException | IOException e) {
			throw new RuntimeException(e);
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
    	
    	private int height;
    	
    	private int width;
    }
    
    @AllArgsConstructor
    @Getter
    public static class ImageMetadata {
    	
    	private String contentType;
    	
    	private Metadata metadata;
    }
    
    @AllArgsConstructor
    @Getter
    public static class ImageExif {
    	
    	private String name;
    	
    	private String value;
    }
    
    @AllArgsConstructor
    private static class Ratio {
    	
    	private BufferedImage image;
    	
    	private Integer height;
    	
    	private Integer width;
    	
    	public boolean hasRatio() {
    		return height != null || width != null;
    	}
    	
    	public int getHeight() {
    		if (height != null) {
    			return height;
    		}
    		return Math.round(((float) image.getHeight() / image.getWidth()) * width);
    	}
    	
    	public int getWidth() {
    		if (width != null) {
    			return width;
    		}
    		return Math.round(((float) image.getHeight() / image.getWidth()) * height);
    	}
    }
    
	public enum ImageMetadataKeys {

		DATETIME_TAKEN("Taken", m -> getMetadata(ExifSubIFDDirectory.class, m, d -> d.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL), r -> r.toInstant().toString())),

		CAMERA_MAKE("Make", m -> getMetadata(ExifIFD0Directory.class, m, d -> d.getString(ExifIFD0Directory.TAG_MAKE))),

		CAMERA_MODEL("Model", m -> getMetadata(ExifIFD0Directory.class, m, d -> d.getString(ExifIFD0Directory.TAG_MODEL)));

		private String name;

		private Function<Metadata, String> extractor;

		private ImageMetadataKeys(String name, Function<Metadata, String> extractor) {
			this.name = name;
			this.extractor = extractor;
		}

		public String getName() {
			return name;
		}

		public String extractMetadata(Metadata metadata) {
			return extractor.apply(metadata);
		}

		private static <T extends Directory> String getMetadata(Class<T> directoryClass, Metadata metadata, Function<T, String> function) {
			return getMetadata(directoryClass, metadata, function, null);
		}

		private static <T extends Directory, R> String getMetadata(Class<T> directoryClass, Metadata metadata, Function<T, R> function, Function<R, String> toString) {
			T directory = metadata.getFirstDirectoryOfType(directoryClass);
			if (directory == null) {
				return null;
			}
			R value = function.apply(directory);
			if (value == null) {
				return null;
			}
			if (toString == null) {
				return value.toString();
			}
			return toString.apply(value);
		}
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
