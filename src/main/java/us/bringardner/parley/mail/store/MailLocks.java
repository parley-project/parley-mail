package us.bringardner.parley.mail.store;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.lock.LockDomain;
import us.bringardner.parley.net.lock.LockOptions;
import us.bringardner.parley.net.lock.Locks;
import us.bringardner.parley.net.lock.Sharing;

/**
 * The mail servers' locks in this JVM (parley-net's {@link Locks}): one lock domain per maildrop
 * root, used by every server here that uses it (SMTP, IMAP, POP3), and registered with the
 * {@link MailboxRegistry} so mailboxes lock through it.
 * <p>
 * The store carries its own settings, so the servers that use it can't disagree about them: the
 * file {@value #STORE_SETTINGS_FILE} in the maildrop root. {@value #SHARING} is how many servers
 * share the store: {@code off} (one server; its claim keeps a second out, in this JVM or another),
 * {@code host} (several on one machine) or {@code cluster} (several anywhere). {@value #LOCK_DOMAIN}
 * is the lock domain's name (default {@code mail}). A store without the file isn't used: making it
 * is an administrator's job ({@link #writeStoreSettings(FileSource, Sharing)}). How the locks are
 * kept between servers follows parley-net's settings ({@code parley.net.lock.provider}, ...).
 */
public final class MailLocks {

	/** The store's settings file, in the maildrop root */
	public static final String STORE_SETTINGS_FILE = ".parley-store.properties";
	/** Store setting: how many servers share the store (off, host, cluster) */
	public static final String SHARING = "sharing";
	/** Store setting: the lock domain's name (default {@code mail}); the same for every server */
	public static final String LOCK_DOMAIN = "lockDomain";
	/** Server setting: this server's id among those that share the store (default: host, server name, port). */
	public static final String SERVER_ID = "serverId";

	private static Locks locks;
	private static final Map<String, LockDomain> DOMAINS = new HashMap<>();
	private static final Map<LockDomain, Integer> USERS = new HashMap<>();

	private MailLocks() {
	}

	/**
	 * The store's settings, from {@value #STORE_SETTINGS_FILE} in its root.
	 * @param root the maildrop root
	 * @return its settings
	 * @throws IOException if the store has none (an administrator makes them) or they can't be read
	 */
	public static Properties storeSettings(FileSource root) throws IOException {
		FileSource f = root.getChild(STORE_SETTINGS_FILE);
		if( f == null || !f.isFile() ) {
			throw new IOException("The mail store " + root.getAbsolutePath() + " has no settings (" + STORE_SETTINGS_FILE
					+ " with " + SHARING + "=off, host or cluster); an administrator makes them");
		}
		Properties p = new Properties();
		try (java.io.InputStream in = f.getInputStream();
				java.io.Reader r = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
			p.load(r);
		}
		return p;
	}

	/** @return how many servers share the store, from its settings */
	public static Sharing sharing(FileSource root) throws IOException {
		return Sharing.parse(storeSettings(root).getProperty(SHARING));
	}

	/**
	 * Make (or replace) a store's settings: an administrator's job, done once for a new store.
	 * @param root    the maildrop root (made if it isn't there)
	 * @param sharing how many servers share it
	 * @throws IOException if the file can't be written
	 */
	public static void writeStoreSettings(FileSource root, Sharing sharing) throws IOException {
		if( !root.exists() && !root.mkdirs() ) {
			throw new IOException("Can't make the mail store " + root.getAbsolutePath());
		}
		String text = "# The mail store's settings, read by every server that uses it\n" + SHARING + "="
				+ sharing.name().toLowerCase(java.util.Locale.ROOT) + "\n";
		try (java.io.OutputStream out = root.getChild(STORE_SETTINGS_FILE).getOutputStream()) {
			out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
	}

	/** @return this JVM's lock service for mail, opened the first time it is asked for */
	public static synchronized Locks locks() {
		if( locks == null ) {
			// each store is registered with its own sharing (its settings); this is only the default
			locks = Locks.open(LockOptions.sharing(Sharing.OFF));
		}
		return locks;
	}

	/**
	 * The lock domain of a maildrop root, for a server that starts using it (match it with
	 * {@link #release(LockDomain)}): registered the first time, and with the registry.
	 * @param root the maildrop root
	 * @return its domain
	 * @throws IOException if its locks can't be set up (see {@link Locks#domain})
	 */
	public static synchronized LockDomain acquire(FileSource root) throws IOException {
		String key = MailboxRegistry.keyOf(root);
		LockDomain d = DOMAINS.get(key);
		if( d == null ) {
			Properties settings = storeSettings(root);
			Sharing sharing = Sharing.parse(settings.getProperty(SHARING));
			String name = settings.getProperty(LOCK_DOMAIN, "mail").trim();
			if( locks().domain(name) != null ) {
				// another root in this JVM already has the name (tests, several stores): give this
				// one its own, from its path
				name = name + "-" + Integer.toHexString(key.hashCode());
			}
			// the store's own sharing (so it can differ between stores)
			d = locks().domain(name, root, locks().options().withSharing(sharing));
			DOMAINS.put(key, d);
			MailboxRegistry.get().useLocks(d);
		}
		USERS.merge(d, 1, Integer::sum);
		return d;
	}

	/** A server stopped using a domain: the last one closes it. */
	public static synchronized void release(LockDomain d) {
		if( d == null ) {
			return;
		}
		Integer n = USERS.get(d);
		if( n == null ) {
			return;
		}
		if( n > 1 ) {
			USERS.put(d, n - 1);
			return;
		}
		USERS.remove(d);
		DOMAINS.values().remove(d);
		MailboxRegistry.get().stopUsingLocks(d);
		d.close();
	}

	/**
	 * This server's id among those that share its store: the id from its settings, or the host,
	 * the server's name and its port.
	 * @param configured the server's {@value #SERVER_ID} setting, or null
	 */
	public static String serverId(String configured, String serverName, int port) {
		return us.bringardner.parley.net.lock.StoreLocks.serverIdOf(configured, serverName, port);
	}
}
