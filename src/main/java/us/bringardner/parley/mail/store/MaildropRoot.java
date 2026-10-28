package us.bringardner.parley.mail.store;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * Opening a mail store's root from settings, the same way for every program that uses the store
 * (the mail servers, parley-mail-admin, parley-servers-setup's StoreSetup), on any file system:
 * <ol>
 * <li>{@code parley.mail.root} is a path on the file system {@code parley.mail.fileSource} names
 * (the local disk when it isn't set), and each {@code parley.mail.fileSource.KEY} setting is that
 * file system's connection setting KEY ({@code parley.mail.fileSource.jdbcUrl}, {@code .jdbcUser},
 * {@code .jdbcPassword=@file:...}: a secret is a reference, which the file system reads);</li>
 * <li>or {@code parley.mail.root} is a {@code filesource:} URL, which names the file system and
 * its connection settings itself ({@code filesource:/mail?sourcetype=jdbc&...}).</li>
 * </ol>
 * A URL's query holds connection settings and secrets, so it is never in a message: {@link #safe}.
 */
public final class MaildropRoot {

	/** The scheme of a root given as a URL ({@code filesource:/mail?sourcetype=jdbc&jdbcUrl=...}) */
	public static final String URL_SCHEME = "filesource:";

	private MaildropRoot() {
	}

	/** @return true if ROOT is a {@code filesource:} URL */
	public static boolean isUrl(String root) {
		return root != null && root.trim().regionMatches(true, 0, URL_SCHEME, 0, URL_SCHEME.length());
	}

	/**
	 * A file system's connection settings: every setting named PREFIX + KEY in SETTINGS, as KEY
	 * ({@code parley.mail.fileSource.jdbcUrl} with the prefix {@code parley.mail.fileSource.} gives
	 * {@code jdbcUrl}).
	 */
	public static Properties connectionSettings(Properties settings, String prefix) {
		Properties ret = new Properties();
		if (settings != null) {
			for (String k : settings.stringPropertyNames()) {
				put(ret, prefix, k, settings.getProperty(k));
			}
		}
		return ret;
	}

	/** The same, from a map of settings */
	public static Properties connectionSettings(Map<String, String> settings, String prefix) {
		Properties ret = new Properties();
		if (settings != null) {
			for (Map.Entry<String, String> e : settings.entrySet()) {
				put(ret, prefix, e.getKey(), e.getValue());
			}
		}
		return ret;
	}

	private static void put(Properties ret, String prefix, String k, String v) {
		if (k != null && v != null && k.startsWith(prefix) && k.length() > prefix.length()) {
			ret.setProperty(k.substring(prefix.length()), v.trim());
		}
	}

	/**
	 * Open a store's root.
	 *
	 * @param root       the root: a path, or a {@code filesource:} URL
	 * @param fileSource the file system's type id for a path (null or empty: the local disk);
	 *                   ignored for a URL
	 * @param connection the file system's connection settings for a path (null or empty: its
	 *                   defaults); ignored for a URL
	 * @return the root (it may not exist yet)
	 * @throws IOException if there is no such file system, it can't connect or the root can't be
	 *                     made; the message never holds a URL's query
	 */
	public static FileSource open(String root, String fileSource, Properties connection) throws IOException {
		return open(root, fileSource, connection, null);
	}

	/**
	 * The same, naming the setting FILE_SOURCE_SETTING ({@code parley.mail.fileSource}) when the
	 * file system it names doesn't exist.
	 */
	public static FileSource open(String root, String fileSource, Properties connection, String fileSourceSetting) throws IOException {
		if (root == null || root.trim().isEmpty()) {
			throw new IOException("No mail root");
		}
		String r = root.trim();
		try {
			if (isUrl(r)) {
				return FileSourceFactory.getFileSource(r);
			}
			FileSourceFactory factory;
			if (fileSource == null || fileSource.trim().isEmpty()) {
				factory = FileSourceFactory.getDefaultFactory();
			} else {
				factory = FileSourceFactory.getFileSourceFactory(fileSource.trim().toLowerCase(Locale.ROOT));
				if (factory == null) {
					throw new IOException("There is no file system " + fileSource.trim()
							+ (fileSourceSetting == null ? "" : " (" + fileSourceSetting + ")"));
				}
				if (connection != null && !connection.isEmpty()) {
					factory.setConnectionProperties(connection);
				}
			}
			// (some file systems connect when first used, others don't: connect it here)
			if (!factory.isConnected() && !factory.connect()) {
				throw new IOException("Can't connect");
			}
			return factory.createFileSource(r);
		} catch (IOException | RuntimeException e) {
			String why = e.getMessage() == null ? e.toString() : e.getMessage();
			if (isUrl(r) && !safe(r).equals(r)) {
				// a file system's message may repeat the URL, and its query holds the settings
				why = why.replace(r, safe(r));
				IOException clean = new IOException(why);
				clean.setStackTrace(e.getStackTrace());
				throw clean;
			}
			throw e instanceof IOException ? (IOException) e : new IOException(why, e);
		}
	}

	/** A root for a message: a URL without its query (where connection settings and secrets would be) */
	public static String safe(String root) {
		if (root == null) {
			return null;
		}
		int q = root.indexOf('?');
		return q < 0 ? root : root.substring(0, q) + "?...";
	}

	/**
	 * Where a store's root is, for a message: the root (a URL without its query) and, for a path on
	 * another file system, which one.
	 */
	public static String describe(String root, String fileSource) {
		if (root == null) {
			return "?";
		}
		String r = safe(root.trim());
		if (isUrl(root) || fileSource == null || fileSource.trim().isEmpty()) {
			return r;
		}
		return r + " on " + fileSource.trim();
	}

	/**
	 * A folder under the root, from a relative path ({@code .smtp-queue}, {@code a/b}): each
	 * segment a child, so it works on any file system.
	 *
	 * @throws IOException if the path leaves the root ({@code ..}) or is absolute
	 */
	public static FileSource child(FileSource root, String relative) throws IOException {
		FileSource ret = root;
		String p = relative == null ? "" : relative.replace('\\', '/');
		if (p.startsWith("/")) {
			throw new IOException(relative + " must be under the mail root");
		}
		for (String seg : p.split("/")) {
			if (seg.isEmpty() || seg.equals(".")) {
				continue;
			}
			if (seg.equals("..")) {
				throw new IOException(relative + " must be under the mail root");
			}
			ret = ret.getChild(seg);
		}
		return ret;
	}
}
