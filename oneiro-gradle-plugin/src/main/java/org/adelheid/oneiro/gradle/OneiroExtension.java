package org.adelheid.oneiro.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.lang.model.SourceVersion;
import org.adelheid.oneiro.plugin.LuaLibraries;
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
import org.jspecify.annotations.Nullable;

/**
 * The {@code oneiro { }} block of a plugin's build:
 *
 * <pre>{@code
 * oneiro {
 *     stubs {
 *         local(file("../rs3-api-types"))
 *     }
 *     plugin {
 *         name = "Herb Tracker"
 *         contactEmail = "plugins@example.org"
 *         settingsIcon = true
 *         dependencies {
 *             required("gauges", 5151) {
 *                 types { local(file("lua-types/gauges")) }
 *             }
 *         }
 *         permissions { optional(chatAccess) }
 *         urls { url("prices", "https://prices.example.org") }
 *     }
 *     libraries {
 *         vendored("widgets") {
 *             remote("https://codeberg.org/<owner>/<repository>", "<commit>")
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

		/** A checkout of rs3-api-types on this machine. */
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

		private final ObjectFactory objects;
		private final List<PluginDependency> dependencies = new ArrayList<>();

		@Inject
		public PluginManifest(ObjectFactory objects) {
			this.objects = objects;
		}

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

		/** The dependencies the build declares. */
		List<PluginDependency> declaredDependencies() {
			return List.copyOf(dependencies);
		}

		/**
		 * The dependencies that ship but name only a folder on this machine, which {@code checkPlugin} refuses: each by
		 * its alias, with its kind, {@code required} or {@code optional}.
		 */
		Map<String, String> unpublishedDependencies() {
			Map<String, String> unpublished = new TreeMap<>();
			for (PluginDependency dependency : dependencies) {
				if (!dependency.getDevelopment().get() && !dependency.getModId().isPresent()) {
					unpublished.put(dependency.getName(), dependency.getUse().get().name().toLowerCase(Locale.ROOT));
				}
			}
			return unpublished;
		}

		/**
		 * Whether the development folder's manifest differs from the shipped one: a dependency names a folder on this
		 * machine, or only the dev source set uses one.
		 */
		boolean developsDependencies() {
			return dependencies.stream()
					.anyMatch(dependency -> dependency.getDevelopment().get() || dependency.getDevFolder().isPresent());
		}

		/**
		 * Plugins this one needs or uses, each exposed to it as the Lua global of its alias: a published one by its
		 * Mod.io ID, and each with a block for its types and its folder on this machine,
		 * {@code required("gauges", 5151) { types { local(file("lua-types/gauges")) } }}.
		 */
		public final class Dependencies {

			/** A plugin this one cannot do without. */
			public void required(String alias, int modId) {
				declare(alias, LuaLibraries.Use.REQUIRED, false, modId, dependency -> { });
			}

			/** A plugin this one cannot do without, with its types or its folder on this machine. */
			public void required(String alias, int modId, Action<? super PluginDependency> action) {
				declare(alias, LuaLibraries.Use.REQUIRED, false, modId, action);
			}

			/** A plugin not published yet, by its folder on this machine, {@code devFolder(...)}, which cannot ship. */
			public void required(String alias, Action<? super PluginDependency> action) {
				declare(alias, LuaLibraries.Use.REQUIRED, false, null, action);
			}

			/**
			 * A plugin used when present, which the player may not have, or may turn off while this one runs; code
			 * reaches it where a check of {@code isLoaded()} does.
			 */
			public void optional(String alias, int modId) {
				declare(alias, LuaLibraries.Use.OPTIONAL, false, modId, dependency -> { });
			}

			/** A plugin used when present, with its types or its folder on this machine. */
			public void optional(String alias, int modId, Action<? super PluginDependency> action) {
				declare(alias, LuaLibraries.Use.OPTIONAL, false, modId, action);
			}

			/** A plugin used when present that is not published yet, by its folder on this machine. */
			public void optional(String alias, Action<? super PluginDependency> action) {
				declare(alias, LuaLibraries.Use.OPTIONAL, false, null, action);
			}

			/**
			 * A plugin only the dev source set uses, which only the development folder's manifest requires; only the
			 * dev source set has its bindings.
			 */
			public void development(String alias, int modId) {
				declare(alias, LuaLibraries.Use.REQUIRED, true, modId, dependency -> { });
			}

			/** A plugin only the dev source set uses, with its types or its folder on this machine. */
			public void development(String alias, int modId, Action<? super PluginDependency> action) {
				declare(alias, LuaLibraries.Use.REQUIRED, true, modId, action);
			}

			/** A plugin only the dev source set uses, by its folder on this machine. */
			public void development(String alias, Action<? super PluginDependency> action) {
				declare(alias, LuaLibraries.Use.REQUIRED, true, null, action);
			}

			private void declare(String alias, LuaLibraries.Use use, boolean development, @Nullable Integer modId,
					Action<? super PluginDependency> action) {
				if (dependencies.stream().anyMatch(dependency -> dependency.getName().equals(alias))) {
					throw new GradleException("The dependency " + alias + " is declared twice; keep one.");
				}
				PluginDependency dependency = objects.newInstance(PluginDependency.class, alias);
				dependency.getUse().set(use);
				dependency.getDevelopment().set(development);
				if (modId != null) {
					dependency.getModId().set(modId);
				}
				action.execute(dependency);
				dependencies.add(dependency);
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
						+ " no word Java reserves, such as widgets.");
			}
			return name;
		}
	}

	/**
	 * A plugin this one depends on, which the client gives it as the global of its alias: by its Mod.io ID, the folder
	 * of a plugin on this machine that the development folder's manifest names instead, and the LuaCATS files its
	 * bindings come from. Without types, it has no bindings, and only Lua of the plugin's own can use it.
	 */
	public abstract static class PluginDependency implements Named {

		private final String name;
		private @Nullable LibraryTypes types;

		@Inject
		public PluginDependency(String name) {
			this.name = name;
		}

		/** The alias: the Lua global the client gives the plugin, and the package of its bindings. */
		@Input
		@Override
		public String getName() {
			return name;
		}

		/** Whether the plugin cannot do without it; set by {@code required(...)} or {@code optional(...)}. */
		@Input
		public abstract Property<LuaLibraries.Use> getUse();

		/** Whether only the dev source set uses it; set by {@code development(...)}. */
		@Input
		public abstract Property<Boolean> getDevelopment();

		/** Its Mod.io ID, by which players' clients find it; unset for a plugin not published yet. */
		@Optional
		@Input
		public abstract Property<Integer> getModId();

		/** The folder of a plugin on this machine that the development folder's manifest names instead. */
		@Optional
		@Input
		public abstract Property<String> getDevFolder();

		@Inject
		protected abstract ObjectFactory getObjects();

		/** The types its bindings come from, which {@code oneiroLibraries} stages; null when the build names none. */
		@Internal
		public @Nullable LibraryTypes getTypes() {
			return types;
		}

		/**
		 * The folder of a plugin on this machine, by its name in the client's plugin folders, which the development
		 * folder's manifest names in place of the Mod.io ID: a library not published yet, or a local copy of one.
		 */
		public void devFolder(String folder) {
			getDevFolder().set(folder);
		}

		/** Where its LuaCATS files come from: definition files of the plugin's, or a repository of community types. */
		public void types(Action<? super LibraryTypes> action) {
			LibraryTypes declared = types;
			if (declared == null) {
				declared = getObjects().newInstance(LibraryTypes.class, name);
				declared.getUse().set(getUse());
				declared.getDevelopment().set(getDevelopment());
				types = declared;
			}
			action.execute(declared);
		}
	}

	/**
	 * Lua the build names: a checkout on this machine or a repository at a full commit, the folder of it that counts,
	 * and an overrides file for its bindings. As a task's input, its local checkout counts by the files that are read
	 * from it.
	 */
	public abstract static class LuaCode implements Named {

		private final String name;

		protected LuaCode(String name) {
			this.name = name;
			getFrom().convention("");
		}

		@Input
		@Override
		public String getName() {
			return name;
		}

		/** A checkout on this machine. */
		@Internal
		public abstract DirectoryProperty getLocal();

		/** A repository on GitHub, Codeberg or GitLab, fetched at {@link #getCommit()}. */
		@Optional
		@Input
		public abstract Property<String> getRemote();

		/** The full commit of the remote. */
		@Optional
		@Input
		public abstract Property<String> getCommit();

		/** The folder of it, from its root, whose Lua files count; its root by default. */
		@Input
		public abstract Property<String> getFrom();

		/** The plugin's overrides file for the bindings, in the format of Oneiro's own. */
		@Optional
		@InputFile
		@PathSensitive(PathSensitivity.NONE)
		public abstract RegularFileProperty getOverrides();

		/** The code from a checkout on this machine. */
		public void local(File directory) {
			getLocal().set(directory);
		}

		/** The code from a repository on GitHub, Codeberg or GitLab, at a full commit. */
		public void remote(String url, String commit) {
			getRemote().set(url);
			getCommit().set(commit);
		}

		/** The folder of it whose Lua files count, from its root. */
		public void from(String folder) {
			getFrom().set(folder);
		}

		/** An overrides file for the bindings, for what the annotations do not say. */
		public void overrides(File file) {
			getOverrides().set(file);
		}
	}

	/**
	 * A library the plugin vendors: its code, of which the Lua files of {@code from} go to the plugin's root, as the
	 * library's documentation lays them out, and its licence.
	 */
	public abstract static class VendoredLibrary extends LuaCode {

		@Inject
		public VendoredLibrary(String name) {
			super(name);
			getDevelopment().convention(false);
		}

		/**
		 * The file of the library's licence, from its root; by default the one file at its root named like a licence,
		 * such as {@code LICENSE} or {@code LICENCE.md}.
		 */
		@Optional
		@Input
		public abstract Property<String> getLicence();

		/** Whether only the dev source set uses the library; set by {@code development(...)}. */
		@Input
		public abstract Property<Boolean> getDevelopment();

		/** The file of the library's licence, which ships under {@code licences/<name>/}. */
		public void licence(String file) {
			getLicence().set(file);
		}
	}

	/**
	 * The LuaCATS files of a library plugin the plugin depends on, which never ship: definition files written from its
	 * docs, or a repository of community types, with {@code from} for a folder of it, such as {@code library} in one
	 * laid out for LLS-Addons. They declare the global of its alias, or give the {@code main.lua} that returns it.
	 */
	public abstract static class LibraryTypes extends LuaCode {

		@Inject
		public LibraryTypes(String name) {
			super(name);
		}

		/** Whether the plugin cannot do without the library; its dependency's. */
		@Input
		public abstract Property<LuaLibraries.Use> getUse();

		/** Whether only the dev source set uses the library; its dependency's. */
		@Input
		public abstract Property<Boolean> getDevelopment();
	}

	/** The Lua 5.4 that runs the plugin's Lua tests: the bundled Lua 5.4.8 unless an installed one is named. */
	public abstract static class Lua {

		/** An installed Lua 5.4 interpreter; unset, the tests run on the bundled one. */
		public abstract RegularFileProperty getExecutable();
	}
}
