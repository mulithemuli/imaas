package at.muli.imaas;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;

import javax.imageio.ImageIO;

import org.imgscalr.Scalr;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

@SpringBootApplication
@RestController
@Log4j2
public class ImaasApplication {

	public static void main(String[] args) {
		SpringApplication.run(ImaasApplication.class, args);
	}
	
	/**
	 * Turns the image to the right orientation and optionally performs transformations on it.
	 * 
	 * @param image
	 * @return
	 */
	@RequestMapping(path = "transform", method = RequestMethod.POST)
	public byte[] transform(@RequestParam("image") byte[] image, @RequestParam(name = "transformOptions", required = false) TransformOptions transformOptions) {
		try (ByteArrayInputStream bis = new ByteArrayInputStream(image);
				ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
			Metadata metadata = readMetadata(bis);
			BufferedImage bImage = ImageIO.read(bis);
			for (Scalr.Rotation rotation : calculateRotation(metadata)) {
				bImage = Scalr.rotate(bImage, rotation);
			}
			
			if (transformOptions != null) {
				bImage = Scalr.resize(bImage, Scalr.Method.QUALITY, Scalr.Mode.FIT_TO_HEIGHT, transformOptions.maxWidth, transformOptions.maxHeight, Scalr.OP_ANTIALIAS);
			}
			
			ImageIO.write(bImage, "CONTENTTYPE", byteArrayOutputStream);
			return byteArrayOutputStream.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
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
	@Getter
	public static class TransformOptions implements Serializable {
		
		/**
		 * 
		 */
		private static final long serialVersionUID = 1L;

		private int maxWidth;
		
		private int maxHeight;
	}
}
