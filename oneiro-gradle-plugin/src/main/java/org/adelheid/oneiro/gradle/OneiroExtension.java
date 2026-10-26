package org.adelheid.oneiro.gradle;

import java.io.File;
import java.util.List;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.lang.model.SourceVersion;
import org.adelheid.oneiro.plugin.Permission;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Named;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

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
 *     libraries {
 *         vendored("zyklib") {
 *             remote("https://codeberg.org/ZyklopMarco/runescape-api-zyklib", "<commit>")
 *             from("src")
 *         }
 *     }
 * }
 * }</pre>
 */
public abstract class OneiroExtension {

	private final Stubs stubs;
	private final PluginManifest plugin;
	private final Libraries libraries;
	private final Lua lua;

	@Inject
	public OneiroExtension(ObjectFactory objects) {
		this.stubs = objects.newInstance(Stubs.class);
		this.plugin = objects.newInstance(PluginManifest.class);
		this.libraries = objects.newInstance(Libraries.class);
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

	/** The Lua libraries the plugin vendors. */
	public Libraries getLibraries() {
		return libraries;
	}

	public void libraries(Action<? super Libraries> action) {
		action.execute(libraries);
	}

	/** The Lua that runs the plugin's Lua tests. */
	public Lua getLua() {
		return lua;
	}

	public void lua(Action<? super Lua> action) {
		action.execute(lua);
	}

	/**
	 * Where the stubs come from: a local checkout, or a repository on GitHub, Codeberg or GitLab that Oneiro fetches
	 * into Gradle's cache, at a pinned commit or at the latest commit of its default branch, checked for at most once a
	 * day.
	 */
	public abstract static class Stubs {

		public abstract DirectoryProperty getLocal();

		public abstract Property<String> getRemote();

		public abstract Property<String> getCommit();

		/** A checkout of runescape-api-stubs on this machine. */
		public void local(File directory) {
			getLocal().set(directory);
		}

		/** A repository on GitHub, Codeberg or GitLab, at the latest commit of its default branch. */
		public void remote(String url) {
			getRemote().set(url);
		}

		/** A repository on GitHub, Codeberg or GitLab at a commit, which keeps builds the same on every machine. */
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

	/**
	 * The Lua libraries the plugin vendors: copied into it, as their documentation lays them out, with their licences,
	 * and bound from their LuaCATS annotations, so that Java and Kotlin call them as Lua does. Each has a name, which
	 * names the package of its bindings, {@code org.adelheid.oneiro.lib.<name>}, and the folder of its licence,
	 * {@code licences/<name>/}.
	 */
	public abstract static class Libraries {

		/** A library's name: a word of lowercase letters and digits that Java can take for a package. */
		private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*");

		private final NamedDomainObjectContainer<VendoredLibrary> declared;

		@Inject
		public Libraries(ObjectFactory objects) {
			this.declared = objects.domainObjectContainer(VendoredLibrary.class);
		}

		/** A library that ships with the plugin, its Lua in the plugin's root. */
		public void vendored(String name, Action<? super VendoredLibrary> action) {
			declared.create(checked(name), action);
		}

		/**
		 * A library that only the dev source set uses, which goes into the development folder alone and never ships;
		 * only the dev source set has its bindings.
		 */
		public void development(String name, Action<? super VendoredLibrary> action) {
			declared.create(checked(name), library -> {
				library.getDevelopment().set(true);
				action.execute(library);
			});
		}

		/** The libraries the build declares. */
		List<VendoredLibrary> declared() {
			return List.copyOf(declared);
		}

		private static String checked(String name) {
			if (!NAME.matcher(name).matches() || SourceVersion.isKeyword(name)) {
				throw new GradleException("A library's name, " + name + ", names the package of its bindings,"
						+ " org.adelheid.oneiro.lib.<name>: lowercase letters and digits, starting with a letter, and"
						+ " no word Java reserves, such as zyklib.");
			}
			return name;
		}
	}

	/**
	 * A library the plugin vendors: where its code comes from, a checkout on this machine or a repository at a full
	 * commit; the folder of it whose Lua files go to the plugin's root; its licence; and an overrides file for its
	 * bindings. As a task's input, its local checkout counts by the files that are read from it.
	 */
	public abstract static class VendoredLibrary implements Named {

		private final String name;

		@Inject
		public VendoredLibrary(String name) {
			this.name = name;
			getFrom().convention("");
			getDevelopment().convention(false);
		}

		@Input
		@Override
		public String getName() {
			return name;
		}

		/** A checkout of the library on this machine. */
		@Internal
		public abstract DirectoryProperty getLocal();

		/** A repository of the library on GitHub, Codeberg or GitLab, fetched at {@link #getCommit()}. */
		@Optional
		@Input
		public abstract Property<String> getRemote();

		/** The full commit of the remote that is vendored. */
		@Optional
		@Input
		public abstract Property<String> getCommit();

		/** The folder of the library, from its root, whose Lua files go to the plugin's root; its root by default. */
		@Input
		public abstract Property<String> getFrom();

		/**
		 * The file of the library's licence, from its root; by default the one file at its root named like a licence,
		 * such as {@code LICENSE} or {@code LICENCE.md}.
		 */
		@Optional
		@Input
		public abstract Property<String> getLicence();

		/** The plugin's overrides file for the library's bindings, in the format of Oneiro's own. */
		@Optional
		@InputFile
		@PathSensitive(PathSensitivity.NONE)
		public abstract RegularFileProperty getOverrides();

		/** Whether only the dev source set uses the library; set by {@code development(...)}. */
		@Input
		public abstract Property<Boolean> getDevelopment();

		/** The library's code from a checkout on this machine. */
		public void local(File directory) {
			getLocal().set(directory);
		}

		/** The library's code from a repository on GitHub, Codeberg or GitLab, at a full commit. */
		public void remote(String url, String commit) {
			getRemote().set(url);
			getCommit().set(commit);
		}

		/** The folder whose Lua files go to the plugin's root, as the library's documentation lays them out. */
		public void from(String folder) {
			getFrom().set(folder);
		}

		/** The file of the library's licence, which ships under {@code licences/<name>/}. */
		public void licence(String file) {
			getLicence().set(file);
		}

		/** An overrides file for the library's bindings, for what its annotations do not say. */
		public void overrides(File file) {
			getOverrides().set(file);
		}
	}

	/** The Lua 5.4 that runs the plugin's Lua tests: the bundled Lua 5.4.8 unless an installed one is named. */
	public abstract static class Lua {

		/** An installed Lua 5.4 interpreter; unset, the tests run on the bundled one. */
		public abstract RegularFileProperty getExecutable();
	}
}
