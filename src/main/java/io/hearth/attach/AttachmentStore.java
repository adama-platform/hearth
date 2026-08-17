package io.hearth.attach;

import java.io.IOException;

/**
 * Where the bytes live, which is deliberately a seam.
 *
 * A community of two hundred people uploading photographs is a directory on a disk, and that is the
 * one implementation here. The seam exists because the next answer is somebody else's -- an object
 * store, a shared volume, a second machine -- and the difference between "we can move this" and "we
 * cannot" is whether every caller went through one interface on the first day. It is three methods
 * because that is all storage is.
 *
 * <b>Nothing here knows what an attachment means.</b> No permissions, no types, no cache: an id and
 * an extension go in, bytes come back. The row in the database is the record and this is the
 * cupboard, and keeping the two apart is what makes "the file is missing" and "the row is missing"
 * two different, diagnosable problems.
 */
public interface AttachmentStore {
  /** write the bytes for one attachment, replacing anything already there */
  void put(long id, String extension, byte[] bytes) throws IOException;

  /** the bytes, or null when there are none; a missing file is not an exception */
  byte[] get(long id, String extension) throws IOException;

  /** remove them; returns false when there was nothing to remove */
  boolean delete(long id, String extension);

  /** where one attachment lives, for a report and for somebody with a shell */
  String pathOf(long id, String extension);

  /** what an operator needs to see on the settings screen */
  String describe();

  /** how many bytes are held, for the same screen; -1 when it would be expensive to say */
  long totalBytes();
}
