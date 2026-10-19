package us.bringardner.parley.mail.store;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.lock.LockDomain;
import us.bringardner.parley.net.lock.LockOptions;
import us.bringardner.parley.net.lock.Locks;
import us.bringardner.parley.net.lock.Sharing;

/**
 * The mail servers' locks in this JVM (parley-net's {@link Locks}): one lock domain per maildrop
 * root, shared by every server here that uses it (SMTP, IMAP, POP3), and registered with the
 * {@link MailboxRegistry} so mailboxes lock through it.
 * <p>
 * How many servers share a store is {@value #SHARED_STORE_PROPERTY}: {@code off} (the default: one
 * server; its claim keeps a second out), {@code host} (several on one machine) or {@code cluster}
 * (several anywhere). How the locks are kept between them follows parley-net's settings
 * ({@code parley.net.lock.provider}, ...). The domain's name is {@value #DOMAIN_PROPERTY}, by default
 * {@code mail}; it must be the same on every server, which matters with a coordination store.
 */
public final class MailLocks {

	/** System property: how many servers share the maildrop root (off, host, cluster). */
	public static final String SHARED_STORE_PROPERTY = "parley.mail.sharedStore";
	/** System property: the lock domain's name (default {@code mail}). */
	public static final String DOMAIN_PROPERTY = "parley.mail.lockDomain";
	/** System property: this server's id among those that share the store (default: host, server name, port). */
	public static final String SERVER_ID_PROPERTY = "parley.mail.serverId";

	private static Locks locks;
	private static final Map<String, LockDomain> DOMAINS = new HashMap<>();
	private static final Map<LockDomain, Integer> USERS = new HashMap<>();

	private MailLocks() {
	}

	/** @return how many servers share the store, from {@value #SHARED_STORE_PROPERTY} */
	public static Sharing sharing() {
		return Sharing.parse(System.getProperty(SHARED_STORE_PROPERTY));
	}

	/** @return this JVM's lock service for mail, opened the first time it is asked for */
	public static synchronized Locks locks() {
		if( locks == null ) {
			locks = Locks.open(LockOptions.sharing(sharing()));
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
			String name = System.getProperty(DOMAIN_PROPERTY, "mail");
			if( locks().domain(name) != null ) {
				// another root in this JVM already has the name (tests, several stores): give this
				// one its own, from its path
				name = name + "-" + Integer.toHexString(key.hashCode());
			}
			// the sharing is read when the root is first used (so it can differ between roots)
			d = locks().domain(name, root, locks().options().withSharing(sharing()));
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
	 * This server's id among those that share its store: {@value #SERVER_ID_PROPERTY}, or the
	 * host, the server's name and its port.
	 */
	public static String serverId(String serverName, int port) {
		String id = System.getProperty(SERVER_ID_PROPERTY);
		if( id == null || id.isBlank() ) {
			String host;
			try {
				host = InetAddress.getLocalHost().getHostName();
			} catch (IOException | RuntimeException e) {
				host = "host";
			}
			id = host + "-" + serverName + "-" + port;
		}
		return id.replaceAll("[^A-Za-z0-9._-]", "_");
	}
}
