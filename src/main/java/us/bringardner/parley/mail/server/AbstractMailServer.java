package us.bringardner.parley.mail.server;

import java.io.IOException;
import java.util.Locale;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.net.server.IAccessControlList;
import us.bringardner.parley.net.server.Server;

/**
 * Base for the mail servers (POP3, SMTP, IMAP): the maildrop root directory, the idle time
 * after which a session is logged out, and the common start-up.
 */
public abstract class AbstractMailServer extends Server {

	/** The maildrop root every mail server shares (POP3, IMAP and SMTP deliver to and read the same maildrops). */
	public static final String MAIL_ROOT_PROP = "parley.mail.root";
	/** The FileSource factory for {@link #MAIL_ROOT_PROP} (the default factory if not set). */
	public static final String MAIL_FILE_SOURCE_PROP = "parley.mail.fileSource";
	/**
	 * The user file every mail server uses when it has no access control of its own (no
	 * {@code <prefix>.authenticationProvider}): one list per file in the JVM, read again when the
	 * file changes.
	 */
	public static final String MAIL_USERS_PROP = "parley.mail.users";
	/** The TLS key store of every mail server that has none of its own ({@code <prefix>.keyStoreName}) */
	public static final String MAIL_TLS_KEY_STORE_PROP = "parley.mail.tls.keyStore";
	/** The password of {@link #MAIL_TLS_KEY_STORE_PROP} */
	public static final String MAIL_TLS_KEY_STORE_PASSWORD_PROP = "parley.mail.tls.keyStorePassword";
	/** The type of {@link #MAIL_TLS_KEY_STORE_PROP} (default PKCS12) */
	public static final String MAIL_TLS_KEY_STORE_TYPE_PROP = "parley.mail.tls.keyStoreType";

	/** The shared user lists, by canonical file */
	private static final java.util.Map<java.io.File, us.bringardner.parley.net.server.FileBasedAcl> SHARED_USERS = new java.util.concurrent.ConcurrentHashMap<>();

	private FileSource maildropRoot;
	/** what {@link #useSharedSettings()} set */
	private boolean sharedSettingsUsed;
	private boolean sharedAcl;
	private boolean sharedKeyStore;
	private FileSourceFactory factory = FileSourceFactory.getDefaultFactory();
	private volatile int autologout;
	/** This server's lock domain and claim on the maildrop root, while it runs. */
	private us.bringardner.parley.net.lock.LockDomain lockDomain;
	private us.bringardner.parley.net.lock.StoreClaim storeClaim;

	/**
	 * @param propertyPrefix the prefix of this server's properties, e.g. "parley.mail.pop3"
	 */
	protected AbstractMailServer(int port, String name, String propertyPrefix, boolean secure) {
		super(port, name);
		setPropertyPrefix(propertyPrefix);
		setSecure(secure);
		setDaemon(false);
		setConnectionFactory(newConnectionFactory());
		useSharedSettings();
	}

	/**
	 * The suite's shared settings, for what this server doesn't set itself: the users
	 * ({@link #MAIL_USERS_PROP}) and the TLS key store ({@link #MAIL_TLS_KEY_STORE_PROP}).
	 */
	private void useSharedSettings() {
		sharedAcl = false;
		sharedKeyStore = false;
		String users = System.getProperty(MAIL_USERS_PROP);
		if (users != null && !users.trim().isEmpty() && getProperty(propertyName(AUTHENTICATION_PROVIDER_PROPERTY)) == null) {
			try {
				setAccessControl(sharedUsers(new java.io.File(users.trim())));
				sharedAcl = true;
			} catch (IOException e) {
				throw new java.io.UncheckedIOException("Can't read the users file " + users + " (" + MAIL_USERS_PROP + ")", e);
			}
		}
		String keyStore = System.getProperty(MAIL_TLS_KEY_STORE_PROP);
		if (keyStore != null && !keyStore.trim().isEmpty() && getProperty(propertyName(PROPERTY_KEY_STORE_NAME)) == null) {
			setKeyStoreFileName(keyStore.trim());
			String password = System.getProperty(MAIL_TLS_KEY_STORE_PASSWORD_PROP);
			if (password != null) {
				setKeyStorePassword(password);
			}
			setKeyStoreType(System.getProperty(MAIL_TLS_KEY_STORE_TYPE_PROP, "PKCS12"));
			sharedKeyStore = true;
		}
		sharedSettingsUsed = true;
	}

	/**
	 * A new prefix (a submission server's, say) may have settings of its own: the shared ones are
	 * chosen again.
	 */
	@Override
	protected void setPropertyPrefix(String propertyPrefix) {
		super.setPropertyPrefix(propertyPrefix);
		if (sharedSettingsUsed) {
			if (sharedAcl) {
				setAccessControl(null);
			}
			if (sharedKeyStore) {
				setKeyStoreFileName(null);
				setKeyStorePassword(null);
				setKeyStoreType(null);
			}
			useSharedSettings();
		}
	}

	/**
	 * The user list of this file every mail server in the JVM shares (read again when the file
	 * changes).
	 */
	public static us.bringardner.parley.net.server.FileBasedAcl sharedUsers(java.io.File file) throws IOException {
		java.io.File key = file.getCanonicalFile();
		synchronized (SHARED_USERS) {
			us.bringardner.parley.net.server.FileBasedAcl acl = SHARED_USERS.get(key);
			if (acl == null) {
				acl = new us.bringardner.parley.net.server.FileBasedAcl();
				acl.initialize(key);
				SHARED_USERS.put(key, acl);
			}
			return acl;
		}
	}

	/** Called by a subclass's constructor once it has finished its own set up. */
	protected final void finishInit() {
		getLogger().setLevel(Level.INFO);
	}

	/**
	 * Find the maildrop root from the system properties.
	 *
	 * @param fileSourceProp the property naming the file source factory (null if not set)
	 * @param legacyFileSourceProp another property to try for it, or null
	 * @param rootProp the property holding the root path
	 * @param legacyRootProp another property to try for it, or null
	 * @param defaultRoot the root if neither property is set
	 * @param defaultRootWindows the root on Windows if neither property is set
	 */
	protected void initMaildropRoot(String fileSourceProp, String legacyFileSourceProp, String rootProp,
			String legacyRootProp, String defaultRoot, String defaultRootWindows) {
		String tmp = property(fileSourceProp, legacyFileSourceProp);
		if (tmp != null) {
			factory = FileSourceFactory.getFileSourceFactory(tmp.toLowerCase(Locale.ROOT));
		}
		tmp = property(rootProp, legacyRootProp);
		if (tmp == null) {
			tmp = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? defaultRootWindows : defaultRoot;
		}
		try {
			maildropRoot = factory.createFileSource(tmp);
		} catch (IOException e) {
			logInfo("Error attempting to set the maildrop root " + tmp + " using the " + factory.getTypeId() + " factory");
		}
	}

	private static String property(String name, String legacy) {
		String ret = System.getProperty(name);
		return ret == null && legacy != null ? System.getProperty(legacy) : ret;
	}

	/** Say so when no one can log in. */
	protected void warnIfNoAccessControl() {
		IAccessControlList acl = getAccessControl();
		if (acl == null) {
			logInfo("No access control is configured for " + getName() + "; no one can log in");
		}
	}

	/** The property that sets the maildrop root, for the error message when it is missing. */
	protected abstract String getRootProperty();

	public FileSource getMaildropRoot() throws IOException {
		if (maildropRoot == null) {
			throw new IOException("The maildrop root is not configured (see " + getRootProperty() + ")");
		}
		if (!maildropRoot.exists()) {
			maildropRoot.mkdirs();
		}
		return maildropRoot;
	}

	public void setMaildropRoot(FileSource root) throws IOException {
		if (!root.exists()) {
			if (!root.mkdirs()) {
				throw new IOException("Can't create the maildrop root " + root);
			}
		} else if (!root.isDirectory()) {
			throw new IOException("The maildrop root is not a directory: " + root);
		}
		this.maildropRoot = root;
		this.factory = root.getFileSourceFactory();
	}

	public FileSourceFactory getFileSourceFactory() {
		return factory;
	}

	// ------------------------------------------------------------------ autologout

	/** Start with this idle time (ms), from a system property. Doesn't call {@link #onAutologoutChanged(int)}. */
	protected void initAutologout(String property, int defaultValue) {
		autologout = Integer.getInteger(property, defaultValue);
	}

	public int getAutologout() {
		return autologout;
	}

	/** Close sessions idle for this long (ms). */
	public void setAutologout(int autologout) {
		if (autologout <= 0) {
			throw new IllegalArgumentException("autologout must be positive");
		}
		this.autologout = autologout;
		onAutologoutChanged(autologout);
	}

	/** Called after the idle time was set; the default does nothing. */
	protected void onAutologoutChanged(int autologout) {
	}

	// ------------------------------------------------------------------ the store claim

	/**
	 * Claim the maildrop root (see {@link us.bringardner.parley.mail.store.MailLocks}) before
	 * accepting connections: with {@code parley.mail.sharedStore} off, a second server on it is
	 * refused; shared, servers that share it are let in. Fails to start if the claim fails.
	 */
	@Override
	public synchronized void start() {
		boolean claimed = false;
		if (storeClaim == null && maildropRoot != null) {
			try {
				lockDomain = us.bringardner.parley.mail.store.MailLocks.acquire(getMaildropRoot());
				storeClaim = lockDomain.claimStore(us.bringardner.parley.mail.store.MailLocks.serverId(getName(), getPort()));
				claimed = true;
			} catch (IOException e) {
				releaseStoreClaim();
				IOException err = e;
				if (e instanceof us.bringardner.parley.net.lock.StoreClaimException && String.valueOf(e.getMessage()).contains("in use by another server")) {
					// (not shared: the other server may be another process on this host)
					err = new IOException(e.getMessage() + "; for servers in separate processes on this host to share it, set "
							+ us.bringardner.parley.mail.store.MailLocks.SHARED_STORE_PROPERTY + "=host (cluster for several hosts) for every one of them", e);
				}
				logError("Can't start " + getName() + ": " + err.getMessage(), err);
				throw new java.io.UncheckedIOException(err);
			}
		}
		boolean started = false;
		boolean prepared = false;
		try {
			storeClaimed();
			prepared = true;
			super.start();
			started = true;
		} catch (IOException e) {
			logError("Can't start " + getName() + ": " + e.getMessage(), e);
			throw new java.io.UncheckedIOException(e);
		} finally {
			if (!started) {
				if (prepared) {
					releasingStore();
				}
				if (claimed) {
					releaseStoreClaim(); // couldn't start (the port is taken, say): don't keep the store
				}
			}
		}
	}

	@Override
	public void stop() {
		super.stop();
		releasingStore();
		releaseStoreClaim();
	}

	/**
	 * Called by {@link #start()} once the store is claimed and before connections are accepted:
	 * work on the store (the SMTP queue, say) starts here, so it never runs on a store this server
	 * hasn't claimed. A failure stops the start. The default does nothing.
	 * @throws IOException if the work can't start
	 */
	protected void storeClaimed() throws IOException {
	}

	/**
	 * Called by {@link #stop()} (and by a start that fails after {@link #storeClaimed()}) after
	 * connections stopped and before the store claim is given up. The default does nothing.
	 */
	protected void releasingStore() {
	}

	private synchronized void releaseStoreClaim() {
		if (storeClaim != null) {
			storeClaim.close();
			storeClaim = null;
		}
		if (lockDomain != null) {
			us.bringardner.parley.mail.store.MailLocks.release(lockDomain);
			lockDomain = null;
		}
	}
}
