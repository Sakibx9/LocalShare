package com.localshare.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class HttpResponse {
    private final int status;
    private final String contentType;
    private final byte[] bodyBytes;  // for small responses
    private final File bodyFile;     // for large file streaming
    private final String fileName;

    public HttpResponse(int status, String contentType, byte[] body) {
        this.status = status;
        this.contentType = contentType;
        this.bodyBytes = body;
        this.bodyFile = null;
        this.fileName = null;
    }

    private HttpResponse(int status, String contentType, File file, String fileName) {
        this.status = status;
        this.contentType = contentType;
        this.bodyBytes = null;
        this.bodyFile = file;
        this.fileName = fileName;
    }

    public static HttpResponse text(int status, String body) {
        return new HttpResponse(status, "text/plain", body.getBytes());
    }

    public static HttpResponse html(String body) {
        return new HttpResponse(200, "text/html; charset=utf-8", body.getBytes());
    }

    public static HttpResponse file(File f, String mime, String name) {
        return new HttpResponse(200, mime, f, name);
    }

    public void write(OutputStream out) throws IOException {
        long contentLength = bodyFile != null ? bodyFile.length() : bodyBytes.length;

        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(status).append(" ").append(statusText()).append("\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Content-Length: ").append(contentLength).append("\r\n");
        header.append("Connection: close\r\n");
        header.append("Access-Control-Allow-Origin: *\r\n");
        if (fileName != null) {
            header.append("Content-Disposition: attachment; filename=\"")
                  .append(fileName.replace("\"", "\\\"")).append("\"\r\n");
        }
        header.append("\r\n");

        out.write(header.toString().getBytes("UTF-8"));

        if (bodyFile != null) {
            // Stream file with large buffer for maximum speed
            byte[] buf = new byte[256 * 1024];
            try (FileInputStream fis = new FileInputStream(bodyFile)) {
                int n;
                while ((n = fis.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
        } else {
            out.write(bodyBytes);
        }
        out.flush();
    }

    private String statusText() {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            default: return "Unknown";
        }
    }
}
