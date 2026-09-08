package io.hearth.inbox;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * The raw message on disk, exactly as it arrived.
 *
 * <b>One file per message and no second copy of anything.</b> The alternative is to pull every part
 * out at delivery and store each as its own blob, which doubles the disk a photograph occupies and
 * makes "show me the original" impossible to answer honestly. Keeping the octets and re-reading
 * them when somebody asks for part `1.2` costs a file read on a click nobody makes often.
 *
 * <b>The path is computed from a long, never from anything a sender chose.</b> Same rule as
 * uploads: a filename in a message is a claim, and the only thing between it and a path traversal
 * is that it never reaches one. The subject, the sender and the attachment names are all absent
 * from the layout by construction.
 *
 * <b>Written atomically.</b> A crash between delivery and the write leaves no file rather than half
 * of one, and a half-written message is worse than a missing one because it parses.
 */
public class MessageFiles {
  /** how many directories the messages spread across, so no one of them holds tens of thousands */
  private static final int BUCKETS = 100;

  private final File root;

  public MessageFiles(File root) {
    this.root = root;
  }

  /** `<root>/mail/store/<id % 100>/<id>.eml` */
  public File fileFor(long id) {
    return new File(new File(root, "store" + File.separator + (id % BUCKETS)), id + ".eml");
  }

  public void write(long id, byte[] raw) throws IOException {
    File target = fileFor(id);
    File directory = target.getParentFile();
    if (directory != null && !directory.isDirectory() && !directory.mkdirs()
        && !directory.isDirectory()) {
      throw new IOException("could not make " + directory);
    }
    File staging = new File(directory, id + ".part");
    Files.write(staging.toPath(), raw);
    Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
  }

  /** the message, or null when it is not there */
  public byte[] read(long id) {
    File file = fileFor(id);
    if (!file.isFile()) {
      return null;
    }
    try {
      return Files.readAllBytes(file.toPath());
    } catch (IOException ex) {
      // a message whose file has gone is a message with no attachments and no original, which is
      // what the caller will report; it is not a reason to fail the page
      return null;
    }
  }

  /**
   * Delete the file for a message that is being deleted.
   *
   * Silent about a file that is not there. Deleting a message whose file went missing at some point
   * must still delete the message: refusing would leave a row somebody cannot get rid of.
   */
  public void delete(long id) {
    File file = fileFor(id);
    if (file.isFile()) {
      file.delete();
    }
  }

  public boolean has(long id) {
    return fileFor(id).isFile();
  }
}
