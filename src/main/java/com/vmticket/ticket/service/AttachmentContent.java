package com.vmticket.ticket.service;

import java.nio.file.Path;

public record AttachmentContent(Path path, String contentType, String fileName) {
}
