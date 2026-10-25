package org.adelheid.oneiro.plugin;

/** The permissions a plugin may request in its manifest, each of which puts it under review on Mod.io. */
public enum Permission {

	/** {@code HTTPRequest} to the manifest's base URLs. */
	HTTP_ACCESS("httpAccess"),
	/** {@code Chat.AddMessage} and the {@code ChatMessage} event. */
	CHAT_ACCESS("chatAccess"),
	/** {@code Player.GetTrackingID()}. */
	TRACKING_ACCESS("trackingAccess");

	private final String manifestName;

	Permission(String manifestName) {
		this.manifestName = manifestName;
	}

	/** The permission's name in {@code plugin.json}. */
	public String manifestName() {
		return manifestName;
	}
}
