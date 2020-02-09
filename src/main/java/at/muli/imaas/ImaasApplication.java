package at.muli.imaas;

import at.muli.imaas.data.ContentType;
import at.muli.imaas.service.ContentDetector;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.google.common.collect.ImmutableMap;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.IOUtils;
import org.imgscalr.Scalr;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

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
	
	@Value("#{'${imaas.supportedProtocols}'.split(',')}")
	private Collection<String> supportedProtocols;
	
	private ContentDetector contentDetector;
	
    public static void main(String[] args) {
		SpringApplication.run(ImaasApplication.class, args);
	}

	public ImaasApplication(ContentDetector contentDetector) {
    	this.contentDetector = contentDetector;
	}
	
	/**
	 * Turns the image to the right orientation and optionally performs transformations on it.
	 * 
	 * @param image the image to transform.
	 * @param height the maximum height to which the image should be transformed.
	 * @param width the maximum width to which the image should be transformed.
	 * @param fitTo where the image should fit – height or width.
	 * @return the image in the new dimensions and the rest of the sizes.
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
	
	@PostMapping(path = "metadata")
	public List<ImageExif> extractMetadata(@RequestBody byte[] image) {
		ImageMetadata imageMetadata = readMetadata(image);
		return Arrays.asList(ImageMetadataKeys.values()).parallelStream()
				.map(k -> new ImageExif(k.name, k.extractor.apply(imageMetadata.metadata)))
				.collect(Collectors.toList());
	}
	
	@GetMapping(path = "transform")
	public ResponseEntity<byte[]> transformFromPath(@RequestParam("image_path") String imagePath,
			@RequestParam(name = "height", required = false) Integer height,
			@RequestParam(name = "width", required = false) Integer width,
			@RequestParam(name = "fit_to", required = false) String fitTo) {
		byte[] image = readFromUrl(imagePath);
		TransformedImage transformedImage = transform(image, height, width, fitTo);
		Base64.getEncoder().encodeToString(image);
		return ResponseEntity.status(HttpStatus.OK).contentType(MediaType.parseMediaType(transformedImage.getMediaType())).body(transformedImage.getImage());
	}
	
	@GetMapping(path = "transform", produces = "application/json")
	public TransformedImage transformFromPathAsJson(@RequestParam("image_path") String imagePath,
			@RequestParam(name = "height", required = false) Integer height,
			@RequestParam(name = "width", required = false) Integer width,
			@RequestParam(name = "fit_to", required = false) String fitTo) {
		ResponseEntity<byte[]> image = transformFromPath(imagePath, height, width, fitTo);
		return new TransformedImage(image.getBody(), image.getHeaders().getContentType().toString(), 0, 0);
	}
	
	@GetMapping(path = "metadata")
	public List<ImageExif> extractMetadata(@RequestParam("image_path") String imagePath) {
		return extractMetadata(readFromUrl(imagePath));
	}
	
	private byte[] readFromUrl(String resource) {
		try {
			URL url = new URL(resource);
			if (!supportedProtocols.contains(url.getProtocol())) {
				throw new MediaTypeNotSupportedException();
			}
			return IOUtils.toByteArray(url.openStream());
		} catch (Exception e) {
			throw new NotFoundException();
		}
	}

	/**
	 * Extracts the {@link Metadata} information and content type from the given image.
	 * 
	 * @param image the image where to get the metadata from.
	 * @return the extracted metadata of the image.
	 * @throws MediaTypeNotSupportedException when the content type of the image is not in {@link #SUPPORTED_IMAGE_TYPES}.
	 */
	private ImageMetadata readMetadata(byte[] image) throws MediaTypeNotSupportedException {
		ContentType contentType = contentDetector.getContentType(image);
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
		// case 1 ignored since it is the same as default
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
        	if (log.isWarnEnabled()) {
        		log.warn("Could not get orientation for image");
			}
            return 1;
        }
    }
    
    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
	private static class TransformedImage {
    	
    	private byte[] image;
    	
    	private String mediaType;
    	
    	private int height;
    	
    	private int width;
    }
    
    @AllArgsConstructor
    @Getter
	private static class ImageMetadata {
    	
    	private String contentType;
    	
    	private Metadata metadata;
    }
    
    @AllArgsConstructor
    @Getter
	private static class ImageExif {
    	
    	private String name;
    	
    	private String value;
    }
    
    @AllArgsConstructor
    private static class Ratio {
    	
    	private BufferedImage image;
    	
    	private Integer height;
    	
    	private Integer width;
    	
    	boolean hasRatio() {
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
    		return Math.round(((float) image.getWidth() / image.getHeight()) * height);
    	}
    }
    
	public enum ImageMetadataKeys {

		DATETIME_TAKEN("Taken", m -> getMetadata(m, d -> d.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL), r -> r.toInstant().toString())),

		CAMERA_MAKE("Make", m -> getMetadata(m, d -> d.getString(ExifIFD0Directory.TAG_MAKE))),

		CAMERA_MODEL("Model", m -> getMetadata(m, d -> d.getString(ExifIFD0Directory.TAG_MODEL)));

		private String name;

		private Function<Metadata, String> extractor;

		ImageMetadataKeys(String name, Function<Metadata, String> extractor) {
			this.name = name;
			this.extractor = extractor;
		}

		public String getName() {
			return name;
		}

		private static String getMetadata(Metadata metadata, Function<ExifIFD0Directory, String> function) {
			return getMetadata(metadata, function, null);
		}

		private static <R> String getMetadata(Metadata metadata, Function<ExifIFD0Directory, R> function, Function<R, String> toString) {
			ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
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
	private static class NotFoundException extends RuntimeException {

		private static final long serialVersionUID = 1L;
	}
	
	@ResponseStatus(value = HttpStatus.UNSUPPORTED_MEDIA_TYPE)
	public static class MediaTypeNotSupportedException extends RuntimeException {

		private static final long serialVersionUID = 1L;
		
		private static final String SUPPORTED_IMAGE_TYPES_ERROR = "the requested media type is not supported (supported: " + SUPPORTED_IMAGE_TYPES.keySet() + ")";
		
		MediaTypeNotSupportedException() {
			super(SUPPORTED_IMAGE_TYPES_ERROR);
		}
	}

}
