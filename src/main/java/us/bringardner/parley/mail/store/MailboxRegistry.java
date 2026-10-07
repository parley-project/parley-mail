package us.bringardner.parley.mail.store;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import us.bringardner.parley.files.FileSource;

/**
 * The open mailboxes of the process, by canonical directory path, so every
 * session (of every ImapServer) that uses a mailbox shares one {@link Mailbox}
 * and sees the others' changes at once. A mailbox is closed when its last user
 * releases it.
 */
public final class MailboxRegistry {

	private static final MailboxRegistry INSTANCE = new MailboxRegistry();

	private final Map<String, Mailbox> open = new HashMap<>();

	public static MailboxRegistry get() {
		return INSTANCE;
	}

	static String keyOf(FileSource dir) throws IOException {
		String key = dir.getCanonicalPath().replace('\\', '/');
		while (key.length() > 1 && key.endsWith("/")) {
			key = key.substring(0, key.length() - 1);
		}
		return key;
	}

	/** Open (or share) the mailbox in a directory. Every call must be matched by {@link #release}. */
	public synchronized Mailbox acquire(FileSource dir) throws IOException {
		String key = keyOf(dir);
		Mailbox m = open.get(key);
		if (m == null || m.isDeleted()) {
			m = Mailbox.open(dir, key);
			open.put(key, m);
		}
		m.refCount++;
		return m;
	}

	public synchronized void release(Mailbox m) {
		if (m == null) {
			return;
		}
		try {
			m.saveIfDirty();
		} catch (IOException e) {
			// the index is written again at the next change
		}
		if (--m.refCount <= 0 && open.get(m.getKey()) == m) {
			open.remove(m.getKey());
		}
	}

	/**
	 * A mailbox directory (and those under it) was deleted or renamed: sessions
	 * that have it selected see every message expunged.
	 */
	public synchronized void invalidate(FileSource dir) throws IOException {
		String key = keyOf(dir);
		List<String> gone = new ArrayList<>();
		for (String k : open.keySet()) {
			if (k.equals(key) || k.startsWith(key + "/")) {
				gone.add(k);
			}
		}
		for (String k : gone) {
			open.remove(k).markDeleted();
		}
	}

	/** Save every open mailbox's index (e.g. at shutdown). */
	public void saveAll() {
		List<Mailbox> list;
		synchronized (this) {
			list = new ArrayList<>(open.values());
		}
		for (Mailbox m : list) {
			try {
				m.saveIfDirty();
			} catch (IOException e) {
				// best effort
			}
		}
	}
}
