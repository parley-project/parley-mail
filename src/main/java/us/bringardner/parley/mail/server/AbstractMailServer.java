package us.bringardner.parley.mail.server;

import java.io.IOException;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.store.MaildropRoot;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.iam.UserRegistry;
import us.bringardner.parley.iam.UserRegistryUnavailableException;
import us.bringardner.parley.net.server.FileUserRegistry;
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
	/** Why the maildrop root couldn't be opened (start() refuses with it), or null */
	private volatile String rootError;
	/** How the maildrop root is shown (no secrets), or null */
	private volatile String rootDescription;
	/** The administration of this server's store while it runs ({@link #getStoreAdmin()}), or null */
	private volatile us.bringardner.parley.mail.store.MailStoreAdmin storeAdmin;
	/**
	 * The user file (parley-net's {@link FileUserRegistry} format) every mail server uses when it
	 * has no user registry of its own (no {@code <prefix>.authenticationProvider}), read again when
	 * the file changes. The value is a path on the default file system or a {@code filesource:} URL
	 * ({@link FileSource#of(String)}). Each server reads it into a registry of its own; an application running
	 * several servers can give them one registry instead ({@link #getUsersFile()},
	 * {@link #setUserRegistry}).
	 */
	public static final String MAIL_USERS_PROP = "parley.mail.users";
	/** The TLS key store of every mail server that has none of its own ({@code <prefix>.keyStoreName}) */
	public static final String MAIL_TLS_KEY_STORE_PROP = "parley.mail.tls.keyStore";
	/** The password of {@link #MAIL_TLS_KEY_STORE_PROP} */
	public static final String MAIL_TLS_KEY_STORE_PASSWORD_PROP = "parley.mail.tls.keyStorePassword";
	/** The type of {@link #MAIL_TLS_KEY_STORE_PROP} (default PKCS12) */
	public static final String MAIL_TLS_KEY_STORE_TYPE_PROP = "parley.mail.tls.keyStoreType";

	private FileSource maildropRoot;
	/** The users file this server's registry was read from ({@link #MAIL_USERS_PROP}), or null */
	private volatile FileSource usersFile;
	/** what {@link #useSharedSettings()} set */
	private boolean sharedSettingsUsed;
	/** The registry this server made from {@link #MAIL_USERS_PROP}, or null: its own, closed when replaced */
	private UserRegistry sharedUsers;
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
		sharedUsers = null;
		sharedKeyStore = false;
		usersFile = null;
		String users = getProperty(MAIL_USERS_PROP);
		if (users != null && !users.trim().isEmpty() && getProperty(propertyName(AUTHENTICATION_PROVIDER_PROPERTY)) == null) {
			try {
				// a path on the default file system, or a filesource: URL
				FileSource file = FileSource.of(users.trim()).getCanonicalFile();
				UserRegistry registry = users(file);
				setUserRegistry(registry);
				usersFile = file;
				sharedUsers = registry;
			} catch (IOException e) {
				throw new java.io.UncheckedIOException("Can't read the users file " + us.bringardner.parley.files.FileSourceFactory.safe(users.trim()) + " (" + MAIL_USERS_PROP + ")", e);
			}
		}
		String keyStore = getProperty(MAIL_TLS_KEY_STORE_PROP);
		if (keyStore != null && !keyStore.trim().isEmpty() && getProperty(propertyName(PROPERTY_KEY_STORE_NAME)) == null) {
			setKeyStoreFileName(keyStore.trim());
			String password = getProperty(MAIL_TLS_KEY_STORE_PASSWORD_PROP);
			if (password != null) {
				setKeyStorePassword(password);
			}
			setKeyStoreType(getProperty(MAIL_TLS_KEY_STORE_TYPE_PROP, "PKCS12"));
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
			UserRegistry made = sharedUsers;
			if (made != null) {
				if (getUserRegistry() == made) {
					setUserRegistry(null);
				}
				closeQuietly(made);
			}
			if (sharedKeyStore) {
				setKeyStoreFileName(null);
				setKeyStorePassword(null);
				setKeyStoreType(null);
			}
			useSharedSettings();
		}
	}

	private static FileUserRegistry users(FileSource file) throws IOException {
		FileUserRegistry registry = new FileUserRegistry();
		registry.initialize(file);
		return registry;
	}

	private void closeQuietly(UserRegistry registry) {
		try {
			registry.close();
		} catch (IOException | RuntimeException e) {
			logError("Can't close the user registry of " + getName(), e);
		}
	}

	/**
	 * @return the users file ({@link #MAIL_USERS_PROP}, canonical) this server read its registry from,
	 * or null when it has a user registry of its own. An application running several servers on one
	 * file can give them one registry instead, with {@link #setUserRegistry} before they start.
	 * @since 2.0.0
	 */
	public FileSource getUsersFile() {
		return usersFile;
	}

	/**
	 * A refused store claim, with what to do about it: with sharing off, the store may be another
	 * server's (in this process or another); with sharing on, the server that has it doesn't share it.
	 */
	static IOException withSharingHint(IOException e, us.bringardner.parley.net.lock.Sharing sharing) {
		if (!(e instanceof us.bringardner.parley.net.lock.StoreClaimException)) {
			return e;
		}
		String f = us.bringardner.parley.mail.store.MailLocks.STORE_SETTINGS_FILE;
		String p = us.bringardner.parley.mail.store.MailLocks.SHARING;
		String hint;
		if (e.getMessage() != null && e.getMessage().contains("has the id")) {
			hint = "; give each server sharing the store its own id (its serverId setting)";
		} else if (sharing == null || sharing == us.bringardner.parley.net.lock.Sharing.OFF) {
			hint = "; the store's settings (" + f + ") say " + p + "=off: one server at a time. For servers to share it, set "
					+ p + "=host there (cluster for several hosts)";
		} else {
			hint = "; the server that has it doesn't share it";
		}
		return new IOException(e.getMessage() + hint, e);
	}

	/** Called by a subclass's constructor once it has finished its own set up. */
	protected final void finishInit() {
		getLogger().setLevel(Level.INFO);
	}

	/**
	 * Find the maildrop root from this server's settings. It has no default: without one the
	 * server doesn't start.
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
		rootError = null;
		String tmp = property(rootProp, legacyRootProp);
		if (tmp == null || tmp.trim().isEmpty()) {
			return; // required: start() says so
		}
		String root = tmp.trim();
		String source = property(fileSourceProp, legacyFileSourceProp);
		try {
			java.util.Properties connection = MaildropRoot.isUrl(root) ? null : connectionSettings(fileSourceProp + ".");
			maildropRoot = MaildropRoot.open(root, source, connection, fileSourceProp);
			rootDescription = MaildropRoot.describe(root, source);
			factory = maildropRoot.getFileSourceFactory();
		} catch (IOException e) {
			maildropRoot = null;
			rootError = "Can't open the maildrop root " + MaildropRoot.describe(root, source) + ": "
					+ (e.getMessage() == null ? e.toString() : e.getMessage());
			logError(rootError, e);
		}
	}

	/** The scheme of a root given as a URL ({@code filesource:/mail?sourcetype=jdbc&url=...}) */
	public static final String FILESOURCE_URL_SCHEME = MaildropRoot.URL_SCHEME;

	/**
	 * The file system's connection settings: every setting named PREFIX + KEY in this server's
	 * configuration ({@code parley.mail.fileSource.url} gives the connection key {@code url}). A
	 * secret is a reference ({@code @file:...}, {@code @keychain:...}), never the secret itself.
	 */
	private java.util.Properties connectionSettings(String prefix) {
		return MaildropRoot.connectionSettings(getConfiguration() != null ? getConfiguration() : System.getProperties(), prefix);
	}

	private String property(String name, String legacy) {
		String ret = name == null ? null : getProperty(name);
		return ret == null && legacy != null ? getProperty(legacy) : ret;
	}

	/**
	 * Say so when no one can log in: the server has no user registry (neither
	 * {@code <prefix>.authenticationProvider}, {@link #MAIL_USERS_PROP} nor {@link #setUserRegistry}).
	 * A login then fails with an error naming the setting (parley-net: no registry is a mistake in
	 * the configuration, not a refused login).
	 */
	protected void warnIfNoAccessControl() {
		UserRegistry registry = getUserRegistry();
		if (registry == null) {
			String setting = getPropertyPrefix() == null ? AUTHENTICATION_PROVIDER_PROPERTY : propertyName(AUTHENTICATION_PROVIDER_PROPERTY);
			logInfo("No user registry is configured for " + getName() + " (" + setting + " or " + MAIL_USERS_PROP + "); no one can log in");
		}
	}

	/**
	 * The names of a user's groups in the server's user registry ({@link UserRegistry#findGroups},
	 * directly or through other groups), for the {@code $group} entries of a mailbox ACL (RFC 4314;
	 * group names carry their domain, {@code sales@bringardner.us}).
	 *
	 * @param principal the user; null gives no groups
	 * @return the group names, in the registry's order; empty when there is no registry or the user is in no group
	 * @throws UserRegistryUnavailableException when the user registry can't answer now
	 * @since 2.0.0
	 */
	public java.util.Set<String> findGroupNames(ParleyPrincipal principal) throws UserRegistryUnavailableException {
		java.util.Set<String> ret = new java.util.LinkedHashSet<>();
		UserRegistry registry = getUserRegistry();
		if (registry == null || principal == null) {
			return ret;
		}
		for (ParleyPrincipal group : registry.findGroups(principal)) {
			ret.add(group.getName());
		}
		return ret;
	}

	/** The property that sets the maildrop root, for the error message when it is missing. */
	protected abstract String getRootProperty();

	public FileSource getMaildropRoot() throws IOException {
		if (maildropRoot == null) {
			throw new IOException(rootError != null ? rootError : "The maildrop root is not configured (see " + getRootProperty() + ")");
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

	/**
	 * Start with this idle time, from this server's settings: a duration ({@code 10m}, {@code 30m}; a bare
	 * number is ms). Doesn't call {@link #onAutologoutChanged(int)}.
	 *
	 * @param defaultValue the default, in ms
	 */
	protected void initAutologout(String property, int defaultValue) {
		long v = getDuration(property, defaultValue);
		autologout = v <= 0 ? defaultValue : us.bringardner.parley.core.util.Durations.toIntMillis(v);
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
	 * accepting connections: with the store's sharing off, a second server on it is refused (in this
	 * JVM or another); shared, servers that share it are let in. Fails to start if the store has no
	 * settings, the maildrop root isn't configured or the claim fails.
	 */
	@Override
	public synchronized void start() {
		if (maildropRoot == null) {
			IOException err = new IOException(rootError != null ? rootError : "The maildrop root is not configured (" + getRootProperty() + ")");
			logError("Can't start " + getName() + ": " + err.getMessage(), err);
			throw new java.io.UncheckedIOException(err);
		}
		boolean claimed = false;
		if (storeClaim == null) {
			us.bringardner.parley.net.lock.Sharing sharing = null;
			try {
				lockDomain = us.bringardner.parley.mail.store.MailLocks.acquire(getMaildropRoot());
				sharing = lockDomain.options().sharing();
				String id = getProperty(propertyName(us.bringardner.parley.mail.store.MailLocks.SERVER_ID));
				// a server on a port the system picks (0) has no port yet: its default id is its own
				String name = getPort() > 0 ? getName() : getName() + "-" + Integer.toHexString(System.identityHashCode(this));
				storeClaim = lockDomain.claimStore(us.bringardner.parley.mail.store.MailLocks.serverId(id, name, getPort()));
				claimed = true;
			} catch (IOException | RuntimeException e) {
				releaseStoreClaim();
				IOException err = e instanceof IOException ? withSharingHint((IOException) e, sharing)
						: new IOException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
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
			makeStoreAdmin();
		} catch (IOException e) {
			logError("Can't start " + getName() + ": " + e.getMessage(), e);
			throw new java.io.UncheckedIOException(e);
		} catch (java.io.UncheckedIOException e) {
			logError("Can't start " + getName() + ": " + e.getCause().getMessage(), e.getCause());
			throw e;
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
		storeAdmin = null;
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

	/**
	 * The administration of this server's store, the mailbox ACLs (RFC 4314) through this server's
	 * locks, while it runs: whoever runs the server registers it as a management bean if it wants
	 * ({@link us.bringardner.parley.mail.store.MailStoreAdminMXBean}; see
	 * {@link MailServerMain#service(AbstractMailServer, String, String)}).
	 *
	 * @return the administration, or null when the server isn't running
	 * @since 2.0.0
	 */
	public us.bringardner.parley.mail.store.MailStoreAdmin getStoreAdmin() {
		return storeAdmin;
	}

	private synchronized void makeStoreAdmin() {
		if (storeAdmin == null && maildropRoot != null) {
			String d = rootDescription != null ? rootDescription : maildropRoot.getName();
			storeAdmin = new us.bringardner.parley.mail.store.MailStoreAdmin(maildropRoot, d, us.bringardner.parley.mail.store.MailboxRegistry.get());
		}
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
