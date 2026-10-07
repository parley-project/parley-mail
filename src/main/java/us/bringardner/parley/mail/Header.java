package us.bringardner.parley.mail;

import java.io.Serializable;

public class Header implements Serializable{
	private static final long serialVersionUID = 1L;
	
	
	
	public static Header parseHeader(String line) {
		Header ret = new Header();
		int idx = line.indexOf(':');
		if( idx < 0) {
			ret.name = line;
		} else {
			ret.name = line.substring(0,idx).trim();
			ret.value = line.substring(idx+1).trim();
		}
		
		return ret;
	}
	
	protected String name;	
	protected String value;
	
	public Header() {}
	public Header(String name,String value) {
		this.name = name;
		this.value = value;
	}
	
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getValue() {
		return value;
	}
	public void setValue(String value) {
		this.value = value;
	}
	
	
}
