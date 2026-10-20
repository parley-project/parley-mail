package us.bringardner.parley.mail.store;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.io.IoUtils;
import us.bringardner.parley.mail.Downgrader;
import us.bringardner.parley.mail.Message;
import us.bringardner.parley.net.lock.LockDomain;
import us.bringardner.parley.net.lock.LockGroup;
import us.bringardner.parley.net.lock.LockLostException;
import us.bringardner.parley.net.lock.Mode;
import us.bringardner.parley.net.lock.NamedLock;
import us.bringardner.parley.net.lock.Sharing;

/**
 * An IMAP mailbox: a directory of message files (one message per file, as in the
 * POP3 maildrop) plus a hidden index, {@code .imap-index}, that keeps each
 * message's UID, flags and INTERNALDATE, and the mailbox's UIDVALIDITY and
 * UIDNEXT (RFC 9051 section 2.3.1.1).
 * <p>
 * One Mailbox object exists per directory in a server (see
 * {@link MailboxRegistry}); every session that selects the mailbox shares it and
 * is told about changes through its {@link MailboxView}. Files added or removed
 * by others (POP3, local delivery) are found by {@link #refresh(boolean)}.
 * Files are kept with CRLF line ends, so stored sizes are the sizes IMAP sends.
 * Names starting with "." are ignored, so a message being written is never seen
 * half written.
 * <p>
 * <b>Several servers.</b> A mailbox in a {@link LockDomain} (see {@link MailLocks}) makes every
 * change under the domain's exclusive lock on its name: it first catches up with the index another
 * server saved (each save has a random id, checked with UIDVALIDITY, so a mailbox deleted and made
 * again is never taken for the one this server knew), makes the change, and saves the index before
 * the lock goes. UIDs are never given twice: UIDNEXT only goes up, whatever the stored index says.
 * Flag changes ({@link #storeFlags}) are made in memory and saved by the next change (or
 * {@link #saveIfDirty()}), replayed on the latest index. The index is replaced in one step where
 * the store can ({@code atomicReplace}); elsewhere the previous one is kept as
 * {@code .imap-index.old} until the new one is in place. Without a domain (or with sharing off),
 * only this server's sessions are kept in step, as before.
 */
public final class Mailbox {

	public static final String INDEX = ".imap-index";
	static final String INDEX_OLD = ".imap-index.old";
	static final String CACHE_DIR = ".imap-cache";
	static final String HEADER = "BJLIMAP 1";

	/** Refresh from the directory at most this often (ms) unless forced. */
	private static final long REFRESH_INTERVAL = 1000;

	/** This JVM's last UIDVALIDITY (package-private for the tests, which play another JVM with it). */
	static final AtomicLong LAST_UIDVALIDITY = new AtomicLong();
	private static final AtomicLong SEQUENCE = new AtomicLong();
	private static final SecureRandom RANDOM = new SecureRandom();

	public enum FlagOp {
		SET, ADD, REMOVE
	}

	private final FileSource dir;
	private final String key;
	private final ReentrantLock lock = new ReentrantLock();
	/** The domain whose locks reach the other servers, or null. */
	private final LockDomain domain;
	private final String lockName;
	/** Whether other servers may change it (a domain that shares the store). */
	private final boolean shared;

	private long uidValidity;
	private long uidNext = 1;
	private String specialUse;
	/** Which save of the index this object last loaded or made (random, so unique), or null. */
	private String saveId;
	private long generation;
	private final TreeMap<Long, MessageInfo> messages = new TreeMap<>();
	private final Map<String, MessageInfo> byName = new HashMap<>();
	private final Set<String> notMessages = new HashSet<>();
	private final List<MailboxView> views = new CopyOnWriteArrayList<>();
	/** Flag changes not saved yet, by UID, in order: replayed on the latest index before saving. */
	private final Map<Long, List<FlagChange>> pendingFlags = new LinkedHashMap<>();
	private boolean dirty;
	private long lastRefresh;
	private volatile boolean deleted;
	/** The lock held while a change runs (guarded by {@link #lock}). */
	private NamedLock changing;
	int refCount;

	private Mailbox(FileSource dir, String key, LockDomain domain, String lockName) {
		this.dir = dir;
		this.key = key;
		this.domain = domain;
		this.lockName = lockName;
		this.shared = domain != null && domain.sharing() != Sharing.OFF;
	}

	/** Open a mailbox directory, reading its index (or creating one) and the files. */
	static Mailbox open(FileSource dir, String key) throws IOException {
		return open(dir, key, null, null);
	}

	/**
	 * Open a mailbox directory that locks through a domain (null: this JVM only).
	 * @param lockName its name in the domain
	 */
	static Mailbox open(FileSource dir, String key, LockDomain domain, String lockName) throws IOException {
		Mailbox m = new Mailbox(dir, key, domain, lockName);
		m.change(() -> {
			m.loadIndex();
			m.scan();
			return null;
		}, false);
		return m;
	}

	public FileSource getDirectory() {
		return dir;
	}

	String getKey() {
		return key;
	}

	/** @return the lock domain, or null */
	LockDomain getDomain() {
		return domain;
	}

	/** @return its lock name in the domain, or null */
	String getLockName() {
		return lockName;
	}

	void lock() {
		lock.lock();
	}

	void unlock() {
		lock.unlock();
	}

	// ------------------------------------------------------------------ changes, under the lock

	private interface Change<T> {
		T run() throws IOException;
	}

	private <T> T change(Change<T> work) throws IOException {
		return change(work, true);
	}

	/**
	 * Run a change: under the domain's lock on this mailbox (if any), then this object's lock;
	 * catch up with the store first, save after.
	 * @param sync whether to catch up first (not while opening: there is nothing loaded yet)
	 */
	private <T> T change(Change<T> work, boolean sync) throws IOException {
		if (lock.isHeldByCurrentThread() && changing == null) {
			throw new IllegalStateException("A mailbox change can't start while its lock is held for reading");
		}
		NamedLock l = domain == null ? null : domain.lock(lockName, Mode.EXCLUSIVE, domain.options().timeout());
		try {
			lock();
			NamedLock outer = changing;
			changing = l != null ? l : outer;
			try {
				if (sync && shared) {
					syncWithStore();
				}
				T ret = work.run();
				commit();
				return ret;
			} finally {
				changing = outer;
				unlock();
			}
		} finally {
			if (l != null) {
				l.close();
			}
		}
	}

	/**
	 * Before each change to the mailbox's files (a delete, a move): the domain lock must still be
	 * held. This asks only this JVM ({@link NamedLock#isValid()}): a lease holder stops trusting
	 * its lease before any other server may take it over, so one that was paused finds it lost
	 * here rather than removing a message the server that took over still indexes.
	 */
	private static void ensureHeld(NamedLock l) throws IOException {
		if (l != null && !l.isValid()) {
			throw new LockLostException("Lost the lock " + l.name() + " while changing the mailbox; nothing more is changed");
		}
	}

	/** Save the index now if it changed: called with the lock held, inside a change. */
	private void commit() throws IOException {
		if (!dirty || deleted || !dir.exists()) {
			return;
		}
		writeIndex();
		dirty = false;
		pendingFlags.clear();
	}

	// ------------------------------------------------------------------ index

	/** The store's last UIDVALIDITY, in the domain's root: {@value}. */
	static final String UIDVALIDITY_FILE = ".mail-uidvalidity";
	/** The domain lock under which it is read and written. */
	static final String UIDVALIDITY_LOCK = "uidvalidity";

	/**
	 * A UIDVALIDITY never given before in this store: the time in seconds, but always more than
	 * the last one given. With a lock domain, the last one is kept in the store (under the domain
	 * lock {@value #UIDVALIDITY_LOCK}), so a mailbox deleted and made again by another server (or
	 * after a restart) in the same second still gets a new one, and every server sees it was
	 * recreated. Called inside a change.
	 */
	private long newUidValidity() throws IOException {
		long now = System.currentTimeMillis() / 1000;
		if (domain == null) {
			return LAST_UIDVALIDITY.updateAndGet(last -> Math.max(now, last + 1)) & 0xFFFFFFFFL;
		}
		try (NamedLock l = domain.lock(UIDVALIDITY_LOCK, Mode.EXCLUSIVE, domain.options().timeout())) {
			FileSource f = domain.root().getChild(UIDVALIDITY_FILE);
			long stored = 0;
			if (f.exists()) {
				try (BufferedReader r = new BufferedReader(new InputStreamReader(f.getInputStream(), StandardCharsets.UTF_8))) {
					String line = r.readLine();
					stored = line == null ? 0 : Long.parseLong(line.trim());
				} catch (NumberFormatException e) {
					stored = 0; // damaged: the time and this JVM's last still keep it new
				}
			}
			long floor = stored;
			long v = LAST_UIDVALIDITY.updateAndGet(last -> Math.max(now, Math.max(last, floor) + 1));
			FileSource tmp = domain.root().getChild(tmpName(UIDVALIDITY_FILE.substring(1)));
			try (OutputStream out = tmp.getOutputStream()) {
				out.write((v + "\n").getBytes(StandardCharsets.UTF_8));
			}
			boolean done = false;
			try {
				l.checkValid();
				done = tmp.renameTo(f, true);
			} finally {
				if (!done) {
					tmp.delete();
				}
			}
			if (!done) {
				throw new IOException("Can't record the UIDVALIDITY in " + f.getAbsolutePath());
			}
			return v & 0xFFFFFFFFL;
		}
	}

	private static String newSaveId() {
		return Long.toHexString(RANDOM.nextLong()) + Long.toHexString(System.nanoTime());
	}

	private static String tmpName(String base) {
		return "." + base + "." + Long.toHexString(RANDOM.nextLong() & Long.MAX_VALUE) + ".tmp";
	}

	/** What an index file holds. */
	private static final class IndexData {
		long uidValidity;
		long uidNext = 1;
		String specialUse;
		String saveId;
		long generation;
		final TreeMap<Long, MessageInfo> messages = new TreeMap<>();
	}

	/** The index file to read: the index, or the previous one while a replace is under way. */
	private FileSource indexFile() throws IOException {
		FileSource f = dir.getChild(INDEX);
		if (f.exists()) {
			return f;
		}
		FileSource old = dir.getChild(INDEX_OLD);
		return old.exists() ? old : null;
	}

	private IndexData readIndex(FileSource f) throws IOException {
		IndexData d = new IndexData();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(f.getInputStream(), StandardCharsets.UTF_8))) {
			String line = r.readLine();
			if (!HEADER.equals(line)) {
				throw new IOException("Not an IMAP index: " + f.getAbsolutePath());
			}
			while ((line = r.readLine()) != null) {
				String[] p = line.split("\t", -1);
				switch (p[0]) {
				case "VERSION":
					d.generation = Long.parseLong(p[1]);
					d.saveId = p[2];
					break;
				case "UIDVALIDITY":
					d.uidValidity = Long.parseLong(p[1]);
					break;
				case "UIDNEXT":
					d.uidNext = Long.parseLong(p[1]);
					break;
				case "SPECIALUSE":
					d.specialUse = p[1].isEmpty() ? null : p[1];
					break;
				case "M":
					if (p.length >= 7) {
						long uid = Long.parseLong(p[1]);
						MessageInfo info = new MessageInfo(uid, p[6], dir.getChild(p[6]), Long.parseLong(p[2]),
								Long.parseLong(p[3]), "1".equals(p[4]));
						if (!p[5].isEmpty()) {
							info.flags.addAll(Arrays.asList(p[5].split(" ")));
						}
						d.messages.put(uid, info);
						d.uidNext = Math.max(d.uidNext, uid + 1);
					}
					break;
				default:
					// unknown lines are ignored (a newer version may add some)
				}
			}
		} catch (RuntimeException e) {
			throw new IOException("Damaged IMAP index " + f.getAbsolutePath(), e);
		}
		return d;
	}

	/** The saved index's id, from its first lines, or null if there is no index (or no id). */
	private String storedSaveId() throws IOException {
		FileSource f = indexFile();
		if (f == null) {
			return null;
		}
		try (BufferedReader r = new BufferedReader(new InputStreamReader(f.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			int n = 0;
			while ((line = r.readLine()) != null && n++ < 4) {
				if (line.startsWith("VERSION\t")) {
					String[] p = line.split("\t", -1);
					return p.length > 2 ? p[2] : null;
				}
			}
		} catch (java.io.FileNotFoundException gone) {
			return null;
		}
		return null;
	}

	private void loadIndex() throws IOException {
		FileSource f = indexFile();
		if (f == null) {
			uidValidity = newUidValidity();
			uidNext = 1;
			dirty = true;
			return;
		}
		IndexData d = readIndex(f);
		take(d);
		if (uidValidity <= 0) {
			uidValidity = newUidValidity();
			dirty = true;
		}
	}

	private void take(IndexData d) {
		uidValidity = d.uidValidity;
		uidNext = Math.max(uidNext, d.uidNext);
		specialUse = d.specialUse;
		saveId = d.saveId;
		generation = d.generation;
		messages.clear();
		byName.clear();
		for (MessageInfo m : d.messages.values()) {
			messages.put(m.getUid(), m);
			byName.put(m.getName(), m);
		}
	}

	/**
	 * Catch up with what another server saved, telling the views what changed; called under the
	 * lock at the start of a change. A mailbox recreated meanwhile (another UIDVALIDITY) is taken
	 * as new: the views are told it was recreated, not about its messages one by one.
	 */
	private void syncWithStore() throws IOException {
		if (deleted) {
			return;
		}
		if (!dir.exists()) {
			markDeletedLocked();
			return;
		}
		String stored = storedSaveId();
		if (stored == null || stored.equals(saveId)) {
			return; // nothing saved by anyone else since
		}
		IndexData d = readIndex(indexFile());
		if (d.uidValidity != uidValidity) {
			for (MailboxView v : views) {
				v.recreated();
			}
			uidNext = 1; // a new mailbox: its own UIDs
			take(d);
			pendingFlags.clear();
			dirty = false;
			return;
		}
		Map<Long, MessageInfo> before = new HashMap<>(messages);
		take(d); // uidNext = max(ours, stored): a UID this server gave is never given again
		for (Map.Entry<Long, MessageInfo> e : before.entrySet()) {
			MessageInfo now = messages.get(e.getKey());
			if (now == null) {
				deleteCache(e.getValue());
				for (MailboxView v : views) {
					v.expunged(e.getKey());
				}
			} else if (!now.flags.equals(e.getValue().flags)) {
				for (MailboxView v : views) {
					v.flagsChanged(e.getKey());
				}
			}
		}
		boolean added = false;
		for (Long uid : messages.keySet()) {
			if (!before.containsKey(uid)) {
				added = true;
			}
		}
		if (added) {
			for (MailboxView v : views) {
				v.added();
			}
		}
		// this server's flag changes not saved yet go on top of the latest index
		dirty = false;
		for (Map.Entry<Long, List<FlagChange>> e : pendingFlags.entrySet()) {
			MessageInfo m = messages.get(e.getKey());
			if (m != null) {
				for (FlagChange c : e.getValue()) {
					if (apply(m, c.op, c.flags)) {
						dirty = true;
						for (MailboxView v : views) {
							v.flagsChanged(m.getUid());
						}
					}
				}
			}
		}
	}

	/** Write the index (under the lock): a new save id; replaced in one step where the store can. */
	private void writeIndex() throws IOException {
		String id = newSaveId();
		long gen = generation + 1;
		FileSource tmp = dir.getChild(tmpName(INDEX));
		try {
			try (Writer w = new OutputStreamWriter(IoUtils.buffered(tmp.getOutputStream()), StandardCharsets.UTF_8)) {
				w.write(HEADER + "\n");
				w.write("VERSION\t" + gen + "\t" + id + "\n");
				w.write("UIDVALIDITY\t" + uidValidity + "\n");
				w.write("UIDNEXT\t" + uidNext + "\n");
				if (specialUse != null) {
					w.write("SPECIALUSE\t" + specialUse + "\n");
				}
				for (MessageInfo m : messages.values()) {
					w.write("M\t" + m.getUid() + "\t" + m.getInternalDate() + "\t" + m.getSize() + "\t"
							+ (m.isUtf8() ? "1" : "0") + "\t" + String.join(" ", m.flags) + "\t" + m.getName() + "\n");
				}
			}
			if (changing != null) {
				changing.checkValid(); // a server that lost its lock writes nothing
			}
			FileSource index = dir.getChild(INDEX);
			if (dir.getCapabilities().atomicReplace()) {
				if (!tmp.renameTo(index, true)) {
					throw new IOException("Can't replace " + index.getAbsolutePath());
				}
			} else {
				// no one-step replace: the previous index stands in (.old) while the new one goes in
				FileSource old = dir.getChild(INDEX_OLD);
				if (old.exists()) {
					old.delete();
				}
				if (index.exists() && !index.renameTo(old)) {
					throw new IOException("Can't replace " + index.getAbsolutePath());
				}
				if (!tmp.renameTo(index)) {
					old.renameTo(index); // put the previous one back
					throw new IOException("Can't write " + index.getAbsolutePath());
				}
				if (old.exists()) {
					old.delete();
				}
			}
			saveId = id;
			generation = gen;
		} finally {
			if (tmp.exists()) {
				tmp.delete();
			}
		}
	}

	/**
	 * Save what changed (flag changes in memory): with other servers, under the lock and on top of
	 * the latest index.
	 */
	public void saveIfDirty() throws IOException {
		lock();
		try {
			if (!dirty || deleted) {
				return;
			}
		} finally {
			unlock();
		}
		change(() -> null);
	}

	// ------------------------------------------------------------------ state

	public long getUidValidity() {
		lock();
		try {
			return uidValidity;
		} finally {
			unlock();
		}
	}

	public long getUidNext() {
		lock();
		try {
			return uidNext;
		} finally {
			unlock();
		}
	}

	/** The RFC 6154 special-use attribute (e.g. "\\Sent"), or null. */
	public String getSpecialUse() {
		lock();
		try {
			return specialUse;
		} finally {
			unlock();
		}
	}

	public void setSpecialUse(String attribute) throws IOException {
		change(() -> {
			specialUse = attribute;
			dirty = true;
			return null;
		});
	}

	public boolean isDeleted() {
		return deleted;
	}

	/** The messages in UID order. */
	public List<MessageInfo> getMessages() {
		lock();
		try {
			return new ArrayList<>(messages.values());
		} finally {
			unlock();
		}
	}

	public MessageInfo get(long uid) {
		lock();
		try {
			return messages.get(uid);
		} finally {
			unlock();
		}
	}

	/** UIDs greater than {@code uid}, ascending. */
	public List<Long> uidsAfter(long uid) {
		lock();
		try {
			return new ArrayList<>(messages.tailMap(uid, false).keySet());
		} finally {
			unlock();
		}
	}

	public int count() {
		lock();
		try {
			return messages.size();
		} finally {
			unlock();
		}
	}

	public int countWithout(String flag) {
		lock();
		try {
			int n = 0;
			for (MessageInfo m : messages.values()) {
				if (!hasFlag(m.flags, flag)) {
					n++;
				}
			}
			return n;
		} finally {
			unlock();
		}
	}

	public int countWith(String flag) {
		return count() - countWithout(flag);
	}

	public long totalSize() {
		lock();
		try {
			long n = 0;
			for (MessageInfo m : messages.values()) {
				n += m.getSize();
			}
			return n;
		} finally {
			unlock();
		}
	}

	/** Case-insensitive flag test (flags are case-insensitive, RFC 9051 section 2.3.2). */
	public static boolean hasFlag(Collection<String> flags, String flag) {
		for (String f : flags) {
			if (f.equalsIgnoreCase(flag)) {
				return true;
			}
		}
		return false;
	}

	public boolean hasFlag(MessageInfo m, String flag) {
		lock();
		try {
			return hasFlag(m.flags, flag);
		} finally {
			unlock();
		}
	}

	// ------------------------------------------------------------------ views

	/** A new session view of this mailbox (SELECT, EXAMINE). Close it when done. */
	public MailboxView newView(boolean readOnly) {
		return new MailboxView(this, readOnly);
	}

	void addView(MailboxView v) {
		views.add(v);
	}

	void removeView(MailboxView v) {
		views.remove(v);
	}

	// ------------------------------------------------------------------ refresh

	/**
	 * Find messages added or removed outside this server's IMAP sessions (POP3,
	 * delivery, other servers) and tell the views. Without {@code force}, at most once a second.
	 */
	public void refresh(boolean force) throws IOException {
		lock();
		try {
			long now = System.currentTimeMillis();
			if (!force && now - lastRefresh < REFRESH_INTERVAL) {
				return;
			}
			lastRefresh = now;
		} finally {
			unlock();
		}
		change(() -> {
			scan();
			return null;
		});
	}

	/** Look at the directory: index new files, forget files that are gone. Under the lock. */
	private void scan() throws IOException {
		Set<String> present = new HashSet<>();
		List<String> added = new ArrayList<>();
		FileSource[] list = deleted || !dir.exists() ? null : dir.listFiles();
		if (list != null) {
			for (FileSource f : list) {
				String name = f.getName();
				if (name.startsWith(".") || name.indexOf('\t') >= 0 || name.indexOf('\n') >= 0) {
					continue;
				}
				present.add(name);
				if (!byName.containsKey(name) && !notMessages.contains(name)) {
					if (f.isFile()) {
						added.add(name);
					} else {
						notMessages.add(name);
					}
				}
			}
		}
		notMessages.retainAll(present);
		for (Iterator<MessageInfo> it = messages.values().iterator(); it.hasNext();) {
			MessageInfo m = it.next();
			if (!present.contains(m.getName())) {
				it.remove();
				byName.remove(m.getName());
				pendingFlags.remove(m.getUid());
				deleteCache(m);
				dirty = true;
				for (MailboxView v : views) {
					v.expunged(m.getUid());
				}
			}
		}
		added.sort(null);
		for (String name : added) {
			MessageInfo m = index(dir.getChild(name), uidNext, new LinkedHashSet<>(), 0);
			if (m != null) {
				uidNext++;
				messages.put(m.getUid(), m);
				byName.put(name, m);
				dirty = true;
			}
		}
		if (!added.isEmpty()) {
			for (MailboxView v : views) {
				v.added();
			}
		}
	}

	/**
	 * Make the index entry for a message file: rewrite it with CRLF line ends if
	 * needed, measure it and check whether it has UTF-8 headers.
	 */
	private MessageInfo index(FileSource file, long uid, Set<String> flags, long internalDate) throws IOException {
		Scan s = Scan.of(file);
		if (s.needsCrlf) {
			FileSource tmp = dir.getChild(tmpName("crlf-" + file.getName()));
			try (InputStream in = IoUtils.buffered(file.getInputStream());
					OutputStream out = new CrlfOutputStream(IoUtils.buffered(tmp.getOutputStream()))) {
				in.transferTo(out);
			}
			if (!tmp.renameTo(file, true)) {
				tmp.delete();
				throw new IOException("Can't rewrite " + file.getAbsolutePath() + " with CRLF line ends");
			}
			s = Scan.of(file);
		}
		boolean utf8 = false;
		if (s.nonAscii) {
			try {
				utf8 = Downgrader.needsUtf8(Message.parse(file));
			} catch (IOException | RuntimeException e) {
				utf8 = true; // can't tell: treat as needing UTF-8
			}
		}
		long date = internalDate > 0 ? internalDate : file.lastModified();
		if (date <= 0) {
			date = System.currentTimeMillis();
		}
		MessageInfo m = new MessageInfo(uid, file.getName(), file, date, s.size, utf8);
		m.flags.addAll(flags);
		return m;
	}

	/** Size, bare LF/CR and 8-bit content of a file. */
	private static final class Scan {
		long size;
		boolean needsCrlf;
		boolean nonAscii;

		static Scan of(FileSource f) throws IOException {
			Scan s = new Scan();
			int prev = '\n';
			try (InputStream in = IoUtils.buffered(f.getInputStream())) {
				byte[] buf = new byte[64 * 1024];
				int n;
				while ((n = in.read(buf)) > 0) {
					for (int i = 0; i < n; i++) {
						int b = buf[i] & 0xff;
						if ((b == '\n' && prev != '\r') || (prev == '\r' && b != '\n')) {
							s.needsCrlf = true;
						}
						if (b >= 128) {
							s.nonAscii = true;
						}
						prev = b;
					}
					s.size += n;
				}
			}
			if (prev == '\r') {
				s.needsCrlf = true;
			}
			return s;
		}
	}

	/** Turns bare LF and bare CR into CRLF. */
	static final class CrlfOutputStream extends java.io.FilterOutputStream {
		private boolean pendingCr;

		CrlfOutputStream(OutputStream out) {
			super(out);
		}

		@Override
		public void write(int b) throws IOException {
			if (pendingCr) {
				pendingCr = false;
				out.write('\r');
				out.write('\n');
				if (b == '\n') {
					return;
				}
			}
			if (b == '\r') {
				pendingCr = true;
			} else if (b == '\n') {
				out.write('\r');
				out.write('\n');
			} else {
				out.write(b);
			}
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			for (int i = off; i < off + len; i++) {
				write(b[i]);
			}
		}

		@Override
		public void close() throws IOException {
			if (pendingCr) {
				pendingCr = false;
				out.write('\r');
				out.write('\n');
			}
			super.close();
		}
	}

	// ------------------------------------------------------------------ changes

	private static String newName() {
		return String.format("%013d.%08d.%08x.eml", System.currentTimeMillis(), SEQUENCE.incrementAndGet() % 100_000_000L,
				RANDOM.nextInt());
	}

	/**
	 * Add a message (APPEND, COPY): its content is copied into the mailbox with
	 * CRLF line ends. If its index entry can't be saved, the message is taken out again, so a
	 * client that is told it failed and tries again doesn't get it twice.
	 *
	 * @return the new message
	 */
	public MessageInfo append(InputStream content, Set<String> flags, long internalDate) throws IOException {
		checkUsable();
		String name = newName();
		FileSource tmp = dir.getChild(tmpName(name));
		try (OutputStream out = new CrlfOutputStream(IoUtils.buffered(tmp.getOutputStream()))) {
			content.transferTo(out);
		} catch (IOException | RuntimeException e) {
			tmp.delete();
			throw e;
		}
		try {
			return change(() -> {
				checkUsable();
				FileSource target = dir.getChild(name);
				if (!tmp.renameTo(target)) {
					throw new IOException("Can't store the message in " + dir.getAbsolutePath());
				}
				MessageInfo m = addLocked(target, flags, internalDate);
				try {
					commit();
				} catch (IOException | RuntimeException e) {
					// not in the saved index: take it back out (its UID isn't given again)
					forget(m);
					target.delete();
					throw e;
				}
				return m;
			});
		} finally {
			if (tmp.exists()) {
				tmp.delete();
			}
		}
	}

	/** Index a file already in the directory (or moved into it); under the lock. */
	private MessageInfo addLocked(FileSource file, Set<String> flags, long internalDate) throws IOException {
		MessageInfo m = index(file, uidNext, cleanFlags(flags), internalDate);
		uidNext++;
		messages.put(m.getUid(), m);
		byName.put(m.getName(), m);
		dirty = true;
		for (MailboxView v : views) {
			v.added();
		}
		return m;
	}

	/** Take a message out of memory (it is gone, or its save failed); under the lock. */
	private void forget(MessageInfo m) {
		messages.remove(m.getUid());
		byName.remove(m.getName());
		pendingFlags.remove(m.getUid());
		deleteCache(m);
		for (MailboxView v : views) {
			v.expunged(m.getUid());
		}
	}

	/**
	 * Move messages into another mailbox (MOVE). In one lock domain both mailboxes are locked
	 * together (in one order, so two moves can't wait for each other): the files are renamed into
	 * the target with their flags, the target's index saved, then the source's. A crash in between
	 * leaves each message in one place: a file renamed but not yet in the target's index is found by
	 * its next refresh (without its flags), and the source forgets a message whose file is gone.
	 * Otherwise (another store, a file system that can't rename across) the messages are copied
	 * and then expunged: a crash in between leaves a copy in both, never in neither.
	 *
	 * @return the new messages, in the order given
	 */
	public List<MessageInfo> moveTo(Mailbox target, List<MessageInfo> list, MailboxView source) throws IOException {
		if (target == this) {
			throw new IOException("A message can't be moved into its own mailbox");
		}
		if (domain != null && domain == target.domain) {
			try (LockGroup g = domain.lockAll(Arrays.asList(lockName, target.lockName), Mode.EXCLUSIVE, domain.options().timeout())) {
				return moveLocked(target, list, source, g);
			}
		}
		if (domain == null && target.domain == null) {
			return moveLocked(target, list, source, null);
		}
		List<MessageInfo> copies = copyTo(target, list);
		List<Long> uids = new ArrayList<>();
		for (MessageInfo m : list) {
			uids.add(m.getUid());
		}
		expunge(uids, source);
		return copies;
	}

	/** Both mailboxes' domain locks are held (or there is no domain). */
	private List<MessageInfo> moveLocked(Mailbox target, List<MessageInfo> list, MailboxView source, LockGroup g) throws IOException {
		NamedLock mine = g == null ? null : lockOf(g, lockName);
		NamedLock theirs = g == null ? null : lockOf(g, target.lockName);
		// this object's locks in one order too (by key), and only ever while both domain locks are held
		Mailbox first = key.compareTo(target.key) <= 0 ? this : target;
		Mailbox second = first == this ? target : this;
		first.lock();
		second.lock();
		NamedLock myOuter = changing;
		NamedLock theirOuter = target.changing;
		changing = mine != null ? mine : myOuter;
		target.changing = theirs != null ? theirs : theirOuter;
		try {
			if (shared) {
				syncWithStore();
				target.syncWithStore();
			}
			List<MessageInfo> ret = new ArrayList<>();
			List<MessageInfo> moved = new ArrayList<>();
			List<FileSource[]> renames = new ArrayList<>();
			List<MessageInfo> copied = new ArrayList<>();
			try {
				for (MessageInfo m : list) {
					if (messages.get(m.getUid()) != m) {
						continue; // expunged meanwhile
					}
					Set<String> flags = new LinkedHashSet<>(m.flags);
					FileSource dest = target.dir.getChild(newName());
					ensureHeld(mine);
					ensureHeld(theirs);
					if (m.getFile().renameTo(dest)) {
						renames.add(new FileSource[] {m.getFile(), dest});
						ret.add(target.addLocked(dest, flags, m.getInternalDate()));
						moved.add(m);
					} else {
						// can't rename across here: a copy, and the original is expunged below
						FileSource tmp = target.dir.getChild(tmpName(dest.getName()));
						try (InputStream in = m.getFile().getInputStream();
								OutputStream out = new CrlfOutputStream(IoUtils.buffered(tmp.getOutputStream()))) {
							in.transferTo(out);
						}
						if (!tmp.renameTo(dest)) {
							tmp.delete();
							throw new IOException("Can't store the message in " + target.dir.getAbsolutePath());
						}
						renames.add(new FileSource[] {null, dest});
						ret.add(target.addLocked(dest, flags, m.getInternalDate()));
						copied.add(m);
					}
				}
				target.commit();
			} catch (IOException | RuntimeException e) {
				// not in the target's saved index (a failed copy, the lock lost, the save failed):
				// put every file back where it was
				for (int i = renames.size() - 1; i >= 0; i--) {
					FileSource[] r = renames.get(i);
					if (r[0] == null) {
						r[1].delete();
					} else {
						r[1].renameTo(r[0]);
					}
					if (i < ret.size()) {
						target.forget(ret.get(i));
					}
				}
				throw e;
			}
			for (MessageInfo m : moved) {
				forgetQuietly(m, source);
			}
			for (MessageInfo m : copied) {
				ensureHeld(mine);
				if (!m.getFile().exists() || m.getFile().delete()) {
					forgetQuietly(m, source);
				}
			}
			dirty = dirty || !moved.isEmpty() || !copied.isEmpty();
			commit(); // if this fails, the source's next change forgets the files that are gone
			return ret;
		} finally {
			changing = myOuter;
			target.changing = theirOuter;
			second.unlock();
			first.unlock();
		}
	}

	private static NamedLock lockOf(LockGroup g, String name) {
		for (NamedLock l : g.locks()) {
			if (l.name().equals(name)) {
				return l;
			}
		}
		return null;
	}

	/** Forget a message moved away; the session that moved it reports it itself. */
	private void forgetQuietly(MessageInfo m, MailboxView source) {
		messages.remove(m.getUid());
		byName.remove(m.getName());
		pendingFlags.remove(m.getUid());
		deleteCache(m);
		dirty = true;
		for (MailboxView v : views) {
			if (v != source) {
				v.expunged(m.getUid());
			}
		}
	}

	/** Copy messages into another mailbox, keeping flags and INTERNALDATE. */
	public List<MessageInfo> copyTo(Mailbox target, List<MessageInfo> list) throws IOException {
		List<MessageInfo> ret = new ArrayList<>();
		for (MessageInfo m : list) {
			try (InputStream in = m.getFile().getInputStream()) {
				ret.add(target.append(in, m.getFlags(this), m.getInternalDate()));
			}
		}
		return ret;
	}

	/**
	 * Remove messages (EXPUNGE): their files are deleted and every other view is
	 * told. The caller reports the expunges to its own session. The UIDs are taken to belong to
	 * the mailbox as it is now: see {@link #expunge(Collection, MailboxView, long)}.
	 *
	 * @return the UIDs removed
	 */
	public List<Long> expunge(Collection<Long> uids, MailboxView source) throws IOException {
		return expunge(uids, source, getUidValidity());
	}

	/**
	 * Remove messages (EXPUNGE), if the mailbox is still the one the UIDs were read from: nothing
	 * is removed if it was recreated (another UIDVALIDITY, as the change catches up with another
	 * server), or if the source view was told it was, since the same UIDs now name other
	 * messages.
	 *
	 * @param uidValidity the UIDVALIDITY the UIDs were read under
	 * @return the UIDs removed
	 */
	public List<Long> expunge(Collection<Long> uids, MailboxView source, long uidValidity) throws IOException {
		return change(() -> {
			List<Long> removed = new ArrayList<>();
			if (this.uidValidity != uidValidity || (source != null && source.recreatedLocked())) {
				return removed; // recreated: those UIDs aren't these messages
			}
			for (long uid : new TreeSet<>(uids)) {
				MessageInfo m = messages.get(uid);
				if (m == null) {
					continue;
				}
				ensureHeld(changing);
				if (m.getFile().exists() && !m.getFile().delete()) {
					continue; // still there: not expunged
				}
				forgetQuietly(m, source);
				removed.add(uid);
			}
			return removed;
		});
	}

	/**
	 * Change a message's flags (STORE); every other view is told. Returns the new flags, or null
	 * if the message is gone. The change is saved by the next change of the mailbox (or
	 * {@link #saveIfDirty()}); with other servers, on top of the latest index.
	 */
	public Set<String> storeFlags(MessageInfo m, FlagOp op, Collection<String> flags, MailboxView source) {
		lock();
		try {
			if (messages.get(m.getUid()) != m) {
				return null;
			}
			Set<String> clean = cleanFlags(flags);
			if (apply(m, op, clean)) {
				dirty = true;
				if (shared) {
					pendingFlags.computeIfAbsent(m.getUid(), k -> new ArrayList<>()).add(new FlagChange(op, clean));
				}
				for (MailboxView v : views) {
					if (v != source) {
						v.flagsChanged(m.getUid());
					}
				}
			}
			return new LinkedHashSet<>(m.flags);
		} finally {
			unlock();
		}
	}

	/** @return whether the flags changed */
	private static boolean apply(MessageInfo m, FlagOp op, Set<String> clean) {
		Set<String> before = new LinkedHashSet<>(m.flags);
		if (op == FlagOp.SET) {
			m.flags.clear();
			m.flags.addAll(clean);
		} else if (op == FlagOp.ADD) {
			for (String f : clean) {
				if (!hasFlag(m.flags, f)) {
					m.flags.add(f);
				}
			}
		} else {
			m.flags.removeIf(f -> hasFlag(clean, f));
		}
		return !before.equals(m.flags);
	}

	/** A flag change waiting to be saved. */
	private static final class FlagChange {
		final FlagOp op;
		final Set<String> flags;

		FlagChange(FlagOp op, Set<String> flags) {
			this.op = op;
			this.flags = flags;
		}
	}

	/** Canonical system flag names; \Recent can't be stored. */
	static Set<String> cleanFlags(Collection<String> flags) {
		Set<String> ret = new LinkedHashSet<>();
		for (String f : flags) {
			String c = canonical(f);
			if (c != null && !hasFlag(ret, c)) {
				ret.add(c);
			}
		}
		return ret;
	}

	private static final String[] SYSTEM = {"\\Seen", "\\Answered", "\\Flagged", "\\Deleted", "\\Draft"};
	private static final String[] KEYWORDS = {"$MDNSent", "$Forwarded", "$Junk", "$NotJunk", "$Phishing"};

	static String canonical(String f) {
		if (f.equalsIgnoreCase("\\Recent")) {
			return null;
		}
		for (String s : SYSTEM) {
			if (s.equalsIgnoreCase(f)) {
				return s;
			}
		}
		for (String s : KEYWORDS) {
			if (s.equalsIgnoreCase(f)) {
				return s;
			}
		}
		return f;
	}

	private void checkUsable() throws IOException {
		if (deleted || !dir.exists()) {
			throw new IOException("The mailbox no longer exists");
		}
	}

	/** Forget this mailbox (deleted or renamed): views see every message expunged. */
	void markDeleted() {
		lock();
		try {
			markDeletedLocked();
		} finally {
			unlock();
		}
	}

	private void markDeletedLocked() {
		deleted = true;
		for (MessageInfo m : messages.values()) {
			for (MailboxView v : views) {
				v.expunged(m.getUid());
			}
		}
		messages.clear();
		byName.clear();
		pendingFlags.clear();
	}

	// ------------------------------------------------------------------ surrogates

	/**
	 * The RFC 6858 surrogate of a message with UTF-8 headers, for sessions that
	 * aren't in UTF-8 mode. It is made once and kept in {@code .imap-cache}.
	 */
	public FileSource surrogate(MessageInfo m) throws IOException {
		FileSource cacheDir = dir.getChild(CACHE_DIR);
		FileSource f = cacheDir.getChild(m.getUid() + ".eml");
		lock();
		try {
			if (!f.exists()) {
				if (!cacheDir.exists()) {
					cacheDir.mkdirs();
				}
				Message msg = Message.parse(m.getFile());
				Downgrader.downgrade(msg);
				FileSource tmp = cacheDir.getChild(tmpName(m.getUid() + ".eml"));
				try (OutputStream out = IoUtils.buffered(tmp.getOutputStream())) {
					msg.writeTo(out);
				}
				if (!tmp.renameTo(f, true)) {
					tmp.delete();
					throw new IOException("Can't cache the surrogate of " + m.getName());
				}
			}
			return f;
		} finally {
			unlock();
		}
	}

	private void deleteCache(MessageInfo m) {
		try {
			FileSource f = dir.getChild(CACHE_DIR).getChild(m.getUid() + ".eml");
			if (f.exists()) {
				f.delete();
			}
		} catch (IOException e) {
			// a stale cache file is harmless
		}
	}

	/** Read the special-use attribute from a mailbox's index without opening it. */
	static String readSpecialUse(FileSource dir) {
		try {
			FileSource f = dir.getChild(INDEX);
			if (!f.exists()) {
				return null;
			}
			try (BufferedReader r = new BufferedReader(new InputStreamReader(f.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				int n = 0;
				while ((line = r.readLine()) != null && n++ < 10) {
					if (line.startsWith("SPECIALUSE\t")) {
						String v = line.substring(11);
						return v.isEmpty() ? null : v;
					}
					if (line.startsWith("M\t")) {
						break;
					}
				}
			}
		} catch (IOException e) {
			// none
		}
		return null;
	}
}
