package us.bringardner.parley.mail.server;

import java.net.URL;
import java.util.Properties;

import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/**
 * A file system for the tests (registered in META-INF/services): files in memory, and the connection
 * settings it was given kept for the test to look at. Any file system gets its settings the same way.
 */
public class RecordingFileSourceFactory extends MemoryFileSourceFactory {

	private static final long serialVersionUID = 1L;
	public static final String ID = "recording";

	/** The last one made, and what it was given */
	static volatile RecordingFileSourceFactory last;
	Properties settings;
	URL url;

	public RecordingFileSourceFactory() {
		last = this;
	}

	@Override
	public String getTypeId() {
		return ID;
	}

	@Override
	public void setConnectionProperties(Properties prop) {
		settings = (Properties) prop.clone();
		super.setConnectionProperties(prop);
	}

	@Override
	public void setConnectionProperties(URL url) {
		this.url = url;
		super.setConnectionProperties(url);
	}
}
