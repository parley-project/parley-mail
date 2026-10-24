package us.bringardner.parley.mail.server;

import java.util.Collections;
import java.util.List;

import us.bringardner.parley.net.server.ServerProvider;

/**
 * A mail server's {@link ServerProvider}: it reads the suite's shared settings file,
 * {@code parley-mail.properties} (the mail root, the users, the TLS key store ...), before its own,
 * and is off when its port is 0.
 *
 * @since 2.0.0
 */
public abstract class MailServerProvider implements ServerProvider {

	/** The settings every mail server shares, read before each server's own file */
	public static final String SHARED_SETTINGS_FILE = "parley-mail.properties";

	private final String id;
	private final String description;
	private final String settingsFile;
	private final String configProperty;

	/**
	 * @param settingsFile   the server's own file, e.g. parley-pop3.properties (POP3 and POP3S share it)
	 * @param configProperty the property naming a file to read instead, e.g. parley.mail.pop3.configFile
	 */
	protected MailServerProvider(String id, String description, String settingsFile, String configProperty) {
		this.id = id;
		this.description = description;
		this.settingsFile = settingsFile;
		this.configProperty = configProperty;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public String description() {
		return description;
	}

	@Override
	public List<String> sharedSettingsFiles() {
		return Collections.singletonList(SHARED_SETTINGS_FILE);
	}

	@Override
	public String settingsFile() {
		return settingsFile;
	}

	@Override
	public String configProperty() {
		return configProperty;
	}

	/** Off when {@link #port()} is 0 */
	@Override
	public boolean enabled() {
		return port() > 0;
	}

	/** The port to listen on, from the system properties; 0 is off */
	public abstract int port();

	/** An integer setting, or the default when it isn't set or isn't a number */
	protected static int intProperty(String name, int defaultValue) {
		String v = System.getProperty(name);
		if (v == null || v.trim().isEmpty()) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}
}
