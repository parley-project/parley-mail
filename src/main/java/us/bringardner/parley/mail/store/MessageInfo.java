package us.bringardner.parley.mail.store;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import us.bringardner.parley.files.FileSource;

/**
 * One message of a {@link Mailbox}: its UID, file and IMAP state. The flags are
 * changed only through the mailbox, under its lock.
 */
public final class MessageInfo {

	private final long uid;
	private final String name;
	private final FileSource file;
	private final long internalDate;
	private final long size;
	private final boolean utf8;
	final LinkedHashSet<String> flags = new LinkedHashSet<>();

	MessageInfo(long uid, String name, FileSource file, long internalDate, long size, boolean utf8) {
		this.uid = uid;
		this.name = name;
		this.file = file;
		this.internalDate = internalDate;
		this.size = size;
		this.utf8 = utf8;
	}

	public long getUid() {
		return uid;
	}

	/** The message file's name in the mailbox directory. */
	public String getName() {
		return name;
	}

	public FileSource getFile() {
		return file;
	}

	/** INTERNALDATE, in milliseconds since the epoch. */
	public long getInternalDate() {
		return internalDate;
	}

	/** RFC822.SIZE: the stored size (files are kept with CRLF line ends). */
	public long getSize() {
		return size;
	}

	/** True if the message has UTF-8 headers (RFC 6532), so non-UTF-8 sessions get a surrogate. */
	public boolean isUtf8() {
		return utf8;
	}

	/** A copy of the flags. */
	public Set<String> getFlags(Mailbox mailbox) {
		mailbox.lock();
		try {
			return Collections.unmodifiableSet(new LinkedHashSet<>(flags));
		} finally {
			mailbox.unlock();
		}
	}
}
