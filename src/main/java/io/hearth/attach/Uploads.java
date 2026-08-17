package io.hearth.attach;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.MemoryAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reading a `multipart/form-data` submission, which is the only shape a browser sends a file in.
 *
 * <b>In memory, on purpose, and bounded twice.</b> The request has already been aggregated by the
 * time it reaches here, so the bytes are in the heap either way; writing them to a temporary file
 * so they can be read back would be two more failure modes and a directory to clean up. The size is
 * bounded by the pipeline before the body is buffered at all (see
 * {@link io.hearth.web.UploadGate}) and again here against what the community actually allows.
 *
 * <b>One file per submission.</b> Not a limitation anybody feels -- the upload screen has one box
 * -- and it keeps the ceiling meaningful: ten files under the limit is ten times the limit.
 *
 * <b>Everything a browser says about the file is a claim.</b> The name is kept for display and
 * sanitised where it is used; the declared content type is read and then ignored, because the
 * extension decides. See {@link Kinds}.
 */
public final class Uploads {
  private static final Logger LOG = LoggerFactory.getLogger(Uploads.class);
  /** the most fields one upload form may carry; past this it is not the upload form */
  private static final int MAX_FIELDS = 20;

  private Uploads() {
  }

  /** the file that arrived: what it was called, and its bytes */
  public record File(String filename, String declaredType, byte[] bytes) {
  }

  /** one submission: the file, the fields beside it, and whether it was refused for size */
  public record Received(File file, Map<String, String> fields, boolean tooLarge) {
    public String field(String name) {
      String value = fields.get(name);
      return value == null ? "" : value;
    }
  }

  public static Received of(FullHttpRequest req, int maxBytes) {
    LinkedHashMap<String, String> fields = new LinkedHashMap<>();
    File file = null;
    boolean tooLarge = false;
    HttpPostRequestDecoder decoder = null;
    try {
      // memory only: the aggregator has already read the whole body, so a temporary file would be
      // a copy of something that is already here, plus a directory to clean up afterwards
      decoder = new HttpPostRequestDecoder(new DefaultHttpDataFactory(false), req);
      int seen = 0;
      for (InterfaceHttpData data : decoder.getBodyHttpDatas()) {
        if (seen++ > MAX_FIELDS) {
          break;
        }
        if (data instanceof FileUpload upload) {
          if (file != null) {
            // one file per submission: ten files each under the ceiling is ten times the ceiling
            continue;
          }
          long length = upload.length();
          if (length > maxBytes) {
            tooLarge = true;
            continue;
          }
          file = new File(upload.getFilename(), upload.getContentType(),
              upload.get());
        } else if (data instanceof MemoryAttribute attribute) {
          String value = attribute.getValue();
          fields.put(attribute.getName(),
              value.length() > 4096 ? value.substring(0, 4096) : value);
        }
      }
    } catch (Exception ex) {
      // a body that does not parse is an upload that did not happen, never a page that throws
      LOG.debug("upload-parse-failed", ex);
    } finally {
      if (decoder != null) {
        try {
          decoder.destroy();
        } catch (RuntimeException ex) {
          LOG.debug("upload-cleanup-failed", ex);
        }
      }
    }
    return new Received(file, fields, tooLarge);
  }

  /** what the browser sent, as text, for a field that should have been one */
  static String text(byte[] bytes) {
    return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
  }
}
