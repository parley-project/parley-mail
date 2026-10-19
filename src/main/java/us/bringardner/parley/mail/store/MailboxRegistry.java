package us.bringardner.parley.mail.store;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.lock.LockDomain;

/**
 * The open mailboxes of the process, by canonical directory path, so every
 * session (of every ImapServer) that uses a mailbox shares one {@link Mailbox}
 * and sees the others' changes at once. A mailbox is closed when its last user
 * releases it.
 */
public final class MailboxRegistry {

	private static final MailboxRegistry INSTANCE = new MailboxRegistry();

	private final Map<String, Mailbox> open = new HashMap<>();
	/** Lock domains by their root's key: a mailbox under one locks through it. */
	private final Map<String, LockDomain> domains = new HashMap<>();

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

	/** Mailboxes under the domain's root lock through it (see {@link MailLocks}). */
	public synchronized void useLocks(LockDomain d) throws IOException {
		domains.put(keyOf(d.root()), d);
	}

	/** The domain no longer applies (its last server stopped). */
	public synchronized void stopUsingLocks(LockDomain d) {
		domains.values().remove(d);
	}

	/**
	 * The lock domain a directory is in (the one whose root it is in, the deepest if several),
	 * or null: no locks reach beyond this JVM.
	 */
	public synchronized LockDomain domainOf(FileSource dir) throws IOException {
		return domainOf(keyOf(dir));
	}

	private LockDomain domainOf(String key) {
		LockDomain best = null;
		int bestLen = -1;
		for (Map.Entry<String, LockDomain> e : domains.entrySet()) {
			String root = e.getKey();
			if ((key.equals(root) || key.startsWith(root.endsWith("/") ? root : root + "/")) && root.length() > bestLen) {
				best = e.getValue();
				bestLen = root.length();
			}
		}
		return best;
	}

	/**
	 * A lock name for a directory in a domain: its path from the domain's root, the same on every
	 * server whatever the root's path there.
	 */
	public static String lockName(String prefix, LockDomain d, FileSource dir) throws IOException {
		String root = keyOf(d.root());
		String key = keyOf(dir);
		String rel = key.length() > root.length() ? key.substring(root.length() + (root.endsWith("/") ? 0 : 1)) : "";
		return prefix + "/" + rel;
	}

	/** Open (or share) the mailbox in a directory. Every call must be matched by {@link #release}. */
	public synchronized Mailbox acquire(FileSource dir) throws IOException {
		String key = keyOf(dir);
		Mailbox m = open.get(key);
		if (m == null || m.isDeleted()) {
			LockDomain d = domainOf(key);
			m = Mailbox.open(dir, key, d, d == null ? null : lockName("mailbox", d, dir));
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
