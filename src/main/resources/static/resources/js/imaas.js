(function($) {
	let imageUrlInput = $(document.getElementById('image_url'));
	let imageFileInput = $(document.getElementById('image_file'));
	let imageFileLabel = imageFileInput.siblings('label');
	let exifModal = $(document.getElementById('exif_dialog'));
	let transformedImageModal = $(document.getElementById('transformed_image_dialog'));
	let exifDataBody = $(document.getElementById('exif_data_body'));
	let transformedImage = $(document.getElementById('transformed_image'));
	let height = $(document.getElementById('height'));
	let width = $(document.getElementById('width'));
	let fitTo = $(document.getElementById('fit_to'));

	let settings = {
			get mode() {
				return localStorage.getItem('mode') || 'upload';
			},
			set mode(mode) {
				localStorage.setItem('mode', mode);
			},
			get lastUsedUrl() {
				return localStorage.getItem('last_used_url') || '';
			},
			set lastUsedUrl(lastUsedUrl) {
				localStorage.setItem('last_used_url', lastUsedUrl);
			},
			get height() {
				return localStorage.getItem('height') || '';
			},
			set height(height) {
				localStorage.setItem('height', height);
			},
			get width() {
				return localStorage.getItem('width') || '';
			},
			set width(width) {
				localStorage.setItem('width', width);
			},
			get fitTo() {
				return localStorage.getItem('fit_to') || '';
			},
			set fitTo(fitTo) {
				localStorage.setItem('fit_to', fitTo);
			}
	}
	
	let getFilename = (url) => {
		if (url) {
			var m = url.toString().match(/.*\/(.+?)\./);
			if (m && m.length > 1) {
				return m[1];
			}
		}
		return '';
	}
	
	$('input[name=image_type]').on('change', (e) => {
		switch (e.currentTarget.id) {
		case 'image_type_url':
			imageUrlInput.removeClass('d-none');
			imageFileInput.parent().addClass('d-none');
			fileUpload = null;
			imageFileLabel.text('Choose image');
			imageFileInput.val('');
			settings.mode = 'url';
			break;
		case 'image_type_file':
			imageUrlInput.addClass('d-none');
			imageFileInput.parent().removeClass('d-none');
			settings.mode = 'upload';
			break;
		default:
			// nothing
		}
	});
	
	let fileUpload;
	let filename;
	
	imageFileInput.on('change', (e) => {
		if (e.target.files.length > 0) {
			let reader = new FileReader();
			reader.onload = function() {
				fileUpload = new Uint8Array(this.result);
			}
			reader.readAsArrayBuffer(e.target.files[0]);
			imageFileLabel.text(e.target.files[0].name);
			filename = e.target.files[0].name;
		}
	});
	
	imageUrlInput.on('change', (e) => {
		settings.lastUsedUrl = e.target.value;
	});
	
	let withFilePost = (method, done, parameters) => {
		imageFileInput.removeClass('is-invalid');
		imageUrlInput.removeClass('is-invalid');
		if (settings.mode === 'upload') {
			if (!fileUpload) {
				return;
			}
			$.post({
				url: method,
				data: fileUpload,
			    processData: false,
			    contentType: false,
			    error: () => {
			    	imageFileInput.addClass('is-invalid');
			    }
			}).done(done);
		} else if (settings.mode === 'url') {
			filename = getFilename(imageUrlInput.val());
			$.get({
				url: method,
				data: { imagePath: imageUrlInput.val() },
				dataType: 'json',
				error: () => {
					imageUrlInput.addClass('is-invalid');
				}
			}).done(done);
		}
	}
	
	$(document.getElementById('transform_image')).on('click', (e) => {
		e.preventDefault();
		withFilePost('transform?' + $('#height, #width, #fit_to').serialize(), (data) => {
			transformedImage.html(templates.transformedImage($.extend(data, {name: filename})));
			transformedImageModal.modal('show');
		});
	});
	
	$(document.getElementById('read_exif')).on('click', (e) => {
		e.preventDefault();
		withFilePost('metadata', (data) => {
			exifDataBody.children().remove();
			$.each(data, (i, exif) => {
				exifDataBody.append(templates.exifRow(exif));
			});
			exifModal.modal('show');
		});
	});
	
	height.on('change', (e) => {
		settings.height = e.currentTarget.value;
	});
	
	width.on('change', (e) => {
		settings.width = e.currentTarget.value;
	});
	
	fitTo.on('change', (e) => {
		settings.fitTo = e.currentTarget.value;
	});
	
	switch(settings.mode) {
	case 'url':
		$(document.getElementById('image_type_url')).trigger('click');
		break;
	case 'upload':
	default:
		$(document.getElementById('image_type_file')).trigger('click');
	}
	
	imageUrlInput.val(settings.lastUsedUrl);
	height.val(settings.height);
	width.val(settings.width);
	fitTo.val(settings.fitTo);
	
	let templates = {
			exifRow: _.template('<tr><td><%-name%></td><td><%-value%></td></tr>'),
			transformedImage: _.template('<a href="data:<%-mediaType%>;base64,<%-image%>" download="<%-name%>"><img src="data:<%-mediaType%>;base64,<%-image%>" alt="<%-name%>" /></a>')
	};
}(jQuery));