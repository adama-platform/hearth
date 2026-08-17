package io.hearth.attach;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Files under `<root>/attachments`, in a shape that does not fall over at scale.
 *
 * <pre>
 *   &lt;root&gt;/attachments/jpg/42/1342.blob
 *                      &lt;ext&gt;/&lt;id % 100&gt;/&lt;id&gt;.blob
 * </pre>
 *
 * <b>Three levels, and each of them earns its place.</b> The extension first, because that is how a
 * person with a shell looks for something and how an operator moves "all the video" to another disk
 * one day. Then the id modulo a hundred, because a directory with a million entries is a directory
 * every tool on the machine is slow in -- `ls`, a backup, a filesystem check -- and a hundred
 * buckets means the ceiling is a hundred times whatever one directory can hold, per kind. Then the
 * id itself as the name, because a file named after what somebody called it is a file named after
 * something they chose, and what they choose includes `../`, a null byte, and a right-to-left
 * override that makes `gpj.exe` read as `exe.jpg`.
 *
 * <b>The path is computed, never parsed.</b> Nothing here takes a path from a request: an id is a
 * long and an extension is checked against a closed table before it arrives, so there is no string
 * from outside anywhere in the resolution. That is the whole reason this is safe, and it is why the
 * one assertion below -- that the resolved file is still inside the root -- is a belt rather than
 * the braces.
 *
 * <b>Writes are atomic.</b> A blob is written to a temporary name in the same directory and moved
 * into place, so a server killed mid-upload leaves either the old file or the new one and never
 * half of a photograph that a browser will render as a grey ribbon.
 */
public class DiskAttachments implements AttachmentStore {
  /** how many buckets one extension is spread across */
  public static final int BUCKETS = 100;

  private final File root;

  public DiskAttachments(File root) {
    this.root = root;
  }

  @Override
  public void put(long id, String extension, byte[] bytes) throws IOException {
    File file = fileFor(id, extension);
    File parent = file.getParentFile();
    if (!parent.isDirectory() && !parent.mkdirs()) {
      throw new IOException("could not create " + parent);
    }
    Path temporary = new File(parent, file.getName() + ".part").toPath();
    Files.write(temporary, bytes);
    Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
  }

  @Override
  public byte[] get(long id, String extension) throws IOException {
    File file = fileFor(id, extension);
    if (!file.isFile()) {
      // a row whose bytes are gone is a 404 and a line in a log, never an exception on a page
      return null;
    }
    return Files.readAllBytes(file.toPath());
  }

  @Override
  public boolean delete(long id, String extension) {
    return fileFor(id, extension).delete();
  }

  @Override
  public String pathOf(long id, String extension) {
    return fileFor(id, extension).getAbsolutePath();
  }

  @Override
  public String describe() {
    return root.getAbsolutePath();
  }

  /**
   * How much is held.
   *
   * A walk of the tree, which is fine for a few thousand files and is only ever asked for by the
   * settings screen. Nothing on a request path calls this.
   */
  @Override
  public long totalBytes() {
    return totalOf(root, 0);
  }

  private static long totalOf(File dir, int depth) {
    File[] children = dir.listFiles();
    if (children == null || depth > 3) {
      return 0;
    }
    long total = 0;
    for (File child : children) {
      total += child.isDirectory() ? totalOf(child, depth + 1) : child.length();
    }
    return total;
  }

  /**
   * The file for one attachment.
   *
   * The extension is cleaned again here even though callers have already checked it. It costs
   * nothing, and the alternative is a method whose safety depends on every caller having done
   * something -- which is the kind of assumption that survives right up until the sixteenth caller.
   */
  File fileFor(long id, String extension) {
    String clean = Kinds.clean(extension);
    if (clean.isEmpty()) {
      clean = "bin";
    }
    File bucket = new File(new File(root, clean), Long.toString(Math.floorMod(id, BUCKETS)));
    return new File(bucket, id + ".blob");
  }
}
