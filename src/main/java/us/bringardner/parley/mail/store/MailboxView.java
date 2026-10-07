package us.bringardner.parley.mail.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * One session's view of a selected {@link Mailbox}: the message sequence
 * numbers it has been told about, and the changes made by others that it hasn't
 * been told about yet (RFC 9051 section 7.4 and 7.5).
 * <p>
 * Message sequence numbers only change when the session is sent EXPUNGE
 * responses, which is not allowed during FETCH, STORE and SEARCH (section 7.5.1),
 * so expunges are kept pending until {@link #collectUpdates(boolean)} is called
 * with {@code allowExpunge}. All state is guarded by the mailbox lock.
 */
public final class MailboxView {

	/** What a session must send: EXPUNGE, then EXISTS, then FETCH FLAGS responses. */
	public static final class Updates {
		/** Message sequence numbers to send in EXPUNGE responses, in sending order. */
		public final List<Integer> expunged = new ArrayList<>();
		/** The new message count, or -1 if it didn't change. */
		public int exists = -1;
		/** Messages whose flags changed: message sequence number and UID. */
		public final List<long[]> flagsChanged = new ArrayList<>();

		public boolean isEmpty() {
			return expunged.isEmpty() && exists < 0 && flagsChanged.isEmpty();
		}
	}

	private final Mailbox mailbox;
	private final boolean readOnly;
	private final ArrayList<Long> uids = new ArrayList<>();
	private final TreeSet<Long> pendingExpunges = new TreeSet<>();
	private final Set<Long> pendingFlags = new LinkedHashSet<>();
	private long knownMax;
	private boolean closed;

	MailboxView(Mailbox mailbox, boolean readOnly) {
		this.mailbox = mailbox;
		this.readOnly = readOnly;
		mailbox.lock();
		try {
			for (MessageInfo m : mailbox.getMessages()) {
				uids.add(m.getUid());
				knownMax = m.getUid();
			}
			knownMax = Math.max(knownMax, mailbox.getUidNext() - 1);
			mailbox.addView(this);
		} finally {
			mailbox.unlock();
		}
	}

	public Mailbox getMailbox() {
		return mailbox;
	}

	/** EXAMINE, or no WRITE permission. */
	public boolean isReadOnly() {
		return readOnly;
	}

	public void close() {
		mailbox.lock();
		try {
			closed = true;
			mailbox.removeView(this);
		} finally {
			mailbox.unlock();
		}
	}

	public boolean isClosed() {
		return closed;
	}

	// called by the mailbox, under its lock

	void expunged(long uid) {
		if (Collections.binarySearch(uids, uid) >= 0) {
			pendingExpunges.add(uid);
		}
		pendingFlags.remove(uid);
	}

	void flagsChanged(long uid) {
		if (Collections.binarySearch(uids, uid) >= 0) {
			pendingFlags.add(uid);
		}
	}

	void added() {
		// new UIDs are found from knownMax when updates are collected
	}

	// ------------------------------------------------------------------ session side

	/** The number of messages the session knows (the last EXISTS). */
	public int size() {
		mailbox.lock();
		try {
			return uids.size();
		} finally {
			mailbox.unlock();
		}
	}

	/** The UID of a message sequence number (1-based), or -1. */
	public long uid(int msn) {
		mailbox.lock();
		try {
			return msn < 1 || msn > uids.size() ? -1 : uids.get(msn - 1);
		} finally {
			mailbox.unlock();
		}
	}

	/** The message sequence number of a UID, or -1. */
	public int msn(long uid) {
		mailbox.lock();
		try {
			int i = Collections.binarySearch(uids, uid);
			return i < 0 ? -1 : i + 1;
		} finally {
			mailbox.unlock();
		}
	}

	/** A copy of the UIDs in sequence order. */
	public List<Long> uids() {
		mailbox.lock();
		try {
			return new ArrayList<>(uids);
		} finally {
			mailbox.unlock();
		}
	}

	/** The largest UID the session knows, or 0. */
	public long maxUid() {
		mailbox.lock();
		try {
			return uids.isEmpty() ? 0 : uids.get(uids.size() - 1);
		} finally {
			mailbox.unlock();
		}
	}

	/** True if another session expunged the message and this one hasn't been told. */
	public boolean isExpunged(long uid) {
		mailbox.lock();
		try {
			return pendingExpunges.contains(uid);
		} finally {
			mailbox.unlock();
		}
	}

	/** The message, or null if it was expunged. */
	public MessageInfo message(long uid) {
		MessageInfo m = mailbox.get(uid);
		return m;
	}

	/** Forget a pending flag change (the session is sending the flags anyway). */
	public void flagsSent(long uid) {
		mailbox.lock();
		try {
			pendingFlags.remove(uid);
		} finally {
			mailbox.unlock();
		}
	}

	/**
	 * Take the changes the session must be told about and apply them to the view.
	 *
	 * @param allowExpunge false during FETCH, STORE and SEARCH (and NOOP is true)
	 */
	public Updates collectUpdates(boolean allowExpunge) {
		Updates u = new Updates();
		mailbox.lock();
		try {
			if (allowExpunge && !pendingExpunges.isEmpty()) {
				// send in descending order, so no number changes before it is sent
				for (Long uid : pendingExpunges.descendingSet()) {
					int i = Collections.binarySearch(uids, uid);
					if (i >= 0) {
						uids.remove(i);
						u.expunged.add(i + 1);
					}
				}
				pendingExpunges.clear();
			}
			List<Long> added = mailbox.uidsAfter(knownMax);
			if (!added.isEmpty()) {
				uids.addAll(added);
				knownMax = added.get(added.size() - 1);
			}
			if (!added.isEmpty() || !u.expunged.isEmpty()) {
				u.exists = uids.size();
			}
			for (Long uid : pendingFlags) {
				int i = Collections.binarySearch(uids, uid);
				if (i >= 0 && !pendingExpunges.contains(uid)) {
					u.flagsChanged.add(new long[] {i + 1, uid});
				}
			}
			pendingFlags.clear();
		} finally {
			mailbox.unlock();
		}
		return u;
	}
}
