(function($) {
	let imageUrlInput = $(document.getElementById('image_url'));
	let imageFileInput = $(document.getElementById('image_file'));
	let imageFileLabel = imageFileInput.siblings('label');
	let exifModal = $(document.getElementById('exif_dialog'));
	let transformedImageModal = $(document.getElementById('transformed_image_dialog'));
	let exifDataBody = $(document.getElementById('exif_data_body'));
	let transformedImage = $(document.getElementById('transformed_image'));

	let mode = 'upload'
	
	$('input[name=image_type]').on('change', (e) => {
		switch (e.currentTarget.id) {
		case 'image_type_url':
			imageUrlInput.removeClass('d-none');
			imageFileInput.parent().addClass('d-none');
			fileUpload = null;
			imageFileLabel.text('Choose image');
			mode = 'url';
			break;
		case 'image_type_file':
			imageUrlInput.addClass('d-none');
			imageFileInput.parent().removeClass('d-none');
			mode = 'upload';
			break;
		default:
			// nothing
		}
	});
	
	let fileUpload;
	
	imageFileInput.on('change', (e) => {
		if (e.target.files.length > 0) {
			let reader = new FileReader();
			reader.onload = function() {
				fileUpload = new Uint8Array(this.result);
			}
			reader.readAsArrayBuffer(e.target.files[0]);
			imageFileLabel.text(e.target.files[0].name);
		}
	});
	
	let withFilePost = (method, done, parameters) => {
		if (mode === 'upload') {
			if (!fileUpload) {
				return;
			}
			$.post({
				url: method,
				data: fileUpload,
			    processData: false,
			    contentType: false,
			}).done(done);
		} else if (mode === 'url') {
			// read from URL ...
			$.get({
				url: method,
				data: { imagePath: imageUrlInput.val() },
				dataType: 'json'
			}).done(done);
		}
	}
	
	$(document.getElementById('transform_image')).on('click', () => {
		withFilePost('transform?' + $('#height, #width, #fit_to').serialize(), (data) => {
			transformedImage.html(templates.transformedImage(data));
			transformedImageModal.modal('show');
		});
	});
	
	$(document.getElementById('read_exif')).on('click', () => {
		withFilePost('metadata', (data) => {
			exifDataBody.children().remove();
			$.each(data, (i, exif) => {
				exifDataBody.append(templates.exifRow(exif));
			});
			exifModal.modal('show');
		});
	});
	
	let templates = {
			exifRow: _.template('<tr><td><%-name%></td><td><%-value%></td></tr>'),
			transformedImage: _.template('<img src="data:<%-mediaType%>;base64,<%-image%>" />')
	};
}(jQuery));