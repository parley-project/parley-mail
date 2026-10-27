package us.bringardner.parley.mail.server;

import java.io.IOException;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.store.MaildropRoot;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.net.server.IAccessControlList;
import us.bringardner.parley.net.server.Server;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerProvider;

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
	/**
	 * The user file every mail server uses when it has no access control of its own (no
	 * {@code <prefix>.authenticationProvider}), read again when the file changes. The servers a
	 * launcher runs share one list per file (in its {@link ServerContext}); a server made by code
	 * has its own.
	 */
	public static final String MAIL_USERS_PROP = "parley.mail.users";
	/** The TLS key store of every mail server that has none of its own ({@code <prefix>.keyStoreName}) */
	public static final String MAIL_TLS_KEY_STORE_PROP = "parley.mail.tls.keyStore";
	/** The password of {@link #MAIL_TLS_KEY_STORE_PROP} */
	public static final String MAIL_TLS_KEY_STORE_PASSWORD_PROP = "parley.mail.tls.keyStorePassword";
	/** The type of {@link #MAIL_TLS_KEY_STORE_PROP} (default PKCS12) */
	public static final String MAIL_TLS_KEY_STORE_TYPE_PROP = "parley.mail.tls.keyStoreType";

	/** The launcher context a provider is making a server in, on this thread ({@link #makeIn}) */
	private static final ThreadLocal<ServerContext> MAKING_IN = new ThreadLocal<>();

	private FileSource maildropRoot;
	/** The launcher context this server was made in, or null (made by code) */
	private final ServerContext context = MAKING_IN.get();
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
		String users = getProperty(MAIL_USERS_PROP);
		if (users != null && !users.trim().isEmpty() && getProperty(propertyName(AUTHENTICATION_PROVIDER_PROPERTY)) == null) {
			try {
				setAccessControl(sharedUsers(new java.io.File(users.trim())));
				sharedAcl = true;
			} catch (IOException e) {
				throw new java.io.UncheckedIOException("Can't read the users file " + users + " (" + MAIL_USERS_PROP + ")", e);
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
	 * The user list of this file: the one the launcher's servers share (made the first time, read
	 * again when the file changes), or for a server made by code a list of its own.
	 */
	private us.bringardner.parley.net.server.FileBasedAcl sharedUsers(java.io.File file) throws IOException {
		java.io.File key = file.getCanonicalFile();
		if (context == null) {
			return users(key);
		}
		// the list's own setting (how often it reads the file) is part of what is shared: servers
		// whose settings differ get lists of their own, so none gets another server's
		String reload = getProperty(us.bringardner.parley.net.server.FileBasedAcl.PROP_RELOAD_SECONDS);
		String sharedKey = SHARED_USERS_KEY + key.getPath() + (reload == null ? "" : "|" + reload.trim());
		try {
			return context.shared(sharedKey, us.bringardner.parley.net.server.FileBasedAcl.class, () -> users(key));
		} catch (IOException | RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * The user list of this file the launcher's mail servers share, or null if none of them uses
	 * it (yet): for an application that changed the file and wants them to read it now
	 * ({@code reloadNow()}).
	 */
	public static us.bringardner.parley.net.server.FileBasedAcl sharedUsers(ServerContext context, java.io.File file) throws IOException {
		Object o = context.get(SHARED_USERS_KEY + file.getCanonicalFile().getPath());
		if (o == null) {
			// one with a reload setting of its own
			String prefix = SHARED_USERS_KEY + file.getCanonicalFile().getPath() + "|";
			o = context.sharedKeys().stream().filter(k -> k.startsWith(prefix)).findFirst().map(context::get).orElse(null);
		}
		return o instanceof us.bringardner.parley.net.server.FileBasedAcl ? (us.bringardner.parley.net.server.FileBasedAcl) o : null;
	}

	/** The {@link ServerContext#shared} key of a users file's list (followed by its canonical path) */
	public static final String SHARED_USERS_KEY = "parley.mail.users:";

	private static us.bringardner.parley.net.server.FileBasedAcl users(java.io.File file) throws IOException {
		us.bringardner.parley.net.server.FileBasedAcl acl = new us.bringardner.parley.net.server.FileBasedAcl();
		acl.initialize(file);
		return acl;
	}

	/**
	 * Make a mail server for a launcher: it shares the users the context's other mail servers use
	 * (see {@link #MAIL_USERS_PROP}). For {@link ServerProvider#create(ServerContext)}.
	 */
	public static <T extends AbstractMailServer> T makeIn(ServerContext context, java.util.concurrent.Callable<T> make) throws Exception {
		return makeIn(context, null, make);
	}

	/**
	 * Make a mail server for a launcher, from its own configuration: everything it reads, in its
	 * constructor too, comes from the configuration (see parley-core's {@code BaseObject.makeWith}),
	 * not the system properties. For {@link ServerProvider#create(ServerContext, java.util.Properties)}.
	 *
	 * @param configuration the server's own settings, or null for the system properties
	 * @since 2.0.0
	 */
	public static <T extends AbstractMailServer> T makeIn(ServerContext context, java.util.Properties configuration,
			java.util.concurrent.Callable<T> make) throws Exception {
		ServerContext before = MAKING_IN.get();
		MAKING_IN.set(context);
		try {
			return us.bringardner.parley.core.BaseObject.makeWith(configuration, make);
		} finally {
			if (before == null) {
				MAKING_IN.remove();
			} else {
				MAKING_IN.set(before);
			}
		}
	}

	/** The launcher context this server was made in, or null */
	public ServerContext getServerContext() {
		return context;
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
			factory = maildropRoot.getFileSourceFactory();
		} catch (IOException e) {
			maildropRoot = null;
			rootError = "Can't open the maildrop root " + MaildropRoot.describe(root, source) + ": "
					+ (e.getMessage() == null ? e.toString() : e.getMessage());
			logError(rootError, e);
		}
	}

	/** The scheme of a root given as a URL ({@code filesource:/mail?sourcetype=jdbc&jdbcUrl=...}) */
	public static final String FILESOURCE_URL_SCHEME = MaildropRoot.URL_SCHEME;

	/**
	 * The file system's connection settings: every setting named PREFIX + KEY in this server's
	 * configuration ({@code parley.mail.fileSource.jdbcUrl} gives the connection key {@code jdbcUrl}). A
	 * secret is a reference ({@code @file:...}, {@code @keychain:...}), never the secret itself.
	 */
	private java.util.Properties connectionSettings(String prefix) {
		return MaildropRoot.connectionSettings(getConfiguration() != null ? getConfiguration() : System.getProperties(), prefix);
	}

	private String property(String name, String legacy) {
		String ret = name == null ? null : getProperty(name);
		return ret == null && legacy != null ? getProperty(legacy) : ret;
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

	/** Start with this idle time (ms), from this server's settings. Doesn't call {@link #onAutologoutChanged(int)}. */
	protected void initAutologout(String property, int defaultValue) {
		autologout = getIntProperty(property, defaultValue);
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
