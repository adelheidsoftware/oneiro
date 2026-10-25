package org.adelheid.oneiro.gradle;

import java.io.File;
import javax.inject.Inject;
import org.adelheid.oneiro.plugin.Permission;
import org.gradle.api.Action;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;

/**
 * The {@code oneiro { }} block of a plugin's build:
 *
 * <pre>{@code
 * oneiro {
 *     stubs {
 *         local(file("../runescape-api-stubs"))
 *     }
 *     plugin {
 *         name = "Herb Tracker"
 *         contactEmail = "plugins@example.org"
 *         settingsIcon = true
 *         dependencies { required("prettyui", 6398181) }
 *         permissions { optional(chatAccess) }
 *         urls { url("prices", "https://prices.example.org") }
 *     }
 * }
 * }</pre>
 */
public abstract class OneiroExtension {

	private final Stubs stubs;
	private final PluginManifest plugin;
	private final Lua lua;

	@Inject
	public OneiroExtension(ObjectFactory objects) {
		this.stubs = objects.newInstance(Stubs.class);
		this.plugin = objects.newInstance(PluginManifest.class);
		this.lua = objects.newInstance(Lua.class);
	}

	/**
	 * The plugin's root package, whose subpackages become folders under {@code src/}; by default the plugin class's.
	 */
	public abstract Property<String> getRootPackage();

	/** Whether a warning, such as a comment Oneiro cannot place, fails the build; false by default. */
	public abstract Property<Boolean> getWarningsAsErrors();

	/**
	 * The client's {@code dev_plugins} folder, where {@code linkDevPlugin} links the plugin; by default the beta
	 * client's on Windows, {@code C:\ProgramData\Jagex\RuneScape-BETA\dev_plugins}.
	 */
	public abstract DirectoryProperty getDevPlugins();

	/** Where the RS3 API's stubs come from, which the bindings are generated from. */
	public Stubs getStubs() {
		return stubs;
	}

	public void stubs(Action<? super Stubs> action) {
		action.execute(stubs);
	}

	/** The plugin's manifest, written as {@code plugin.json}; its version is the project's. */
	public PluginManifest getPlugin() {
		return plugin;
	}

	public void plugin(Action<? super PluginManifest> action) {
		action.execute(plugin);
	}

	/** The Lua that runs the plugin's Lua tests. */
	public Lua getLua() {
		return lua;
	}

	public void lua(Action<? super Lua> action) {
		action.execute(lua);
	}

	/**
	 * Where the stubs come from: a local checkout, or a GitHub repository that Oneiro fetches into Gradle's cache,
	 * at a pinned commit or at the latest commit of its default branch, checked for at most once a day.
	 */
	public abstract static class Stubs {

		public abstract DirectoryProperty getLocal();

		public abstract Property<String> getRemote();

		public abstract Property<String> getCommit();

		/** A checkout of runescape-api-stubs on this machine. */
		public void local(File directory) {
			getLocal().set(directory);
		}

		/** A GitHub repository, at the latest commit of its default branch. */
		public void remote(String url) {
			getRemote().set(url);
		}

		/** A GitHub repository at a commit, which keeps builds the same on every machine. */
		public void remote(String url, String commit) {
			getRemote().set(url);
			getCommit().set(commit);
		}
	}

	/** The manifest: what people see, what the plugin may do, and what it needs. */
	public abstract static class PluginManifest {

		/**
		 * The plugin's identifier, lowercase words joined by hyphens: the name of its link in {@code dev_plugins}, of
		 * its zip, and the prefix of its event subscriptions. By default the project's name.
		 */
		public abstract Property<String> getId();

		/** The plugin's name as people see it. */
		public abstract Property<String> getName();

		/** The address the legal sections require; not a Mod.io or RuneScape login. */
		public abstract Property<String> getContactEmail();

		/** A settings icon in the Plugin Hub, which the {@code SettingsLayerReady} event fills. */
		public abstract Property<Boolean> getSettingsIcon();

		/** An icon in the Plugins ribbon window, which the {@code ContentLayerReady} event fills. */
		public abstract Property<Boolean> getRibbonIcon();

		/**
		 * Each required dependency by alias, as {@code mod:<id>} or {@code folder:<name>}; set through
		 * {@link #dependencies}.
		 */
		public abstract MapProperty<String, String> getRequiredDependencies();

		/**
		 * Each optional dependency by alias, as {@code mod:<id>} or {@code folder:<name>}; set through
		 * {@link #dependencies}.
		 */
		public abstract MapProperty<String, String> getOptionalDependencies();

		/** The {@code plugin.json} names of the required permissions; set through {@link #permissions}. */
		public abstract SetProperty<String> getRequiredPermissions();

		/** The {@code plugin.json} names of the optional permissions; set through {@link #permissions}. */
		public abstract SetProperty<String> getOptionalPermissions();

		/** The base URLs by alias; set through {@link #urls}. */
		public abstract MapProperty<String, String> getUrls();

		public void dependencies(Action<? super Dependencies> action) {
			action.execute(new Dependencies());
		}

		public void permissions(Action<? super Permissions> action) {
			action.execute(new Permissions());
		}

		public void urls(Action<? super Urls> action) {
			action.execute(new Urls());
		}

		/** Plugins this one needs or uses, each exposed to it as the Lua global of its alias. */
		public final class Dependencies {

			/** A published plugin, by its Mod.io ID, as a library such as PrettyUI documents. */
			public void required(String alias, int modId) {
				getRequiredDependencies().put(alias, "mod:" + modId);
			}

			/** A plugin on this machine, by its folder name in the client's plugin folders. */
			public void required(String alias, String folder) {
				getRequiredDependencies().put(alias, "folder:" + folder);
			}

			/** A published plugin used when present, by its Mod.io ID. */
			public void optional(String alias, int modId) {
				getOptionalDependencies().put(alias, "mod:" + modId);
			}

			/** A plugin on this machine used when present, by its folder name in the client's plugin folders. */
			public void optional(String alias, String folder) {
				getOptionalDependencies().put(alias, "folder:" + folder);
			}
		}

		/** The permissions the plugin requests: {@code optional(chatAccess)}, which is the preferred form. */
		public final class Permissions {

			public Permission getHttpAccess() {
				return Permission.HTTP_ACCESS;
			}

			public Permission getChatAccess() {
				return Permission.CHAT_ACCESS;
			}

			public Permission getTrackingAccess() {
				return Permission.TRACKING_ACCESS;
			}

			/** A permission the plugin cannot work without. */
			public void required(Permission permission) {
				getRequiredPermissions().add(permission.manifestName());
			}

			/** A permission that unlocks more, which the code checks with {@code CurrentPlugin.HasPermission}. */
			public void optional(Permission permission) {
				getOptionalPermissions().add(permission.manifestName());
			}
		}

		/** The base URLs the plugin may reach, by the alias code uses, {@code Urls.prices}. */
		public final class Urls {

			/** A base URL, which code reaches as {@code Urls.<alias>} and the Lua as {@code Plugin.urls.<alias>}. */
			public void url(String alias, String url) {
				getUrls().put(alias, url);
			}
		}
	}

	/** The Lua 5.4 that runs the plugin's Lua tests: the bundled Lua 5.4.8 unless an installed one is named. */
	public abstract static class Lua {

		/** An installed Lua 5.4 interpreter; unset, the tests run on the bundled one. */
		public abstract RegularFileProperty getExecutable();
	}
}
